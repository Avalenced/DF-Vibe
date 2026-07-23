package com.dfvibe;

import com.dfvibe.PlotDetector.PlotInfo;
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.reflect.TypeToken;
import dev.dfonline.codeclient.CodeClient;
import dev.dfonline.codeclient.action.None;
import dev.dfonline.codeclient.action.impl.PlaceTemplates;
import dev.dfonline.codeclient.location.Plot;

import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

/**
 * The sync loop. Always run from a worker thread (network + subprocess).
 *
 *   pull   : scan plot -> codec decompile -> write ALL .py files for the project.
 *   push   : read .py -> codec recompile -> place swap (replace/add by header; no delete).
 *   deploy : read .py -> codec recompile -> CLEAR plot, then place all (exact mirror;
 *            supports deletes and cloning a codebase onto a fresh/other plot).
 *
 * Plot identity (id from /locate) is recorded per project in .df-vibe.json and in a
 * workspace registry (plotId -> project), enabling bare `/df pull` auto-resume and a
 * cross-plot warning on push. /df backup snapshots the live plot to .df-backups/ on demand
 * (deploy/restore do NOT auto-snapshot - run /df backup first if you want a safety net).
 */
public final class SyncService {
	private static final Gson GSON = new Gson();
	private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
	private static final long DETECT_MS = 4000;
	// Max code-template token length we'll place. The item's codetemplatedata NBT string is ~token+75 chars, and
	// MC's packet encoder caps a single NBT string at 65535 bytes (writeUTF) - over that, placing it crashes the
	// client with a netty EncoderException + disconnect. Leave headroom for the JSON wrapper.
	private static final int MAX_TEMPLATE_TOKEN = 65000;
	private static final AtomicBoolean BUSY = new AtomicBoolean(false);
	private static final AtomicBoolean CANCEL = new AtomicBoolean(false);
	/** /df end pause: freeze the running scan/place in place (it holds position; the heartbeat keeps the socket
	 *  alive) until /df end continue. Read on the game thread by ScanPlot/PlaceTemplates. */
	public static volatile boolean PAUSED = false;
	private static volatile Thread WORKER;
	private static final String AUTH_FAIL = "Authorization failed unexpectedly (the bundled API should "
			+ "auto-approve). Try again; if it persists, make sure the mod loaded and nothing else uses port 31375.";

	private SyncService() {}

	/** The port to reach THIS instance's bundled API on. The API auto-increments off 31375 when that's busy
	 *  (so several MC instances can each scan their own codespace), and it lives in the same JVM as us, so we
	 *  connect to whatever it actually bound. A user-set custom port (codeClientPort != the 31375 default) is
	 *  honoured as an explicit override. */
	private static int apiPort(Config cfg) {
		return cfg.codeClientPort != 31375 ? cfg.codeClientPort
				: dev.dfonline.codeclient.websocket.SocketHandler.boundPort;
	}

	/** Only one sync may run at a time (the API allows a single app connection). */
	public static boolean tryLock() { return BUSY.compareAndSet(false, true); }

	public static void unlock() {
		BUSY.set(false); WORKER = null; PAUSED = false;
	}

	/** Mark the start of an operation on this worker thread (enables /df end to cancel it). */
	private static void begin() { WORKER = Thread.currentThread(); CANCEL.set(false); PAUSED = false; }

	/** /df end pause | continue: freeze / resume the running op. No-op (with a note) if nothing is running. */
	public static void pause(boolean p) {
		if (!BUSY.get()) { Chat.warn("Nothing is running to " + (p ? "pause" : "resume") + "."); return; }
		PAUSED = p;
		if (p) Chat.info("Paused. Use /df end continue to resume.");
		else Chat.good("Resumed.");
	}

	/** /df showmissingchest: toggle the highlight of every still-unscanned chest/line (only shows while a scan
	 *  is actually running, since that's where the not-yet-read list lives). */
	public static void toggleShowMissing() {
		boolean on = dev.dfonline.codeclient.action.impl.ScanPlot.toggleShowMissing();
		if (on) Chat.good("Unread-chest highlight on.");
		else Chat.info("Unread-chest highlight off.");
	}

	/** /df end: signal the running operation to stop and interrupt its blocking wait. */
	public static boolean cancel() {
		if (!BUSY.get()) return false;
		CANCEL.set(true);
		PAUSED = false; // ending a paused op must actually stop it
		Thread w = WORKER;
		if (w != null) w.interrupt();
		// Interrupting the worker only unblocks its websocket WAIT - the actual placer/scanner runs as
		// CodeClient.currentAction on the GAME thread and would keep going afterward (this is why "/df end"
		// used to keep placing, and left the scan's progress bar spinning). Stop it the way /abort does:
		// swap in a no-op action and clear the API's queue, on the game thread. Then the next tick has
		// nothing to do.
		try {
			CodeClient.MC.execute(() -> {
				CodeClient.confirmingAction = null;
				CodeClient.currentAction = new None();
				try { CodeClient.API.abort(); } catch (Throwable ignored) {}
				try { if (CodeClient.MC.player != null) CodeClient.MC.player.noClip = false; } catch (Throwable ignored) {} // drop any scan noclip
				// Stop dead: zero any residual velocity so the player does not keep drifting.
				try { if (CodeClient.MC.player != null) CodeClient.MC.player.setVelocity(0, 0, 0); } catch (Throwable ignored) {}
				// A cancelled scan/salvage never reaches complete(), so the progress bar, the ETA model,
				// the ACTIVE scan pointer and the salvage offhand-reach item would linger - tear them down here.
				try { Progress.finish(); } catch (Throwable ignored) {}
				try { Eta.end(); } catch (Throwable ignored) {}
				try { dev.dfonline.codeclient.action.impl.ScanPlot.clearActive(); } catch (Throwable ignored) {}
				try { dev.dfonline.codeclient.action.impl.CaptureRegion.clearActive(); } catch (Throwable ignored) {}
				try {
					if (CodeClient.MC.getNetworkHandler() != null) {
						dev.dfonline.codeclient.Utility.makeHolding(net.minecraft.item.ItemStack.EMPTY);
						CodeClient.MC.getNetworkHandler().sendPacket(
							new net.minecraft.network.packet.c2s.play.CreativeInventoryActionC2SPacket(45, net.minecraft.item.ItemStack.EMPTY));
					}
				} catch (Throwable ignored) {}
			});
		} catch (Throwable ignored) {}
		return true;
	}

	/**
	 * /df end compile: stop a running SCAN gracefully and keep what it grabbed. Unlike {@link #cancel()}
	 * (which interrupts the worker and aborts), this just asks the scan to finish NOW - it returns the lines
	 * already collected through the normal websocket path, so the in-flight pull decompiles + writes them as a
	 * partial result instead of losing the whole pull. Returns false if no scan is running.
	 */
	public static boolean endCompile() {
		if (dev.dfonline.codeclient.action.impl.CaptureRegion.requestFinish()) return true; // build capture: write what we have
		return dev.dfonline.codeclient.action.impl.ScanPlot.requestFinish();
	}

	private static boolean cancelled(Exception e) {
		return CANCEL.get() || e instanceof InterruptedException;
	}

	/** Report an op's outcome: a clean "Cancelled" if /df end fired, else the real error. */
	private static void reportFail(String op, Exception e, boolean placeOp) {
		if (cancelled(e)) {
			Chat.warn("Cancelled." + (placeOp ? " The plot may be partially modified." : ""));
			Log.info("cancelled by /df end");
		} else {
			Chat.error(op + " failed: " + e.getMessage());
			Log.error(op + " failed", e);
			if (Log.file() != null) Chat.warn("Error details written to the log.", Chat.openFile("[open log]", Log.file().toString()));
		}
	}

	/** Shared "<op> stopped early" warning: links the diagnostic log if -log wrote one,
	 *  else hints how to get one. */
	private static void stoppedEarly(String op, boolean log) {
		if (log && Log.file() != null)
			Chat.warn(op + " stopped early.", Chat.openFile("[open log]", Log.file().toString()));
		else
			Chat.warn(op + " stopped early. Re-run with -log for a diagnostic log.");
	}

	// ===================================================================== PULL
	public static void pull(Config cfg, String projectName) {
		pull(cfg, projectName, false, false, false);
	}

	public static void pull(Config cfg, String projectName, boolean deep, boolean nomove, boolean save) {
		begin();
		Log.begin(cfg, "PULL " + (projectName != null ? projectName : "(auto-detect)")
				+ (deep ? " -chests" : "") + (nomove ? " -nomove" : ""), false);
		if (deep) Chat.info("-chests: rebuilding every line from its blocks and chests. Slower, but recovers lines too long for a normal pull.");
		if (nomove) Chat.info("-nomove: grabbing lines within reach. I'll point you toward the rest.");
		if (!cfg.ready()) { Chat.error("Set workspace + codec first (/df workspace, /df codec)."); return; }

		PlotInfo plot = PlotDetector.detect(cfg.locateCommand, DETECT_MS);
		String project = projectName;
		if (project == null || project.isBlank()) {
			if (plot == null) { Chat.error("Couldn't detect the plot and no name given. Use /df pull <name>."); return; }
			project = loadRegistry(cfg).get(plot.id());
			if (project == null) { Chat.error("Plot [" + plot.id() + "] isn't linked to a project yet - name it:",
					Chat.suggest("[/df pull <name>]", "/df pull ", "create/link a project for this plot")); return; }
			Chat.info("Plot [" + plot.id() + "] -> project '" + project + "'.");
		}
		Path dir = cfg.projectDir(project);
		backupDirtyFiles(cfg, dir, project); // a pull overwrites every .py - save unpushed edits first

		// -nomove needs the codespace bounds so it knows when the whole plot has been swept; take them from the
		// dev plot's geometry. The default (auto-sweep) pull discovers the plot itself and needs no box.
		int[] box = nomove ? codespaceBox(cfg) : null;
		try (CodeClientConn cc = CodeClientConn.connect(apiPort(cfg))) {
			if (!cc.auth(cfg)) { Chat.error(AUTH_FAIL); return; }
			if (!ensureCodeMode(cc)) return;
			if (nomove) Chat.info("-nomove: fly slowly through the whole codespace. I'll grab lines as you pass.");

			List<String> lines;
			dev.dfonline.codeclient.action.impl.ScanPlot.NO_MOVE = nomove; // ScanPlot reads these at init
			dev.dfonline.codeclient.action.impl.ScanPlot.REGION = box;
			// Save-as-you-go (-save): checkpoint grabbed lines to disk during the scan (every ~40), so a long
			// scan that later times out/dies still leaves usable .py files. Best-effort - a failed checkpoint
			// never disturbs the scan, and the authoritative full write still happens at the end on success.
			final Path fdir = dir;
			final Config fcfg = cfg;
			final List<String> checkpoint = new ArrayList<>();
			java.util.function.Consumer<String> onLine = save ? tok -> {
				synchronized (checkpoint) {
					checkpoint.add(tok);
					if (checkpoint.size() >= 40) { checkpointWrite(fcfg, fdir, new ArrayList<>(checkpoint)); checkpoint.clear(); }
				}
			} : null;
			try { lines = cc.scan(deep, save, onLine); } finally {
				if (save) synchronized (checkpoint) { if (!checkpoint.isEmpty()) { checkpointWrite(cfg, dir, checkpoint); checkpoint.clear(); } }
				dev.dfonline.codeclient.action.impl.ScanPlot.NO_MOVE = false;
				dev.dfonline.codeclient.action.impl.ScanPlot.REGION = null;
				Eta.end(); // complete() normally ends it, but an aborted/failed scan must not leak a stale ETA
			}
			if (lines.isEmpty()) {
				Chat.warn(nomove ? "No lines found in the codespace. Fly into the plot codespace, then retry."
						: "Scan found 0 lines. Make sure you're standing on the plot in dev/code mode (/dev), then retry.");
				return;
			}

			JsonObject req = new JsonObject();
			JsonArray arr = new JsonArray();
			lines.forEach(arr::add);
			req.add("lines", arr);
			JsonObject res = new Codec(cfg).run("decompile", GSON.toJson(req));
			if (res.has("warnings")) {
				for (var el : res.getAsJsonArray("warnings")) Chat.warn("Codec: " + el.getAsString());
			}
			if (!res.has("files")) { Chat.error("Codec error: no files returned.", "Nothing was written. Check the codec at /df path codec."); return; }

			Files.createDirectories(dir);
			List<String> written = new ArrayList<>();
			Map<String, String> hashes = new HashMap<>();
			for (var el : res.getAsJsonArray("files")) {
				JsonObject f = el.getAsJsonObject();
				String name = f.get("file").getAsString();
				Files.writeString(dir.resolve(name), f.get("source").getAsString());
				written.add(name);
				hashes.put(name, f.get("hash").getAsString());
			}
			saveHashes(dir, hashes); // baseline so the next push only sends what you actually edit
			int codecVersion = res.has("codec_version") ? res.get("codec_version").getAsInt() : 0;
			writeMeta(dir, project, plot, written.size(), codecVersion);
			if (plot != null) {
				Map<String, String> reg = loadRegistry(cfg);
				reg.put(plot.id(), project);
				saveRegistry(cfg, reg);
			}
			reportOrphans(dir, written);

			JsonArray errs = res.has("errors") ? res.getAsJsonArray("errors") : new JsonArray();
			if (errs.size() > 0) {
				Files.writeString(dir.resolve(".df-failed.json"), GSON.toJson(errs));
				Chat.warn(errs.size() + " line(s) couldn't be decompiled - SKIPPED (saved to .df-failed.json). "
						+ "They are NOT in this project; avoid /df deploy until fixed.");
			} else {
				Files.deleteIfExists(dir.resolve(".df-failed.json")); // clear stale diagnostics from a prior pull
			}

			cfg.activeProject = project;
			cfg.save();
			Chat.good("Pulled " + written.size() + " lines into '" + project + "'"
					+ (errs.size() > 0 ? " (" + errs.size() + " skipped)" : "") + ".",
					Chat.openFile("[open]", dir.toString()));
		} catch (Exception e) {
			reportFail("Pull", e, false);
		}
	}

	/** Save-as-you-go checkpoint: decompile a batch of just-grabbed tokens and write their .py files, best-effort.
	 *  Each line is its own file (keyed by header), so a partial batch decompiles fine; hashes/meta are skipped
	 *  (the authoritative final write handles those on success). Any failure is swallowed so a checkpoint can
	 *  never disturb the running scan. */
	private static void checkpointWrite(Config cfg, Path dir, List<String> tokens) {
		if (tokens == null || tokens.isEmpty()) return;
		try {
			JsonObject req = new JsonObject();
			JsonArray arr = new JsonArray();
			tokens.forEach(arr::add);
			req.add("lines", arr);
			JsonObject res = new Codec(cfg).run("decompile", GSON.toJson(req));
			Files.createDirectories(dir);
			if (res.has("files")) for (var el : res.getAsJsonArray("files")) {
				JsonObject f = el.getAsJsonObject();
				Files.writeString(dir.resolve(f.get("file").getAsString()), f.get("source").getAsString());
			}
			Chat.info("saved " + tokens.size() + " line(s) so far (-save checkpoint)");
		} catch (Throwable e) {
			// Never disturb the running scan - but do say the safety net has a hole in it.
			Chat.warn("-save checkpoint failed (" + e.getMessage() + ") - the scan continues; if it dies, these lines are lost.");
		}
	}

	/**
	 * -nomove coverage: each -nomove pull only grabs lines within ~64 blocks of where you stand, so one
	 * pass can't see the whole codespace. We remember every spot you've pulled from (.df-coverage.json),
	 * sample the codespace box (your /df region if set, else the detected plot), and tell you which part you
	 * still haven't been within reach of - so "it shouldn't scan what it can't see" becomes "go here next",
	 * and it only says done once you've physically swept the whole box.
	 */
	private static void reportNomoveCoverage(Config cfg, Path dir) {
		try {
			var pl = CodeClient.MC.player;
			if (pl == null) return;
			double px = pl.getX(), py = pl.getY(), pz = pl.getZ();
			int[] box = codespaceBox(cfg);
			if (box == null) {
				Chat.info("Stand in the plot codespace in dev mode so -nomove can track which parts you've covered.");
				return;
			}
			// Remember this spot, then reload the full set of spots we've pulled from.
			Path cf = dir.resolve(".df-coverage.json");
			List<double[]> pts = new ArrayList<>();
			if (Files.exists(cf)) {
				try {
					JsonArray arr = GSON.fromJson(Files.readString(cf), JsonArray.class);
					if (arr != null) for (var el : arr) {
						JsonArray a = el.getAsJsonArray();
						pts.add(new double[]{a.get(0).getAsDouble(), a.get(1).getAsDouble(), a.get(2).getAsDouble()});
					}
				} catch (Exception ignored) {}
			}
			pts.add(new double[]{px, py, pz});
			JsonArray out = new JsonArray();
			for (double[] p : pts) { JsonArray a = new JsonArray(); a.add(p[0]); a.add(p[1]); a.add(p[2]); out.add(a); }
			Files.writeString(cf, GSON.toJson(out));
			// Sample the box; a sample is "covered" if some spot we pulled from is within 64 blocks of it.
			int minX = Math.min(box[0], box[3]), maxX = Math.max(box[0], box[3]);
			int minY = Math.min(box[1], box[4]), maxY = Math.max(box[1], box[4]);
			int minZ = Math.min(box[2], box[5]), maxZ = Math.max(box[2], box[5]);
			final double R2 = 64 * 64;
			int total = 0, uncovered = 0;
			double bestD = Double.MAX_VALUE;
			int bx = 0, by = 0, bz = 0;
			for (int x = minX; x <= maxX; x += 30)
				for (int z = minZ; z <= maxZ; z += 30)
					for (int y = minY; y <= maxY; y += 5) {
						total++;
						boolean cov = false;
						for (double[] p : pts) {
							double dx = x - p[0], dy = y - p[1], dz = z - p[2];
							if (dx * dx + dy * dy + dz * dz <= R2) { cov = true; break; }
						}
						if (!cov) {
							uncovered++;
							double dx = x - px, dy = y - py, dz = z - pz, d = dx * dx + dy * dy + dz * dz;
							if (d < bestD) { bestD = d; bx = x; by = y; bz = z; }
						}
					}
			if (total == 0) return;
			if (uncovered == 0) {
				Chat.good("-nomove: whole codespace covered.");
				return;
			}
			int pct = (int) Math.round(100.0 * (total - uncovered) / total);
			Chat.info("-nomove: ~" + pct + "% of the codespace covered. Fly " + directionTo(px, py, pz, bx, by, bz)
					+ " (toward " + bx + " " + by + " " + bz + "), then /df pull -nomove again.");
		} catch (Exception e) {
			Chat.warn("Coverage check failed: " + e.getMessage());
		}
	}

	/** The codespace box [x1,y1,z1,x2,y2,z2] from the detected plot's geometry (dev mode), or null. */
	private static int[] codespaceBox(Config cfg) {
		try {
			if (CodeClient.location instanceof Plot plot && plot.getX() != null && plot.getZ() != null) {
				Plot.Size sz = plot.assumeSize();
				int x = plot.getX(), z = plot.getZ(), fy = plot.getFloorY();
				return new int[]{x - sz.codeWidth, fy, z, x, fy + 45, z + sz.codeLength}; // ~9 layers default height
			}
		} catch (Throwable ignored) {}
		return null;
	}

	/** ONE straight axis to fly along (never a diagonal): north/south first (lines run along Z), then up/down
	 *  (layers stack in Y), and east/west only last (the codespace is thin in X). */
	private static String directionTo(double px, double py, double pz, int x, int y, int z) {
		double dx = x - px, dy = y - py, dz = z - pz;
		if (dz > 8) return "+Z (south)";
		if (dz < -8) return "-Z (north)";
		if (dy > 8) return "up";
		if (dy < -8) return "down";
		if (dx > 8) return "+X (east)";
		if (dx < -8) return "-X (west)";
		return "a little further";
	}

	// ===================================================================== PUSH
	public static void push(Config cfg, int batch, boolean log, boolean underground, boolean compact, String sort, int gap, boolean fix) {
		begin();
		Log.begin(cfg, "PUSH " + cfg.activeProject + (batch > 0 ? " -batch " + batch : "")
				+ (underground ? " -underground" : "") + (compact ? " -compact" : "")
				+ " -sort " + sort + (compact ? "" : " -gap " + gap), log);
		Path dir = requireActive(cfg);
		if (dir == null) return;
		try {
			PlotInfo plot = PlotDetector.detect(cfg.locateCommand, DETECT_MS);
			String recorded = readMetaPlotId(dir);
			// Heads-up only (never blocks): you're pushing onto a plot other than the one this came from.
			if (plot != null && recorded != null && !recorded.equals(plot.id()))
				Chat.warn("Heads-up: '" + cfg.activeProject + "' came from plot [" + recorded + "] but you're on [" + plot.id() + " " + plot.name() + "]. Pushing anyway.");
			if (plot == null) Chat.warn("(Couldn't confirm which plot you're on. Proceeding anyway.)");
			String plotId = plot != null ? plot.id() : recorded;
			recordPlotLength(dir);

			JsonObject res = recompileFiles(cfg, dir);
			if (res == null) return;
			JsonArray files = res.getAsJsonArray("files");

			// Only push lines whose content hash differs from the last sync.
			Map<String, String> stored = loadHashes(dir);
			Map<String, String> tokenFiles = new HashMap<>(); // line token -> file name
			List<String> changed = new ArrayList<>();
			List<String> changedIds = new ArrayList<>();
			for (var el : files) {
				JsonObject f = el.getAsJsonObject();
				String file = f.get("file").getAsString(), hash = f.get("hash").getAsString();
				if (!hash.equals(stored.get(file))) {
					changed.add(f.get("line").getAsString());
					changedIds.add(f.get("id").getAsString());
					tokenFiles.put(f.get("line").getAsString(), file);
				}
			}
			if (changed.isEmpty()) {
				writeLastSync(dir, "push", plotId, res, Map.of(), null);
				Chat.good("Already in sync.");
				return;
			}

			try (CodeClientConn cc = CodeClientConn.connect(apiPort(cfg))) {
				if (!cc.auth(cfg)) { Chat.error(AUTH_FAIL); return; }
				if (!ensureCodeMode(cc)) return;
				List<String> safe = enforceLength(changed, fix); // -fix: over-64KB lines segment-place (1-1) instead of dropping
				Set<String> safeSet = new HashSet<>(safe);
				Set<String> dropped = new HashSet<>(); // length-skipped files stay pending, not "synced"
				for (String t : changed) if (!safeSet.contains(t)) dropped.add(tokenFiles.get(t));
				Map<String, String> status = new HashMap<>();
				for (String t : changed) status.put(tokenFiles.get(t), safeSet.contains(t) ? "placed" : "skipped_length");
				if (safe.isEmpty()) {
					writeLastSync(dir, "push", plotId, res, status, null);
					Chat.warn("No lines left to push after the length check.");
					return;
				}
				List<String> failedNames = new ArrayList<>();
				boolean ok = placeBatched(cc, safe, batch, "Push", underground, compact, sort, gap, failedNames);
				if (!ok) for (String t : safe) status.put(tokenFiles.get(t), "failed");
				applyFailedStatus(failedNames, safe, tokenFiles, status);
				writeLastSync(dir, "push", plotId, res, status, ok ? null : "place stopped early; some lines may have been placed");
				if (ok) {
					saveHashes(dir, hashesAfterPlace(files, stored, dropped));
					Chat.good("Pushed " + safe.size() + " line(s).", "Changed: " + String.join(", ", changedIds));
				} else {
					stoppedEarly("Push", log);
				}
			}
		} catch (Exception e) {
			reportFail("Push", e, true);
		}
	}

	// =================================================================== DEPLOY
	public static void deploy(Config cfg, int batch, boolean log, boolean underground, boolean compact, String sort, int gap, boolean fix) {
		begin();
		Log.begin(cfg, "DEPLOY " + cfg.activeProject + (batch > 0 ? " -batch " + batch : "")
				+ (underground ? " -underground" : "") + (compact ? " -compact" : "")
				+ " -sort " + sort + (compact ? "" : " -gap " + gap), log);
		Path dir = requireActive(cfg);
		if (dir == null) return;
		try {
			JsonObject res = recompileFiles(cfg, dir);
			if (res == null) return;
			JsonArray files = res.getAsJsonArray("files");
			Map<String, String> tokenFiles = new HashMap<>(); // line token -> file name
			List<String> lines = new ArrayList<>();
			for (var el : files) {
				JsonObject f = el.getAsJsonObject();
				lines.add(f.get("line").getAsString());
				tokenFiles.put(f.get("line").getAsString(), f.get("file").getAsString());
			}

			try (CodeClientConn cc = CodeClientConn.connect(apiPort(cfg))) {
				if (!cc.auth(cfg)) { Chat.error(AUTH_FAIL); return; }
				if (!ensureCodeMode(cc)) return;
				List<String> safe = enforceLength(lines, fix); // -fix: over-64KB lines segment-place (1-1) instead of dropping
				if (safe.isEmpty()) {
					Chat.error("Every line is too long for this plot.", "Nothing placed; the plot is untouched. Deploy cancelled.");
					return;
				}
				Set<String> safeSet = new HashSet<>(safe);
				Set<String> dropped = new HashSet<>(); // length-skipped files stay pending, not "synced"
				for (String t : lines) if (!safeSet.contains(t)) dropped.add(tokenFiles.get(t));
				boolean owned = cc.clear();
				if (!owned) {
					// Not the plot owner: /plot clear is owner-only. Clear the codespace by reading every line
					// (that's our backup of the old code) and BREAKING it - which a builder is allowed to do.
					Chat.warn("You don't own this plot, so /plot clear isn't available. Backing up the current code, then clearing it line by line...");
					List<String> backup = cc.clearByScan(null);
					if (backup != null && !backup.isEmpty()) {
						snapshot(cfg, cfg.activeProject.isBlank() ? "plot" : cfg.activeProject, backup, "predeploy");
						Chat.good("Backed up " + backup.size() + " old line(s) to .df-backups (restore with /df backup load), then cleared by breaking.");
					} else {
						Chat.info("Nothing to clear; placing onto an empty codespace.");
					}
				}
				Log.step("cleared codespace; placing " + safe.size() + " lines");
				// IMPORTANT: create the underground AFTER the clear - clearing the codespace
				// resets/removes the underground, so creating it first would just delete it.
				if (underground) {
					Chat.info("Creating underground codespace (/p codespace underground create)...");
					createUndergroundCodespace();
					forceUnderground();
					Log.step("-underground: created codespace after clear; placing from the bottom (floor y=5)");
				}
				clearInventory(); // start with an empty inventory so any lines that fail to place collect cleanly
				List<String> overflow = new ArrayList<>();
				List<String> failedNames = new ArrayList<>();
				boolean ok = placeLayout(cc, safe, batch, "Deploy", underground, compact, sort, gap, overflow, failedNames);
				Set<String> overflowFiles = new HashSet<>();
				for (String t : overflow) overflowFiles.add(tokenFiles.get(t));
				Map<String, String> status = new HashMap<>();
				for (String t : lines) status.put(tokenFiles.get(t), ok ? "placed" : "failed");
				for (String t : overflow) status.put(tokenFiles.get(t), "overflow");
				for (String t : lines) if (!safeSet.contains(t)) status.put(tokenFiles.get(t), "skipped_length");
				applyFailedStatus(failedNames, safe, tokenFiles, status); // per-line rejections beat the batch "done"
				writeLastSync(dir, "deploy", readMetaPlotId(dir), res, status,
						ok ? null : "deploy stopped early; the plot may be partially placed");
				if (ok) {
					Set<String> unplaced = new HashSet<>(dropped);
					unplaced.addAll(overflowFiles);
					saveHashes(dir, hashesAfterPlace(files, loadHashes(dir), unplaced));
					int placed = safe.size() - overflow.size();
					if (dropped.isEmpty() && overflowFiles.isEmpty()) {
						Chat.good("Deployed " + placed + " lines.");
					} else {
						Chat.good("Deployed " + placed + " lines.");
						// overflow = ran out of codespace (fill can retry, esp. -underground);
						// dropped = too long for the plot's limit (fill won't help - split them).
						if (!overflowFiles.isEmpty()) {
							String fillCmd = "/df fill" + (underground ? " -underground" : "");
							Chat.warn(overflowFiles.size() + " line(s) didn't fit the codespace (now in your inventory).",
									Chat.runCmd("[" + fillCmd + "]", fillCmd, "retry the lines that didn't place"));
						}
						if (!dropped.isEmpty())
							Chat.warn(dropped.size() + " line(s) are too long for this plot and were skipped - split them into smaller functions.");
					}
				} else {
					stoppedEarly("Deploy", log);
				}
			}
		} catch (Exception e) {
			reportFail("Deploy", e, true);
		}
	}

	// ================================================================ SCHEMATIC
	/**
	 * /df schematic save: read a build's exact state within the given box and have the codec generate a
	 * DiamondFire build-loader project (.py) under schemRoot/<name>. Running @load in-game rebuilds it.
	 * Container contents are captured unless -nochest. Dev/build mode on your own plot only.
	 */
	public static void schematicSave(Config cfg, String name,
			boolean nomove, int[] regionOverride, int sizeLen, boolean noChest) {
		begin();
		Log.begin(cfg, "SCHEM SAVE " + name + (nomove ? " -nomove" : "") + (noChest ? " -nochest" : "")
				+ (sizeLen > 0 ? " -size " + sizeLen : ""), true);
		try {
			if (cfg.schemRoot == null || cfg.schemRoot.isBlank()) { Chat.error("Set a schem folder first: /df schematic path <dir>."); return; }
			if (cfg.codecDir == null || cfg.codecDir.isBlank()) { Chat.error("Set the codec dir first: /df codec <dir>."); return; }
			if (name == null || name.isBlank()) { Chat.error("Give a name: /df schematic save <name>."); return; }
			// Own-plot only: must be in dev or build mode on the plot (never capturing someone else's play-mode plot).
			if (!(CodeClient.location instanceof dev.dfonline.codeclient.location.Dev
					|| CodeClient.location instanceof dev.dfonline.codeclient.location.Build)) {
				Chat.error("Schematic save works in dev or build mode on your own plot. Enter /dev or /build first.");
				return;
			}
			// The build box comes from the required inline -region coords.
			int[] box = regionOverride;
			if (box == null) {
				Chat.error("Give the build's box: /df schematic save <name> -region x1 y1 z1 x2 y2 z2.");
				return;
			}
			Path schemDir = cfg.schemDir(name);
			Path captureDir = schemDir.resolve(".capture");
			Files.createDirectories(captureDir);
			// Re-capturing over an old save: wipe the old capture files first, so a capture that dies
			// mid-write can never leave a plausible-looking MIX of new and stale files for genschem.
			for (String stale : new String[]{"meta.json", "palette.json", "blocks.gz", "entities.json"})
				Files.deleteIfExists(captureDir.resolve(stale));

			try (CodeClientConn cc = CodeClientConn.connect(apiPort(cfg))) {
				if (!cc.auth(cfg)) { Chat.error(AUTH_FAIL); return; }
				String status;
				dev.dfonline.codeclient.action.impl.CaptureRegion.REGION = box;
				dev.dfonline.codeclient.action.impl.CaptureRegion.NO_MOVE = nomove;
				dev.dfonline.codeclient.action.impl.CaptureRegion.NO_CHEST = noChest;
				dev.dfonline.codeclient.action.impl.CaptureRegion.OUT_DIR = captureDir;
				if (nomove)
					Chat.info("-nomove capture: fly slowly through the whole build; finishes once you've swept it all.");
				if (noChest)
					Chat.info("-nochest: shape only (empty containers), much faster.");
				try {
					status = cc.capture(null);
				} finally {
					dev.dfonline.codeclient.action.impl.CaptureRegion.REGION = null;
					dev.dfonline.codeclient.action.impl.CaptureRegion.NO_MOVE = false;
					dev.dfonline.codeclient.action.impl.CaptureRegion.NO_CHEST = false;
					dev.dfonline.codeclient.action.impl.CaptureRegion.OUT_DIR = null;
				}
				if (status == null) { Chat.error("Capture timed out.", "No response. Make sure you're at the build, then try again."); return; }
				if (status.startsWith("error")) { Chat.error("Capture failed: " + status); return; }
				Chat.good("Captured region (" + status + "). Generating loader code...");

				JsonObject req = new JsonObject();
				req.addProperty("captureDir", captureDir.toString());
				req.addProperty("outDir", schemDir.toString());
				req.addProperty("name", name);
				// Target plot size for the generated code lines: the -size flag if given, else the plot
				// we're standing on, else massive (300 - the loader should always fit a massive).
				int plen = sizeLen > 0 ? sizeLen : detectedPlotLength();
				if (plen <= 0) plen = 300;
				req.addProperty("plotLength", plen);
				JsonObject res = new Codec(cfg).run("genschem", GSON.toJson(req));
				if (res.has("warnings"))
					for (var el : res.getAsJsonArray("warnings")) Chat.warn("Codec: " + el.getAsString());
				int files = res.has("files") ? res.get("files").getAsInt() : 0;
				int boxes = res.has("boxes") ? res.get("boxes").getAsInt() : 0;
				int fixtures = res.has("fixtures") ? res.get("fixtures").getAsInt() : 0;
				int codecVersion = res.has("codec_version") ? res.get("codec_version").getAsInt() : 0;
				writeMeta(schemDir, name, null, files, codecVersion, plen); // .df-vibe.json marker (plot length for check/validate/load)

				Chat.good("Saved build '" + name + "': " + boxes + " block-box(es), " + fixtures + " fixture func(s), "
								+ files + " code line(s) in " + cfg.schemRoot + ".",
						Chat.runCmd("[/df schematic load " + name + "]", "/df schematic load " + name,
								"deploy this build's loader to the current plot"));
			}
		} catch (Exception e) {
			reportFail("Schematic save", e, true);
		}
	}

	/**
	 * /df schematic load: deploy a saved build's loader project (schemRoot/<name>) to the current plot, reusing
	 * the place pipeline. Additive by default (swap-by-header, won't wipe the plot); -clear clears first.
	 * After loading, stand on the intended origin and type @load to materialize the build.
	 */
	public static void schematicLoad(Config cfg, String name, boolean clear) {
		begin();
		Log.begin(cfg, "SCHEM LOAD " + name + (clear ? " -clear" : ""), true);
		try {
			if (cfg.schemRoot == null || cfg.schemRoot.isBlank()) { Chat.error("Set a schem folder first: /df schematic path <dir>."); return; }
			if (name == null || name.isBlank()) { Chat.error("Give a name: /df schematic load <name>."); return; }
			Path dir = cfg.schemDir(name);
			if (!Files.isDirectory(dir)) { Chat.error("No saved build '" + name + "' in " + cfg.schemRoot + " (run /df schematic save first)."); return; }

			JsonObject res = recompileFiles(cfg, dir);
			if (res == null) return;
			JsonArray files = res.getAsJsonArray("files");
			List<String> lines = new ArrayList<>();
			for (var el : files) lines.add(el.getAsJsonObject().get("line").getAsString());

			try (CodeClientConn cc = CodeClientConn.connect(apiPort(cfg))) {
				if (!cc.auth(cfg)) { Chat.error(AUTH_FAIL); return; }
				if (!ensureCodeMode(cc)) return;
				List<String> safe = enforceLength(lines);
				if (safe.isEmpty()) { Chat.error("Every loader line is too long for this plot.", "Nothing was placed."); return; }
				if (clear) {
					boolean owned = cc.clear();
					if (!owned) {
						List<String> backup = cc.clearByScan(null);
						if (backup != null && !backup.isEmpty()) snapshot(cfg, "schem-" + name, backup, "preschem");
					}
				}
				clearInventory();
				List<String> failedNames = new ArrayList<>();
				boolean ok = placeBatched(cc, safe, 0, "Schematic", false, false, "alpha", 0, failedNames);
				applyFailedStatus(failedNames, null, null, null);
				if (ok) {
					Chat.good("Loaded build '" + name + "' (" + safe.size() + " line(s)). Stand where you want the build's "
							+ "NW-bottom corner and type @load in play mode.");
				} else {
					stoppedEarly("Schematic load", true);
				}
			}
		} catch (Exception e) {
			reportFail("Schematic load", e, true);
		}
	}

	// ===================================================================== FILL
	/**
	 * Place ONLY the lines that are missing from the plot - no clear, no touching what's already
	 * there. Scans the plot, identifies each placed line, and compares to the local files by their
	 * stable identity (header id); any local line whose id isn't on the plot gets placed. This is
	 * how you recover the handful of lines a big deploy's placer failed to seat (placement on DF is
	 * flaky): run it once or twice after a deploy and it converges to 100%, instead of re-clearing
	 * and re-rolling the dice on all 388 lines.
	 */
	public static void fill(Config cfg, int batch, boolean log, boolean underground, boolean compact, String sort, int gap, boolean fix) {
		begin();
		Log.begin(cfg, "FILL " + cfg.activeProject + (underground ? " -underground" : "")
				+ " -sort " + sort + (compact ? " -compact" : " -gap " + gap), log);
		Path dir = requireActive(cfg);
		if (dir == null) return;
		try {
			PlotInfo plot = PlotDetector.detect(cfg.locateCommand, DETECT_MS);
			String recorded = readMetaPlotId(dir);
			// Heads-up only (never blocks): filling a plot other than the one this came from.
			if (plot != null && recorded != null && !recorded.equals(plot.id()))
				Chat.warn("Heads-up: '" + cfg.activeProject + "' came from plot [" + recorded + "] but you're on [" + plot.id() + " " + plot.name() + "]. Filling anyway.");

			JsonObject res = recompileFiles(cfg, dir);
			if (res == null) return;
			JsonArray files = res.getAsJsonArray("files"); // local lines: {file, id, line, hash}

			try (CodeClientConn cc = CodeClientConn.connect(apiPort(cfg))) {
				if (!cc.auth(cfg)) { Chat.error(AUTH_FAIL); return; }
				if (!ensureCodeMode(cc)) return;

				// If the code lives underground, force that floor (y=5) BEFORE scanning - the scan
				// starts at getFloorY(), so without this it would only cover the upper codespace
				// (y>=50), miss every underground line, and think the whole project is missing.
				if (underground) { forceUnderground(); Log.step("-underground: scanning + filling the bottom codespace (floor y=5)"); }

				// What's actually on the plot right now? Scan, then identify each line.
				List<String> plotLines = cc.scan();
				Set<String> present = new HashSet<>();
				if (!plotLines.isEmpty()) {
					JsonObject idReq = new JsonObject();
					JsonArray arr = new JsonArray();
					plotLines.forEach(arr::add);
					idReq.add("lines", arr);
					JsonObject idRes = new Codec(cfg).run("identify", GSON.toJson(idReq));
					if (idRes.has("files")) {
						for (var el : idRes.getAsJsonArray("files")) {
							JsonObject f = el.getAsJsonObject();
							if (f.has("id") && !f.get("id").isJsonNull()) present.add(f.get("id").getAsString());
						}
					}
				}

				// Local lines whose identity isn't on the plot = the gaps to fill.
				String plotId = plot != null ? plot.id() : recorded;
				List<String> missing = new ArrayList<>();
				List<String> missingIds = new ArrayList<>();
				List<String> missingFiles = new ArrayList<>();
				for (var el : files) {
					JsonObject f = el.getAsJsonObject();
					if (!present.contains(f.get("id").getAsString())) {
						missing.add(f.get("line").getAsString());
						missingIds.add(f.get("id").getAsString());
						missingFiles.add(f.get("file").getAsString());
					}
				}
				Log.step("plot has " + present.size() + " line(s); local has " + files.size() + "; " + missing.size() + " missing");
				if (missing.isEmpty()) {
					writeLastSync(dir, "fill", plotId, res, Map.of(), null);
					Chat.good("Plot already has all " + files.size() + " lines.");
					return;
				}

				List<String> safe = enforceLength(missing, fix); // -fix: over-64KB lines segment-place (1-1) instead of dropping
				Set<String> safeSet = new HashSet<>(safe);
				Map<String, String> status = new HashMap<>();
				for (int i = 0; i < missing.size(); i++)
					status.put(missingFiles.get(i), safeSet.contains(missing.get(i)) ? "placed" : "skipped_length");
				if (safe.isEmpty()) {
					writeLastSync(dir, "fill", plotId, res, status, null);
					Chat.warn("No fillable lines left after the length check.");
					return;
				}
				Chat.info("Filling " + safe.size() + " line(s) missing from the plot...");
				List<String> failedNames = new ArrayList<>();
				Map<String, String> fillTokenFiles = new HashMap<>();
				for (int i = 0; i < missing.size(); i++) fillTokenFiles.put(missing.get(i), missingFiles.get(i));
				boolean ok = placeBatched(cc, safe, batch, "Fill", underground, compact, sort, gap, failedNames);
				if (!ok) for (int i = 0; i < missing.size(); i++)
					if (safeSet.contains(missing.get(i))) status.put(missingFiles.get(i), "failed");
				applyFailedStatus(failedNames, safe, fillTokenFiles, status);
				writeLastSync(dir, "fill", plotId, res, status, ok ? null : "place stopped early; some lines may have been placed");
				if (ok) {
					Chat.good("Filled " + safe.size() + " line(s).", "Added: " + String.join(", ", missingIds));
				} else {
					stoppedEarly("Fill", log);
				}
			}
		} catch (Exception e) {
			reportFail("Fill", e, true);
		}
	}

	// ===================================================================== DIFF
	/** Preview which lines changed since last sync (read-only; no game contact). */
	public static void diff(Config cfg) {
		Path dir = requireActive(cfg);
		if (dir == null) return;
		try {
			JsonObject res = recompileFiles(cfg, dir);
			if (res == null) return;
			JsonArray files = res.getAsJsonArray("files");
			Map<String, String> stored = loadHashes(dir);
			List<String> changed = new ArrayList<>(), added = new ArrayList<>();
			for (var el : files) {
				JsonObject f = el.getAsJsonObject();
				String file = f.get("file").getAsString(), hash = f.get("hash").getAsString();
				if (!stored.containsKey(file)) added.add(f.get("id").getAsString());
				else if (!hash.equals(stored.get(file))) changed.add(f.get("id").getAsString());
			}
			if (changed.isEmpty() && added.isEmpty()) { Chat.good("No changes since last sync."); return; }
			if (!changed.isEmpty()) Chat.info("Changed (" + changed.size() + "): " + String.join(", ", changed));
			if (!added.isEmpty()) Chat.info("New (" + added.size() + "): " + String.join(", ", added));
			Chat.info("Ready to send.", Chat.runCmd("[/df push]", "/df push", "place the changed lines"));
		} catch (Exception e) {
			Chat.error("Diff failed: " + e.getMessage());
		}
	}

	// =================================================================== BACKUP
	public static void backup(Config cfg) {
		begin();
		Log.begin(cfg, "BACKUP", false);
		if (!cfg.ready()) { Chat.error("Set workspace + codec first."); return; }
		String label = cfg.activeProject.isBlank() ? "plot" : cfg.activeProject;
		try (CodeClientConn cc = CodeClientConn.connect(apiPort(cfg))) {
			if (!cc.auth(cfg)) { Chat.error(AUTH_FAIL); return; }
			if (!ensureCodeMode(cc)) return;
			List<String> lines = cc.scan();
			if (lines.isEmpty()) { Chat.warn("Scan found 0 lines, nothing to back up."); return; }
			snapshot(cfg, label, lines, "manual");
			Chat.good("Backed up " + lines.size() + " line(s).",
					Chat.openFile("[folder]", Path.of(cfg.plotsRoot).resolve(".df-backups").toString()),
					Chat.runCmd("[list]", "/df backup list", "list snapshots"));
		} catch (Exception e) {
			reportFail("Backup", e, false);
		}
	}

	/** List recent backup files (filesystem only). Filtered to the active project if set. */
	public static void backupList(Config cfg) {
		if (cfg.plotsRoot.isBlank()) { Chat.error("Set workspace first (/df workspace)."); return; }
		Path bdir = Path.of(cfg.plotsRoot).resolve(".df-backups");
		if (!Files.isDirectory(bdir)) { Chat.info("No backups yet. Make one with /df backup."); return; }
		String prefix = cfg.activeProject.isBlank() ? "" : cfg.activeProject + "-";
		try (Stream<Path> s = Files.list(bdir)) {
			List<String> names = s.map(p -> p.getFileName().toString())
					.filter(n -> n.endsWith(".txt") && (prefix.isEmpty() || n.startsWith(prefix)))
					.sorted(Comparator.reverseOrder()).limit(12).toList();
			String scope = prefix.isEmpty() ? "" : " for '" + cfg.activeProject + "'";
			if (names.isEmpty()) { Chat.info("No backups" + scope + " yet."); return; }
			Chat.info("Recent backups" + scope + " (newest first) - click to restore:");
			for (String n : names)
				Chat.raw(net.minecraft.text.Text.literal("  ")
						.append(Chat.runCmd(n, "/df backup load " + n, "Restore this snapshot (clears the plot first)")));
		} catch (Exception e) {
			Chat.error("List failed: " + e.getMessage());
		}
	}

	/** Restore a backup: CLEAR the plot, then place all of the backup's lines. */
	public static void backupLoad(Config cfg, String file) {
		begin();
		Log.begin(cfg, "RESTORE " + file, false);
		if (!cfg.ready()) { Chat.error("Set workspace + codec first."); return; }
		Path f = Path.of(cfg.plotsRoot).resolve(".df-backups").resolve(file);
		if (!Files.isRegularFile(f)) { Chat.error("Backup not found: " + file + " (see /df backup list)"); return; }
		try {
			List<String> lines = new ArrayList<>();
			for (String ln : Files.readAllLines(f, StandardCharsets.UTF_8)) {
				String t = ln.trim();
				if (!t.isEmpty()) lines.add(t);
			}
			if (lines.isEmpty()) { Chat.warn("Backup '" + file + "' is empty."); return; }
			try (CodeClientConn cc = CodeClientConn.connect(apiPort(cfg))) {
				if (!cc.auth(cfg)) { Chat.error(AUTH_FAIL); return; }
				if (!ensureCodeMode(cc)) return;
				List<String> safe = enforceLength(lines, true); // layout path: over-64KB lines segment-place (1-1)
				cc.clear();
				List<String> failedNames = new ArrayList<>();
				boolean ok = placeLayout(cc, safe, 0, "Restore", false, false, "alpha", 0, null, failedNames);
				applyFailedStatus(failedNames, null, null, null);
				if (ok)
					Chat.good("Restored " + safe.size() + " line(s) from backup '" + file + "'.");
				else
					stoppedEarly("Restore", true);
			}
		} catch (Exception e) {
			reportFail("Restore", e, true);
		}
	}

	// ================================================================== helpers
	private static Path requireActive(Config cfg) {
		if (!cfg.ready()) { Chat.error("Set workspace + codec first."); return null; }
		Path dir = cfg.activeDir();
		if (dir == null) { Chat.error("No active project. /df use <name> or /df pull <name>."); return null; }
		if (!Files.isDirectory(dir)) { Chat.error("Project folder not found: " + dir); return null; }
		return dir;
	}

	/** Read all .py files and recompile. Returns the codec result {files,errors,codec_version}, or null if no .py. */
	private static JsonObject recompileFiles(Config cfg, Path dir) throws Exception {
		JsonArray fileArr = new JsonArray();
		try (Stream<Path> s = Files.list(dir)) {
			for (Path p : s.filter(p -> p.getFileName().toString().endsWith(".py")).toList()) {
				JsonObject f = new JsonObject();
				f.addProperty("file", p.getFileName().toString());
				f.addProperty("source", Files.readString(p, StandardCharsets.UTF_8));
				fileArr.add(f);
			}
		}
		if (fileArr.size() == 0) { Chat.warn("No .py files in '" + cfg.activeProject + "'. Nothing to do."); return null; }
		JsonObject req = new JsonObject();
		req.add("files", fileArr);
		JsonObject res = new Codec(cfg).run("recompile", GSON.toJson(req));
		// Every caller iterates res["files"] - a malformed codec response must fail HERE with a real
		// message, not as a NullPointerException halfway into a place.
		if (!res.has("files")) { Chat.error("Codec returned an invalid recompile response (no 'files'). Check the codec manually."); return null; }
		if (res.has("errors")) {
			for (var el : res.getAsJsonArray("errors")) {
				JsonObject e = el.getAsJsonObject();
				Chat.error("Won't compile (skipped): " + e.get("file").getAsString() + " - " + e.get("error").getAsString());
			}
		}
		// Non-blocking: validation found lines that compile but are broken DF (bad var scope,
		// 28-item chest, etc.). We still place them - just flag so they're not a silent bug.
		if (res.has("issues") && res.getAsJsonArray("issues").size() > 0) {
			JsonArray iss = res.getAsJsonArray("issues");
			Chat.warn(iss.size() + " line(s) have validation errors (placing anyway - run 'dfpy.py validate' for details):");
			int shown = 0;
			for (var el : iss) {
				if (shown++ >= 5) { Chat.warn("  ...and " + (iss.size() - 5) + " more."); break; }
				JsonObject e = el.getAsJsonObject();
				Chat.warn("  " + e.get("file").getAsString() + ": " + e.get("code").getAsString()
						+ " - " + e.get("msg").getAsString());
			}
		}
		return res;
	}

	/**
	 * /df pull overwrites every .py - if any are diff-dirty (unpushed edits, same check as diff()),
	 * copy them into .df-backups/<project>-<ts>-auto-pre-pull/ first so a pull can't silently
	 * destroy local work. Best-effort: a failed check warns and lets the pull proceed.
	 */
	private static void backupDirtyFiles(Config cfg, Path dir, String project) {
		try {
			if (!Files.isDirectory(dir)) return;
			boolean hasPy;
			try (Stream<Path> s = Files.list(dir)) {
				hasPy = s.anyMatch(p -> p.getFileName().toString().endsWith(".py"));
			}
			if (!hasPy) return;
			JsonObject res = recompileFiles(cfg, dir);
			if (res == null) return;
			Map<String, String> stored = loadHashes(dir);
			List<String> dirty = new ArrayList<>();
			for (var el : res.getAsJsonArray("files")) {
				JsonObject f = el.getAsJsonObject();
				String file = f.get("file").getAsString();
				if (!f.get("hash").getAsString().equals(stored.get(file))) dirty.add(file);
			}
			if (res.has("errors")) { // non-compiling files can't be hash-checked; treat as dirty
				for (var el : res.getAsJsonArray("errors")) dirty.add(el.getAsJsonObject().get("file").getAsString());
			}
			if (dirty.isEmpty()) return;
			Path backups = Path.of(cfg.plotsRoot).resolve(".df-backups")
					.resolve(project + "-" + LocalDateTime.now().format(TS) + "-auto-pre-pull");
			Files.createDirectories(backups);
			for (String f : dirty) Files.copy(dir.resolve(f), backups.resolve(f));
			Chat.warn(dirty.size() + " file(s) have unpushed local edits this pull will overwrite: " + String.join(", ", dirty));
			Chat.warn("Copies saved to " + backups);
		} catch (Exception e) {
			Chat.warn("Couldn't check for unpushed local edits (" + e.getMessage() + ") - pulling anyway.");
		}
	}

	private static boolean ensureCodeMode(CodeClientConn cc) throws InterruptedException {
		String mode = cc.mode();
		String m = mode == null ? "" : mode.trim().toLowerCase();
		if (m.equals("code")) return true;
		if (m.equals("spawn") || m.equals("play") || m.equals("build")) {
			Chat.error("You're in '" + mode + "' mode. Switch to dev mode with /dev, then try again.");
			return false;
		}
		// No reply / "unauthed" / anything unrecognized: don't run a destructive op blind.
		Chat.error("Couldn't confirm dev/code mode (API said " + (mode == null ? "nothing" : "'" + mode + "'")
				+ "). Make sure you're on the plot in dev mode, then try again.");
		return false;
	}

	/**
	 * The plot's max code-line length (codeLength), or -1 if it isn't known. Read IN-PROCESS
	 * from the bundled engine's current location - we deliberately do NOT use the websocket
	 * "size" command, which triggers plot-size detection (chat spam + can fail) mid-sync. If
	 * the size isn't already known, we just skip the guard.
	 */
	private static int plotCodeLength() {
		int known = detectedPlotLength();
		if (known > 0) return known;
		// Size not detected at deploy time: fall back to 300, the largest code-line length any plot
		// allows. This still catches lines too long for ANY plot (the deterministic "Invalid template
		// placement" failures) instead of letting them error in a retry-loop. (On a SMALLER plot whose
		// real limit is <300 this won't catch mid-length lines, but >300 lines are caught everywhere.)
		return 300;
	}

	/** The live plot's detected code length, or -1 if the engine hasn't detected the size yet. */
	private static int detectedPlotLength() {
		try {
			if (CodeClient.location instanceof Plot plot && plot.getSize() != null) {
				return plot.getSize().codeLength;
			}
		} catch (Throwable t) {
			// location/size unavailable
		}
		return -1;
	}

	/**
	 * Force placement into the underground codespace (floor y=5, the tall space) for this op.
	 * CodeClient auto-detects underground, but the detection samples the block under the PLAYER,
	 * and the player is flown around during a place, so it often mis-detects mid-deploy. Setting
	 * the flag in-process makes it explicit. Requires the underground codespace to already exist
	 * (/p codespace underground create); placing there without it would target empty air.
	 */
	private static void forceUnderground() {
		try {
			if (CodeClient.location instanceof Plot plot) {
				plot.setHasUnderground(true);
			}
		} catch (Throwable t) {
			// best-effort: if engine state isn't readable, just place at the normal floor
		}
	}

	/**
	 * Run {@code /p codespace underground create} in-game and give DiamondFire a moment to build
	 * the space, so {@code -underground} has somewhere to place. Sent on the game thread (the only
	 * place chat commands are valid). Idempotent — DF just ignores it if the space already exists.
	 */
	private static void createUndergroundCodespace() throws InterruptedException {
		CodeClient.MC.execute(() -> {
			if (CodeClient.MC.player != null && CodeClient.MC.player.networkHandler != null) {
				CodeClient.MC.player.networkHandler.sendChatCommand("p codespace underground create");
			}
		});
		Thread.sleep(2500); // let the server generate the underground area before we clear + place
	}

	/**
	 * Clear the player's inventory (/clear) on the game thread before a deploy, so any code lines that
	 * fail to place - which the placer hands back to the inventory - aren't buried among other items.
	 */
	private static void clearInventory() throws InterruptedException {
		CodeClient.MC.execute(() -> {
			if (CodeClient.MC.player != null && CodeClient.MC.player.networkHandler != null) {
				CodeClient.MC.player.networkHandler.sendChatCommand("clear");
			}
		});
		Thread.sleep(300); // let the server apply the clear before placement begins
	}

	/**
	 * Drop (with a warning) any line too long to fit this plot, so placement can't run off the
	 * plot edge and break. Returns the lines that are safe to place. If the plot size isn't
	 * known, returns the input unchanged (no guard).
	 */
	private static List<String> enforceLength(List<String> lines) {
		return enforceLength(lines, false);
	}

	/**
	 * @param splitOversize true when the caller places via {@link #placeLayout} (absolute positions),
	 *        which SEGMENT-PLACES over-64KB tokens (see {@link #splitToken}) - so keep them. The
	 *        swap/batched path can't split (it picks positions client-side), so it still drops them.
	 */
	private static List<String> enforceLength(List<String> lines, boolean splitOversize) {
		int limit = plotCodeLength();
		List<String> ok = new ArrayList<>();
		List<String> droppedLen = new ArrayList<>();
		List<String> droppedBig = new ArrayList<>();
		for (String t : lines) {
			// Hard Minecraft limit (applies even when the plot size is unknown): a code-template item carries
			// its data in a single NBT string ({"author":...,"code":"<token>"} ~ token + 75 chars), and MC's
			// packet encoder writes NBT strings with writeUTF, which caps a single string at 65535 BYTES. A
			// bigger one throws an EncoderException on the network thread and DISCONNECTS the client the instant
			// we try to place it (the set_creative_mode_slot crash). The LAYOUT path segment-places these
			// (identical code, delivered in parts); the batched path must still drop them before they're sent.
			if (t.length() > MAX_TEMPLATE_TOKEN && !splitOversize) { droppedBig.add(Templates.name(t)); continue; }
			if (limit > 0) {
				int len = Templates.length(t);
				if (len > limit) { droppedLen.add(Templates.name(t) + " (" + len + " blocks)"); continue; }
			}
			ok.add(t);
		}
		if (!droppedLen.isEmpty()) {
			Chat.warn(droppedLen.size() + " line(s) exceed this plot's " + limit + "-block code-line limit and were skipped.");
			Chat.warn("Split each into smaller functions: " + String.join(", ", droppedLen));
		}
		if (!droppedBig.isEmpty()) {
			Chat.warn(droppedBig.size() + " line(s) are too large for one template (over 64 KB) and were skipped here: "
					+ String.join(", ", droppedBig));
			Chat.warn("Re-run with -fix to place them in segments (identical code).");
		}
		return ok;
	}

	/** Pull the failed-line names out of a placer's "... done failed a;b;c" reply (empty if none). */
	private static void collectFailed(String result, List<String> out) {
		if (result == null || out == null) return;
		int i = result.indexOf(" failed ");
		if (i < 0) return;
		for (String n : result.substring(i + 8).split(";")) if (!n.isBlank()) out.add(n.trim());
	}

	/**
	 * Mark the files behind per-line placement failures as "failed" in the status map (instead of the
	 * old behaviour: the batch said "done" so everything was recorded "placed" - even lines DiamondFire
	 * silently rejected, which is how a missing line could masquerade as synced). Names come from the
	 * placer as {@link Templates#name} strings; continuation segments of a split line may not map to a
	 * file and are shown raw.
	 */
	private static void applyFailedStatus(List<String> failedNames, java.util.Collection<String> tokens,
			Map<String, String> tokenFiles, Map<String, String> status) {
		if (failedNames == null || failedNames.isEmpty()) return;
		Map<String, String> nameToFile = new HashMap<>();
		if (tokens != null && tokenFiles != null)
			for (String t : tokens) nameToFile.put(Templates.name(t), tokenFiles.get(t));
		List<String> shown = new ArrayList<>();
		for (String n : failedNames) {
			String f = nameToFile.get(n);
			if (f != null && status != null) status.put(f, "failed");
			shown.add(f != null ? f : n);
		}
		Chat.warn(shown.size() + " line(s) did NOT end up placed (DiamondFire rejected them or placement gave up): "
				+ String.join(", ", shown));
		Chat.warn("Each was handed back to your inventory (named). Place one by hand to see DiamondFire's own error, then /df fill.");
	}

	/** Build a {@link Layout} from the current plot's geometry, or null if it isn't known. */
	private static Layout layoutFor(boolean underground) {
		try {
			if (CodeClient.location instanceof Plot plot && plot.getX() != null && plot.getZ() != null) {
				int floorY = underground ? 5 : 50;
				Plot.Size sz = plot.assumeSize();
				return new Layout(plot.getX(), plot.getZ(), floorY, sz.codeWidth, sz.codeLength);
			}
		} catch (Throwable ignored) {
		}
		return null;
	}

	/**
	 * Deploy/restore placement: compute the WHOLE layout up front (one absolute position per line),
	 * then place it in chunks at those fixed positions. Because positions are global, chunking only
	 * paces the placer (anti-flood breathers) - it can NEVER make chunks overlap, which the old
	 * per-chunk "place swap" path did. Chunks default to 50 even when no -batch is given, so a plain
	 * /df deploy doesn't stall on one huge all-at-once place. Returns true only if every chunk
	 * reported "done". Falls back to {@link #placeBatched} if the plot geometry isn't known.
	 * overflowOut (nullable) receives the tokens that didn't fit the codespace.
	 */
	private static boolean placeLayout(CodeClientConn cc, List<String> lines, int batch, String what,
			boolean underground, boolean compact, String sort, int gap, List<String> overflowOut,
			List<String> failedOut) throws InterruptedException {
		Layout layout = layoutFor(underground);
		if (layout == null) {
			Log.step(what + ": plot geometry unknown - falling back to per-chunk swap placement");
			// safe even with over-64KB tokens: the placer splits them into segments client-side
			return placeBatched(cc, lines, batch, what, underground, compact, sort, gap, failedOut);
		}
		List<String> ordered = sortForPlacement(lines, sort, compact);
		List<String> overflow = (overflowOut != null) ? overflowOut : new ArrayList<>();
		List<Layout.Placement> places = layout.compute(ordered, compact, gap, overflow);
		// Segment-place over-64KB lines: the layout reserved the line's FULL physical length at (x,y,z),
		// so its segments go at z + <blocks placed so far> along that same line - contiguous, so DF reads
		// them back as ONE line byte-identical to the original (a 1-1 copy with no code modification).
		List<Layout.Placement> expanded = new ArrayList<>(places.size());
		int splitLines = 0;
		for (Layout.Placement p : places) {
			if (p.token().length() <= MAX_TEMPLATE_TOKEN) { expanded.add(p); continue; }
			List<String> segs = Templates.split(p.token());
			if (segs == null) {
				Chat.warn("Couldn't split over-64KB line " + Templates.name(p.token()) + " into segments - SKIPPED.");
				if (failedOut != null) failedOut.add(Templates.name(p.token()));
				continue;
			}
			splitLines++;
			int zOff = 0;
			for (String s : segs) {
				expanded.add(new Layout.Placement(p.x(), p.y(), p.z() + zOff, s));
				zOff += Math.max(0, Templates.length(s));
			}
			Log.step(what + ": segment-placing " + Templates.name(p.token()) + " as " + segs.size()
					+ " parts along one line @ " + p.x() + "," + p.y() + "," + p.z());
		}
		if (splitLines > 0)
			Chat.info(splitLines + " over-64KB line(s) will be placed in segments along one line - identical code, no changes.");
		places = expanded;
		if (!overflow.isEmpty())
			Chat.warn(overflow.size() + " line(s) didn't fit this plot's codespace - use -underground (more layers), "
					+ "a bigger plot, or split long lines. (placing the rest)");
		int n = places.size();
		if (n == 0) { Chat.warn("Nothing fit the codespace to place."); return overflow.isEmpty(); }
		int size = (batch > 0) ? Math.min(batch, n) : Math.min(50, n); // chunk even with no -batch
		int total = (n + size - 1) / size;
		Log.step(what + ": global layout, placing " + n + " line(s) in " + total + " chunk(s) of up to " + size
				+ " [layout=" + (compact ? "compact" : "organized, gap=" + gap) + ", sort=" + sort + "]"
				+ (overflow.isEmpty() ? "" : ", " + overflow.size() + " overflow"));
		Progress.start(what, n);
		try {
			int placed = 0;
			for (int i = 0, b = 1; i < n; i += size, b++) {
				if (CANCEL.get()) {
					Chat.warn(what + " cancelled after " + (b - 1) + "/" + total + " chunk(s) (" + placed + " lines).");
					Log.info("cancelled at chunk " + b);
					return false;
				}
				List<Layout.Placement> chunk = new ArrayList<>(places.subList(i, Math.min(i + size, n)));
				if (underground) forceUnderground();
				String result = cc.placeAt(chunk);
				collectFailed(result, failedOut);
				boolean ok = result != null && result.toLowerCase().contains("done");
				Log.info(what + " chunk " + b + "/" + total + " (" + chunk.size() + " lines) -> "
						+ (result == null ? "NO RESPONSE (closed: " + cc.closedReason() + ")" : result));
				if (!ok) {
					if (result == null) abortLivePlace();
					Chat.warn(what + " stopped at chunk " + b + "/" + total + ": "
							+ (result == null ? "no response - try a smaller -batch (e.g. -batch 25)." : result));
					return false;
				}
				placed += chunk.size();
				if (total > 1) Chat.info(what + " " + b + "/" + total + " ok  (" + placed + "/" + n + " lines)");
				if (i + size < n) Thread.sleep(1000); // anti-flood breather between chunks
			}
			return true;
		} finally {
			Progress.finish();
		}
	}

	/**
	 * Place lines in swap mode, optionally in batches of `batch` (0 = all in one place).
	 * Batching keeps each place small enough to finish before the response timeout and
	 * reports progress; cancellation (/df end) is honored between batches. Logs each
	 * batch's raw result. Returns true only if every batch reported "done".
	 *
	 * @param compact  MINIMAL layout (pack everything) vs the default ORGANIZED (banded) layout.
	 * @param sort     secondary order within each line type: "alpha" (A-Z, default), "size"
	 *                 (largest-first, so small lines cluster), "type"/"none" (file order).
	 * @param gap      ORGANIZED only: empty Y-layers between the event/function/process bands
	 *                 (0 = touching, N = N blank layers, -1 = don't separate bands).
	 */
	private static boolean placeBatched(CodeClientConn cc, List<String> lines, int batch, String what,
			boolean underground, boolean compact, String sort, int gap) throws InterruptedException {
		return placeBatched(cc, lines, batch, what, underground, compact, sort, gap, null);
	}

	private static boolean placeBatched(CodeClientConn cc, List<String> lines, int batch, String what,
			boolean underground, boolean compact, String sort, int gap, List<String> failedOut) throws InterruptedException {
		// Layout for this placement: -compact = MINIMAL (pack everything), else ORGANIZED (each type
		// in its own band of layers, one line per column, falling back to packing only if needed).
		PlaceTemplates.setLayout(compact ? PlaceTemplates.LayoutMode.MINIMAL : PlaceTemplates.LayoutMode.ORGANIZED);
		PlaceTemplates.setGap(gap);
		// Order the WHOLE set by line type, then the -sort key, before chunking - so even across
		// batches the codespace reads player events (lowest), then game, entity, functions, processes,
		// with the chosen secondary order inside each type. (Matched lines on /df push swap into their
		// existing spot regardless, so the ordering only shapes where NEW lines land.)
		List<String> ordered = sortForPlacement(lines, sort, compact);
		int n = ordered.size();
		int size = (batch > 0) ? Math.min(batch, n) : n;
		if (size <= 0) return true;
		int total = (n + size - 1) / size;
		Log.step(what + ": placing " + n + " line(s) in " + total + " batch(es) of up to " + size
				+ " [layout=" + (compact ? "minimal" : "organized, gap=" + gap) + ", sort=" + sort + "]");
		Progress.start(what, n);
		try {
			return placeSequential(cc, ordered, size, total, what, underground, failedOut);
		} finally {
			Progress.finish();
		}
	}

	/** The classic path: slice + place one batch at a time on this thread. */
	private static boolean placeSequential(CodeClientConn cc, List<String> lines, int size, int total,
			String what, boolean underground, List<String> failedOut) throws InterruptedException {
		int n = lines.size();
		int placed = 0;
		for (int i = 0, b = 1; i < n; i += size, b++) {
			if (CANCEL.get()) {
				Chat.warn(what + " cancelled after " + (b - 1) + "/" + total + " batch(es) (" + placed + " lines).");
				Log.info("cancelled at batch " + b);
				return false;
			}
			List<String> chunk = new ArrayList<>(lines.subList(i, Math.min(i + size, n)));
			if (!placeOneBatch(cc, chunk, what, b, total, underground, failedOut)) return false;
			placed += chunk.size();
			if (total > 1) Chat.info(what + " " + b + "/" + total + " ok  (" + placed + "/" + n + " lines)");
			// Breathe between batches: the place flies the player fast, and back-to-back batches can
			// trip DiamondFire's anti-cheat flood protection -> "Connection reset". A short idle gap
			// lets its counters decay before the next burst.
			if (i + size < n) Thread.sleep(1000);
		}
		return true;
	}

	/** Send + confirm one chunk; log the raw result and warn (returning false) if it didn't come back "done". */
	private static boolean placeOneBatch(CodeClientConn cc, List<String> chunk, String what, int b, int total,
			boolean underground, List<String> failedOut) throws InterruptedException {
		// Re-assert underground each batch: CC re-detects it every tick from the player's position,
		// and the player is flown around mid-place, so it can flip back otherwise.
		if (underground) forceUnderground();
		String result = cc.placeSwap(chunk);
		collectFailed(result, failedOut);
		boolean ok = result != null && result.toLowerCase().contains("done");
		Log.info(what + " batch " + b + "/" + total + " (" + chunk.size() + " lines) -> "
				+ (result == null ? "NO RESPONSE (closed: " + cc.closedReason() + ")" : result));
		if (!ok) {
			if (result == null) abortLivePlace();
			Chat.warn(what + " stopped at batch " + b + "/" + total + ": "
					+ (result == null ? "no response - try a smaller -batch (e.g. -batch 25)." : result));
		}
		return ok;
	}

	/**
	 * A place got no response: the in-game placer may still be running with the API queue populated,
	 * and we're about to release the BUSY lock. Stop it the same way /df end does, so a timed-out
	 * place can't keep seating blocks under (or fight) the next operation.
	 */
	private static void abortLivePlace() {
		try {
			CodeClient.MC.execute(() -> {
				CodeClient.confirmingAction = null;
				CodeClient.currentAction = new None();
				try { CodeClient.API.abort(); } catch (Throwable ignored) {}
			});
		} catch (Throwable ignored) {}
	}

	/**
	 * Sort the lines for placement: primary key is the line TYPE (player event, game event, entity
	 * event, function, process, unknown), secondary key is the chosen {@code sort}:
	 *   "size" -> largest first (so small lines pack together with other small lines),
	 *   "type"/"none" -> type only (codec/file order preserved within a type),
	 *   anything else ("alpha", default) -> the line name A-Z.
	 * Sort keys are decoded ONCE per line up front (gzip-decoding a token in the comparator would be
	 * O(n log n) decodes); a stable sort then preserves file order among equal keys.
	 */
	private static List<String> sortForPlacement(List<String> lines, String sort, boolean compact) {
		record Keyed(String token, int rank, String name, int len) {}
		List<Keyed> keyed = new ArrayList<>(lines.size());
		for (String t : lines) keyed.add(new Keyed(t, Templates.lineRank(t), Templates.name(t).toLowerCase(), Templates.length(t)));
		Comparator<Keyed> cmp;
		if (compact) {
			// -compact ignores type entirely: order largest-first so the packer bin-packs each column
			// close to the plot's code length. (This is why compact no longer leads with events.)
			cmp = (a, b) -> Integer.compare(b.len(), a.len());
		} else {
			// ORGANIZED: type first (events -> game -> entity -> func -> process), then the -sort key.
			String mode = (sort == null) ? "alpha" : sort.toLowerCase();
			cmp = Comparator.comparingInt(Keyed::rank);
			switch (mode) {
				case "size" -> cmp = cmp.thenComparing((a, b) -> Integer.compare(b.len(), a.len())); // largest first
				case "type", "none", "file" -> { /* type only; stable sort keeps file order within a type */ }
				default -> cmp = cmp.thenComparing(Keyed::name); // alpha (default)
			}
		}
		keyed.sort(cmp);
		List<String> out = new ArrayList<>(lines.size());
		for (Keyed k : keyed) out.add(k.token());
		return out;
	}

	private static void snapshot(Config cfg, String project, List<String> lines, String kind) {
		try {
			Path backups = Path.of(cfg.plotsRoot).resolve(".df-backups");
			Files.createDirectories(backups);
			String name = project + "-" + LocalDateTime.now().format(TS) + "-" + kind + ".txt";
			Files.write(backups.resolve(name), String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
		} catch (Exception e) {
			Chat.warn("Backup skipped: " + e.getMessage());
		}
	}

	private static void writeMeta(Path dir, String project, PlotInfo plot, int lineCount, int codecVersion) throws Exception {
		writeMeta(dir, project, plot, lineCount, codecVersion, 0);
	}

	/** plotLengthOverride > 0 pins the recorded plotLength (schematic saves with -size target a plot
	 *  other than the one being stood on); otherwise the live plot's detected length is used. */
	private static void writeMeta(Path dir, String project, PlotInfo plot, int lineCount, int codecVersion,
			int plotLengthOverride) throws Exception {
		JsonObject meta = new JsonObject();
		meta.addProperty("project", project);
		meta.addProperty("lines", lineCount);
		meta.addProperty("lastSync", LocalDateTime.now().toString());
		if (codecVersion > 0) meta.addProperty("codecVersion", codecVersion);
		if (plot != null) {
			meta.addProperty("plotId", plot.id());
			meta.addProperty("plotName", plot.name());
		}
		int plotLength = plotLengthOverride > 0 ? plotLengthOverride : detectedPlotLength();
		if (plotLength > 0) meta.addProperty("plotLength", plotLength); // codec default for length validation
		try { if (DfVibeClient.config != null && DfVibeClient.config.rank != null && !DfVibeClient.config.rank.isBlank())
			meta.addProperty("rank", DfVibeClient.config.rank); } catch (Throwable ignored) {} // codec RANK_LOCKED check
		Files.writeString(dir.resolve(".df-vibe.json"), GSON.toJson(meta));
	}

	/** Merge the live plot's code length into .df-vibe.json (without rewriting the rest of the meta). */
	private static void recordPlotLength(Path dir) {
		int len = detectedPlotLength();
		if (len <= 0) return;
		try {
			Path f = dir.resolve(".df-vibe.json");
			JsonObject meta = Files.exists(f) ? GSON.fromJson(Files.readString(f), JsonObject.class) : null;
			if (meta == null) meta = new JsonObject();
			if (meta.has("plotLength") && meta.get("plotLength").getAsInt() == len) return;
			meta.addProperty("plotLength", len);
			Files.writeString(f, GSON.toJson(meta));
		} catch (Exception ignored) {
		}
	}

	static String readMetaPlotId(Path dir) {
		try {
			Path f = dir.resolve(".df-vibe.json");
			if (!Files.exists(f)) return null;
			JsonObject meta = GSON.fromJson(Files.readString(f), JsonObject.class);
			return (meta != null && meta.has("plotId")) ? meta.get("plotId").getAsString() : null;
		} catch (Exception e) {
			return null;
		}
	}

	private static void reportOrphans(Path dir, List<String> written) throws Exception {
		try (Stream<Path> s = Files.list(dir)) {
			List<String> orphans = s.map(p -> p.getFileName().toString())
					.filter(n -> n.endsWith(".py") && !written.contains(n))
					.toList();
			if (!orphans.isEmpty()) {
				Chat.warn(orphans.size() + " local file(s) not in the plot (kept): " + String.join(", ", orphans));
			}
		}
	}

	private static Path registryFile(Config cfg) {
		return Path.of(cfg.plotsRoot).resolve(".df-vibe-registry.json");
	}

	private static Map<String, String> loadRegistry(Config cfg) {
		try {
			Path f = registryFile(cfg);
			if (Files.exists(f)) {
				Type t = new TypeToken<Map<String, String>>() {}.getType();
				Map<String, String> m = GSON.fromJson(Files.readString(f), t);
				if (m != null) return m;
			}
		} catch (Exception ignored) {
		}
		return new HashMap<>();
	}

	private static void saveRegistry(Config cfg, Map<String, String> m) {
		try {
			Files.writeString(registryFile(cfg), GSON.toJson(m));
		} catch (Exception e) {
			Chat.warn("Registry save failed: " + e.getMessage());
		}
	}

	// --- per-line content hashes (file -> hash), the "last synced" state for change detection
	private static Map<String, String> loadHashes(Path dir) {
		try {
			Path f = dir.resolve(".df-hashes.json");
			if (Files.exists(f)) {
				Type t = new TypeToken<Map<String, String>>() {}.getType();
				Map<String, String> m = GSON.fromJson(Files.readString(f), t);
				if (m != null) return m;
			}
		} catch (Exception ignored) {
		}
		return new HashMap<>();
	}

	private static void saveHashes(Path dir, Map<String, String> m) {
		try {
			Files.writeString(dir.resolve(".df-hashes.json"), GSON.toJson(m));
		} catch (Exception e) {
			Chat.warn("Hash state save failed: " + e.getMessage());
		}
	}

	private static Map<String, String> currentHashes(JsonArray files) {
		Map<String, String> m = new HashMap<>();
		for (var el : files) {
			JsonObject f = el.getAsJsonObject();
			m.put(f.get("file").getAsString(), f.get("hash").getAsString());
		}
		return m;
	}

	/**
	 * Hashes to save after a place: current for every file EXCEPT those whose line never reached the
	 * plot (length-dropped / overflow) - those keep their previously-stored hash (or stay absent), so
	 * /df diff and the next push still report them as pending instead of silently "in sync".
	 */
	private static Map<String, String> hashesAfterPlace(JsonArray files, Map<String, String> stored, Set<String> unplaced) {
		Map<String, String> m = currentHashes(files);
		for (String f : unplaced) {
			if (f == null) continue;
			String prev = stored.get(f);
			if (prev == null) m.remove(f);
			else m.put(f, prev);
		}
		return m;
	}

	/**
	 * Machine-readable result of the last push/deploy/fill: <project>/.df-last-sync.json.
	 * statusByFile maps file -> placed|failed|skipped_length|overflow (anything absent = unchanged);
	 * files that failed to compile are appended as failed with the codec's error.
	 */
	private static void writeLastSync(Path dir, String op, String plotId, JsonObject res,
			Map<String, String> statusByFile, String placeError) {
		try {
			JsonObject o = new JsonObject();
			o.addProperty("op", op);
			o.addProperty("timestamp", LocalDateTime.now().toString());
			if (plotId != null) o.addProperty("plotId", plotId);
			JsonArray lines = new JsonArray();
			for (var el : res.getAsJsonArray("files")) {
				JsonObject f = el.getAsJsonObject();
				String file = f.get("file").getAsString();
				String status = statusByFile.getOrDefault(file, "unchanged");
				JsonObject entry = new JsonObject();
				entry.addProperty("id", f.get("id").getAsString());
				entry.addProperty("file", file);
				entry.addProperty("status", status);
				if ("failed".equals(status) && placeError != null) entry.addProperty("error", placeError);
				lines.add(entry);
			}
			if (res.has("errors")) {
				for (var el : res.getAsJsonArray("errors")) {
					JsonObject e = el.getAsJsonObject();
					JsonObject entry = new JsonObject();
					entry.addProperty("file", e.get("file").getAsString());
					entry.addProperty("status", "failed");
					entry.addProperty("error", e.get("error").getAsString());
					lines.add(entry);
				}
			}
			o.add("lines", lines);
			Files.writeString(dir.resolve(".df-last-sync.json"), GSON.toJson(o));
		} catch (Exception e) {
			Chat.warn("Sync-result save failed: " + e.getMessage());
		}
	}
}
