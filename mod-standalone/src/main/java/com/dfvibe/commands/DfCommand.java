package com.dfvibe.commands;

import com.dfvibe.Chat;
import com.dfvibe.Config;
import com.dfvibe.DfVibeClient;
import com.dfvibe.RuntimeLog;
import com.dfvibe.SyncService;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Stream;

import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.argument;
import static net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal;

/** The /df command tree. Pull/push run on worker threads (network + subprocess). */
public final class DfCommand {
	private DfCommand() {}

	/** Default = place everything in one go (no batching). Opt into chunking with -batch N (e.g. if a giant place times out). */
	private static final int DEFAULT_BATCH = 0;

	public static void register(CommandDispatcher<FabricClientCommandSource> d) {
		d.register(literal("df")
			.executes(c -> { help(); return 1; })
			.then(literal("help").executes(c -> { help(); return 1; }))
			.then(literal("status").executes(c -> { status(); return 1; }))
			.then(literal("path")
				.executes(c -> { showPaths(); return 1; })
				.then(literal("auto").executes(c -> { pathAuto(); return 1; }))
				.then(literal("plots").then(argument("path", StringArgumentType.greedyString()).suggests(DfCommand::suggestPaths)
					.executes(c -> { setPlots(StringArgumentType.getString(c, "path")); return 1; })))
				.then(literal("codec").then(argument("path", StringArgumentType.greedyString()).suggests(DfCommand::suggestPaths)
					.executes(c -> { setCodec(StringArgumentType.getString(c, "path")); return 1; })))
				.then(literal("python").then(argument("path", StringArgumentType.greedyString()).suggests(DfCommand::suggestPaths)
					.executes(c -> { setPython(StringArgumentType.getString(c, "path")); return 1; })))
				.then(literal("builds").then(argument("path", StringArgumentType.greedyString()).suggests(DfCommand::suggestPaths)
					.executes(c -> { setSchemRoot(StringArgumentType.getString(c, "path")); return 1; }))))
			.then(literal("config")
				.executes(c -> { showConfig(); return 1; })
				.then(argument("kv", StringArgumentType.greedyString()).suggests(DfCommand::suggestConfig)
					.executes(c -> { setConfig(StringArgumentType.getString(c, "kv")); return 1; })))
			.then(literal("use").then(argument("name", StringArgumentType.word()).suggests(DfCommand::suggestProjects)
				.executes(c -> { use(StringArgumentType.getString(c, "name")); return 1; })))
			.then(literal("pull")
				.executes(c -> { pullArgs(""); return 1; })
				.then(argument("name", StringArgumentType.greedyString()).suggests(DfCommand::suggestPull)
					.executes(c -> { pullArgs(StringArgumentType.getString(c, "name")); return 1; })))
			.then(literal("push")
				.executes(c -> { push(Opts.DEFAULT); return 1; })
				.then(argument("opts", StringArgumentType.greedyString()).suggests(DfCommand::suggestOpts)
					.executes(c -> { push(parseOpts(StringArgumentType.getString(c, "opts"))); return 1; })))
			.then(literal("deploy")
				.executes(c -> { deploy(Opts.DEFAULT); return 1; })
				.then(argument("opts", StringArgumentType.greedyString()).suggests(DfCommand::suggestOpts)
					.executes(c -> { deploy(parseOpts(StringArgumentType.getString(c, "opts"))); return 1; })))
			.then(literal("fill")
				.executes(c -> { fill(Opts.DEFAULT); return 1; })
				.then(argument("opts", StringArgumentType.greedyString()).suggests(DfCommand::suggestOpts)
					.executes(c -> { fill(parseOpts(StringArgumentType.getString(c, "opts"))); return 1; })))
			.then(literal("debug")
				.then(literal("log")
					.executes(c -> { RuntimeLog.status(); return 1; })
					.then(literal("start").executes(c -> { RuntimeLog.start(DfVibeClient.config); return 1; }))
					.then(literal("stop").executes(c -> { RuntimeLog.stop(); return 1; }))
					.then(literal("status").executes(c -> { RuntimeLog.status(); return 1; })))
				.then(literal("missingchest").executes(c -> { SyncService.toggleShowMissing(); return 1; })))
			.then(literal("end").executes(c -> { end(); return 1; })
				.then(literal("compile").executes(c -> { endCompile(); return 1; }))
				.then(literal("pause").executes(c -> { SyncService.pause(true); return 1; }))
				.then(literal("continue").executes(c -> { SyncService.pause(false); return 1; }))
				.then(literal("resume").executes(c -> { SyncService.pause(false); return 1; })))
			.then(literal("cancel").executes(c -> { end(); return 1; }))
			.then(literal("stop").executes(c -> { end(); return 1; }))
			.then(literal("backup")
				.executes(c -> { backup(); return 1; })
				.then(literal("list").executes(c -> { SyncService.backupList(DfVibeClient.config); return 1; }))
				.then(literal("load").then(argument("file", StringArgumentType.word()).suggests(DfCommand::suggestBackups)
					.executes(c -> { backupLoad(StringArgumentType.getString(c, "file")); return 1; }))))
			.then(literal("schematic")
				.executes(c -> { schematicHelp(); return 1; })
				.then(literal("save").then(argument("opts", StringArgumentType.greedyString()).suggests(DfCommand::suggestSchemSave)
					.executes(c -> { schematicSaveArgs(StringArgumentType.getString(c, "opts")); return 1; })))
				.then(literal("load").then(argument("opts", StringArgumentType.greedyString()).suggests(DfCommand::suggestSchemLoad)
					.executes(c -> { schematicLoadArgs(StringArgumentType.getString(c, "opts")); return 1; }))))
		);
	}

	private static void schematicHelp() {
		Chat.raw(Text.literal("schematic ").formatted(Formatting.AQUA)
				.append(Text.literal("copy a build to another plot").formatted(Formatting.GRAY)));
		Chat.plain(Text.literal("  ").append(Chat.suggest("save <name>", "/df schematic save ",
				"Capture a build (blocks, signs, heads, container contents) into loader code. Flags: -nochest (shape only), -nomove (fly it yourself), -size (target plot).")));
		Chat.plain(Text.literal("  ").append(Chat.suggest("load <name>", "/df schematic load ",
				"Deploy the build's loader, then stand on the origin and type @load in chat.")));
		Chat.plain(Text.literal("  builds folder set with ").formatted(Formatting.DARK_GRAY)
				.append(Chat.suggest("/df path builds", "/df path builds ", "folder where saved builds live")));
	}

	private static void showSchemRoot() {
		String r = DfVibeClient.config.schemRoot;
		if (r == null || r.isBlank()) Chat.info("No builds folder set. Set one with /df path builds <dir>.");
		else Chat.info("Builds folder: " + r);
	}

	private static void setSchemRoot(String path) {
		Config cfg = DfVibeClient.config;
		cfg.schemRoot = path.trim();
		cfg.save();
		Chat.good("Builds folder set to " + cfg.schemRoot);
	}

	/** Parsed /df schematic save arguments, shared by the interactive command and the AFK queue. regionBox =
	 *  inline `-region x1 y1 z1 x2 y2 z2` (null = fall back to /df region; a capture always needs SOME box).
	 *  sizeLen = target plot code-line length from -size (-1 = auto-detect the current plot). */
	private record SchemSaveSpec(String name, boolean nomove, int[] regionBox, int sizeLen, boolean nochest) {}

	/** -size value -> target plot code-line length: massive/mega = 300, large = 100, basic = 50,
	 *  or a raw number for a custom length. Returns -1 when the value isn't recognized. */
	private static int parsePlotSize(String v) {
		switch (v.toLowerCase()) {
			case "massive", "mega" -> { return 300; }
			case "large" -> { return 100; }
			case "basic" -> { return 50; }
			default -> {
				try {
					int n = Integer.parseInt(v);
					return (n >= 10 && n <= 1000) ? n : -1;
				} catch (NumberFormatException e) { return -1; }
			}
		}
	}

	/** Parse `/df schematic save [name] [-spectator|-eventless-fly|-nomove] [-region [x1 y1 z1 x2 y2 z2]]
	 *  [-size massive|large|basic|<number>] [-nochest]`. A capture always uses a box: the inline -region coords
	 *  if given, else the /df region. -size targets the plot the build will LOAD on (generated code lines are
	 *  budgeted to fit it); default is the plot you're standing on. -nochest skips every container pick, so the
	 *  build's shape is captured but its chests come out empty. */
	private static SchemSaveSpec parseSchemSave(String raw) {
		String name = null;
		boolean nomove = false, nochest = false;
		int[] regionBox = null;
		int sizeLen = -1;
		String[] toks = (raw == null ? "" : raw).trim().split("\\s+");
		for (int i = 0; i < toks.length; i++) {
			String tok = toks[i];
			if (tok.isEmpty()) continue;
			if (tok.equalsIgnoreCase("-nomove")) nomove = true;
			else if (tok.equalsIgnoreCase("-region")) {
				int[] box = tryParseCoords(toks, i + 1);   // inline x1 y1 z1 x2 y2 z2
				if (box != null) { regionBox = box; i += 6; }
				else Chat.warn("-region needs 6 coords: -region x1 y1 z1 x2 y2 z2.");
			}
			else if (tok.equalsIgnoreCase("-size")) {
				int v = (i + 1 < toks.length) ? parsePlotSize(toks[i + 1]) : -1;
				if (v > 0) { sizeLen = v; i++; }
				else Chat.warn("-size wants massive, large, basic or a number. Using the current plot's size.");
			}
			else if (tok.equalsIgnoreCase("-nochest") || tok.equalsIgnoreCase("-nochests")
					|| tok.equalsIgnoreCase("-no-chest")) nochest = true;
			else if (name == null && !tok.startsWith("-")) name = tok;
			else if (tok.startsWith("-")) Chat.warn("Unknown schematic flag '" + tok + "' (ignored). Flags: -nomove -nochest -region -size");
		}
		return new SchemSaveSpec(name, nomove, regionBox, sizeLen, nochest);
	}

	/** /df schematic save [name] [-eventless-fly|-nomove] - same movement flags as pull; grabs chests unless -nochest. */
	private static void schematicSaveArgs(String raw) {
		SchemSaveSpec s = parseSchemSave(raw);
		if (s.name() == null) { Chat.error("Give a name: /df schematic save <name> -region x1 y1 z1 x2 y2 z2 [-nomove] [-nochest]."); return; }
		Config cfg = DfVibeClient.config;
		if (!SyncService.tryLock()) { Chat.warn("Busy.", "A sync is already running. Stop it with /df end."); return; }
		Chat.info("Capturing build '" + s.name() + "'...");
		new Thread(() -> { try { runSchemSave(cfg, s); } finally { SyncService.unlock(); } }, "df-schem-save").start();
	}

	/** Blocking build capture (no lock/thread of its own) - used by the interactive worker AND the queue. */
	private static void runSchemSave(Config cfg, SchemSaveSpec s) {
		SyncService.schematicSave(cfg, s.name(), s.nomove(), s.regionBox(), s.sizeLen(), s.nochest());
	}

	/** /df schematic load [name] [-clear] - deploy the saved build's loader (additive unless -clear). */
	private static void schematicLoadArgs(String raw) {
		String name = null;
		boolean clear = false;
		for (String tok : (raw == null ? "" : raw).trim().split("\\s+")) {
			if (tok.isEmpty()) continue;
			if (tok.equalsIgnoreCase("-clear")) clear = true;
			else if (name == null && !tok.startsWith("-")) name = tok;
		}
		if (name == null) { Chat.error("Give a name: /df schematic load <name> [-clear]."); return; }
		Config cfg = DfVibeClient.config;
		if (!SyncService.tryLock()) { Chat.warn("Busy.", "A sync is already running. Stop it with /df end."); return; }
		Chat.info("Loading build '" + name + "'...");
		final String fn = name; final boolean fclear = clear;
		new Thread(() -> { try { SyncService.schematicLoad(cfg, fn, fclear); } finally { SyncService.unlock(); } }, "df-schem-load").start();
	}

	/** Saved-build subfolders of schemRoot whose name starts with prefix, sorted. */
	private static List<String> listSchems(String prefix) {
		Config cfg = DfVibeClient.config;
		List<String> out = new ArrayList<>();
		if (cfg.schemRoot != null && !cfg.schemRoot.isBlank()) {
			Path root = Path.of(cfg.schemRoot);
			if (Files.isDirectory(root)) {
				try (Stream<Path> s = Files.list(root)) {
					s.filter(Files::isDirectory)
						.map(p -> p.getFileName().toString())
						.filter(n -> !n.startsWith(".") && n.toLowerCase().startsWith(prefix))
						.sorted()
						.forEach(out::add);
				} catch (Exception ignored) {
				}
			}
		}
		return out;
	}

	private static CompletableFuture<Suggestions> suggestSchemSave(CommandContext<FabricClientCommandSource> ctx, SuggestionsBuilder b) {
		String remaining = b.getRemaining();
		int lastSpace = remaining.lastIndexOf(' ');
		String token = remaining.substring(lastSpace + 1).toLowerCase();
		SuggestionsBuilder ob = b.createOffset(b.getStart() + lastSpace + 1);
		if (lastSpace < 0) for (String p : listSchems(token)) ob.suggest(p); // existing names (overwrite) only as first word
		String low = remaining.toLowerCase();
		String before = lastSpace > 0 ? remaining.substring(0, lastSpace).trim().toLowerCase() : "";
		if (before.endsWith("-size")) {                       // value for -size: presets (or type a number)
			for (String v : new String[]{"massive", "large", "basic"}) if (v.startsWith(token)) ob.suggest(v);
			return ob.buildFuture();
		}
		if ("-nomove".startsWith(token) && !low.contains("-nomove")) ob.suggest("-nomove");
		if ("-nochest".startsWith(token) && !low.contains("-nochest")) ob.suggest("-nochest");
		if ("-region".startsWith(token) && !low.contains("-region")) ob.suggest("-region");
		if ("-size".startsWith(token) && !low.contains("-size")) ob.suggest("-size");
		return ob.buildFuture();
	}

	private static CompletableFuture<Suggestions> suggestSchemLoad(CommandContext<FabricClientCommandSource> ctx, SuggestionsBuilder b) {
		String remaining = b.getRemaining();
		int lastSpace = remaining.lastIndexOf(' ');
		String token = remaining.substring(lastSpace + 1).toLowerCase();
		SuggestionsBuilder ob = b.createOffset(b.getStart() + lastSpace + 1);
		if (lastSpace < 0) for (String p : listSchems(token)) ob.suggest(p);
		if ("-clear".startsWith(token) && !remaining.toLowerCase().contains("-clear")) ob.suggest("-clear");
		return ob.buildFuture();
	}

	private static void help() {
		Chat.raw(Text.literal("commands ").formatted(Formatting.AQUA)
				.append(Text.literal("(click to type, hover for details)").formatted(Formatting.DARK_GRAY)));
		hcmd("pull", "[name]", "scan the plot into files",
				"Reads the plot's code into your project files. No name auto-detects the plot. Over-limit lines are rebuilt from their blocks automatically.");
		hcmd("push", "", "send your edits back",
				"Places only the lines you changed or added. Safe to repeat; it never deletes.");
		hcmd("deploy", "", "clear, then place every line",
				"Wipes the codespace and re-places everything. Use it to delete lines or re-lay-out. Flags: -sort, -gap, -compact, -batch, -underground, -fix.");
		hcmd("fill", "", "place missing lines only",
				"Places lines that are in your files but not on the plot. Never clears.");
		hcmd("schematic", "save|load", "copy a build",
				"save captures a build (blocks, signs, heads, container contents) into loader code. load deploys it.");
		hcmd("backup", "[list|load]", "snapshot the plot",
				"backup saves the current plot. list and load browse and restore snapshots.");
		hcmd("use", "<project>", "switch active project",
				"Sets which project push, deploy and fill target.");
		hcmd("config", "", "settings",
				"salvageReach, placeReach, rank, default flags, and more.");
		hcmd("path", "", "folders",
				"Where your projects, the codec, python and saved builds live. /df path auto sets them all up for you.");
		hcmd("status", "", "readiness and plot link", null);
		Chat.plain(Text.literal("  stuck? ").formatted(Formatting.DARK_GRAY)
				.append(Chat.runCmd("/df end", "/df end", "stop the running operation")));
	}

	/** One help row: clickable command name (types it in chat, hover shows detail), dim args, gray one-liner. */
	private static void hcmd(String name, String args, String desc, String hover) {
		MutableText row = Text.literal("  ")
				.append(Chat.suggest(name, "/df " + name + " ", hover != null ? hover : desc));
		if (!args.isEmpty()) row.append(Text.literal(" " + args).formatted(Formatting.DARK_GRAY));
		row.append(Text.literal("  " + desc).formatted(Formatting.GRAY));
		Chat.plain(row);
	}

	private static void status() {
		Config cfg = DfVibeClient.config;
		String proj = (cfg.activeProject == null || cfg.activeProject.isBlank()) ? null : cfg.activeProject;
		if (!cfg.ready()) {
			Chat.warn("Not set up yet.", Chat.runCmd("[auto-setup]", "/df path auto", "create a DF Vibe folder with plots, builds and a ready codec, then point the config at them"));
			return;
		}
		if (proj == null) {
			Chat.info("Ready, no project yet.", Chat.suggest("[pull]", "/df pull ", "scan a plot to start a project"));
			return;
		}
		MutableText line = Text.literal("Project ").formatted(Formatting.WHITE)
				.append(Text.literal(proj).formatted(Formatting.AQUA));
		if (cfg.rank != null && !cfg.rank.isBlank())
			line.append(Text.literal("   rank ").formatted(Formatting.DARK_GRAY))
					.append(Text.literal(cfg.rank).formatted(Formatting.GRAY));
		if (RuntimeLog.isOn())
			line.append(Text.literal("   log on").formatted(Formatting.GRAY));
		Chat.raw(line);
	}

	/** Show the four folder settings, each with a [set] click when unset. */
	private static void showPaths() {
		Config cfg = DfVibeClient.config;
		sline("plots", cfg.plotsRoot.isBlank() ? null : cfg.plotsRoot,
				Chat.suggest("[set]", "/df path plots ", "folder your projects live in"));
		sline("codec", cfg.codecDir.isBlank() ? null : cfg.codecDir,
				Chat.suggest("[set]", "/df path codec ", "folder with dfpy.py"));
		sline("python", cfg.pythonPath, null);
		sline("builds", (cfg.schemRoot == null || cfg.schemRoot.isBlank()) ? null : cfg.schemRoot,
				Chat.suggest("[set]", "/df path builds ", "folder for saved schematics"));
		Chat.plain(Text.literal("  ").append(Chat.runCmd("[auto-setup]", "/df path auto",
				"create the DF Vibe folder + a ready codec automatically")));
	}

	/** One key/value row: gray label, white value, or a dim "not set" plus a [set] click when the value is null. */
	private static void sline(String label, String value, MutableText setter) {
		MutableText row = Text.literal("  " + label + ": ").formatted(Formatting.GRAY);
		if (value != null) row.append(Text.literal(value).formatted(Formatting.WHITE));
		else {
			row.append(Text.literal("not set").formatted(Formatting.DARK_GRAY));
			if (setter != null) row.append(Text.literal(" ")).append(setter);
		}
		Chat.plain(row);
	}

	/** /df path auto: create a "DF Vibe" folder next to your Minecraft folder with plots/, builds/ and a
	 *  ready-to-run codec/ (extracted from the mod), and point the config at them. Python must be installed. */
	private static void pathAuto() {
		Config cfg = DfVibeClient.config;
		Path base = net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().resolve("DF Vibe");
		try {
			Path plots = base.resolve("plots"), builds = base.resolve("builds"), codec = base.resolve("codec");
			Files.createDirectories(plots);
			Files.createDirectories(builds);
			Files.createDirectories(codec);
			// Never clobber an existing codec - if you've edited it, keep your copy.
			boolean hadCodec = Files.exists(codec.resolve("dfpy.py"));
			int files = hadCodec ? 0 : extractCodec(codec);
			cfg.plotsRoot = plots.toString();
			cfg.schemRoot = builds.toString();
			cfg.codecDir = codec.toString();
			cfg.save();
			Chat.good("Set up in " + base.getFileName() + ".",
					(hadCodec ? "Kept your existing codec; " : "Extracted the codec (" + files + " files); ")
							+ "created plots and builds under " + base + " and pointed the config at them. "
							+ "You still need Python installed.");
			Chat.raw(Text.literal("  ").append(Chat.openFile("[open folder]", base.toString()))
					.append(Text.literal("  ")).append(Chat.runCmd("[status]", "/df status", "check setup")));
		} catch (Exception e) {
			Chat.error("Auto-setup failed: " + e.getMessage(),
					"Couldn't write to " + base + ". Check folder permissions, or set paths by hand with /df path.");
		}
	}

	/** Unzip the mod's bundled codec.zip into {@code dest}; returns the number of files written. */
	private static int extractCodec(Path dest) throws java.io.IOException {
		var in = DfCommand.class.getResourceAsStream("/codec.zip");
		if (in == null) throw new java.io.IOException("this build has no bundled codec - use /df path codec <dir> instead");
		int n = 0;
		byte[] buf = new byte[8192];
		try (var zis = new java.util.zip.ZipInputStream(in)) {
			java.util.zip.ZipEntry e;
			while ((e = zis.getNextEntry()) != null) {
				Path out = dest.resolve(e.getName()).normalize();
				if (!out.startsWith(dest)) continue;   // zip-slip guard
				if (e.isDirectory()) { Files.createDirectories(out); continue; }
				if (out.getParent() != null) Files.createDirectories(out.getParent());
				try (var os = Files.newOutputStream(out)) {
					int r;
					while ((r = zis.read(buf)) > 0) os.write(buf, 0, r);
				}
				n++;
			}
		}
		return n;
	}

	/** Project subfolders of the plots folder whose name starts with prefix (lower-cased), sorted. */
	private static List<String> listProjects(String prefix) {
		Config cfg = DfVibeClient.config;
		List<String> out = new ArrayList<>();
		if (!cfg.plotsRoot.isBlank()) {
			Path root = Path.of(cfg.plotsRoot);
			if (Files.isDirectory(root)) {
				try (Stream<Path> s = Files.list(root)) {
					s.filter(Files::isDirectory)
						.map(p -> p.getFileName().toString())
						.filter(n -> !n.startsWith(".") && n.toLowerCase().startsWith(prefix))
						.sorted()
						.forEach(out::add);
				} catch (Exception ignored) {
				}
			}
		}
		return out;
	}

	private static CompletableFuture<Suggestions> suggestProjects(CommandContext<FabricClientCommandSource> ctx, SuggestionsBuilder b) {
		listProjects(b.getRemaining().toLowerCase()).forEach(b::suggest);
		return b.buildFuture();
	}

	/** /df pull: a project name (first token), or the -save flag. */
	private static CompletableFuture<Suggestions> suggestPull(CommandContext<FabricClientCommandSource> ctx, SuggestionsBuilder b) {
		String remaining = b.getRemaining();
		int lastSpace = remaining.lastIndexOf(' ');
		String token = remaining.substring(lastSpace + 1).toLowerCase();
		SuggestionsBuilder ob = b.createOffset(b.getStart() + lastSpace + 1);
		if (lastSpace < 0) for (String p : listProjects(token)) ob.suggest(p);     // project only as the first word
		String low = remaining.toLowerCase();
		if ("-save".startsWith(token) && !low.contains("-save")) ob.suggest("-save");
		return ob.buildFuture();
	}

	/** Value hints per config key (booleans -> on/off; flag fields -> example flags; etc.). */
	private static final java.util.Map<String, String[]> CONFIG_VALUES = java.util.Map.of(
		"salvageReach", new String[]{"on", "off"},
		"placeReach", new String[]{"on", "off"},
		"pullOpts", new String[]{"-save"},
		"pushOpts", new String[]{"-compact", "-underground", "-batch 25"},
		"deployOpts", new String[]{"-compact", "-underground", "-sort size"},
		"pythonPath", new String[]{"python", "python3"},
		"locateCommand", new String[]{"locate"},
		"rank", new String[]{"none", "Noble", "Emperor", "Mythic", "Overlord"},
		"codeClientPort", new String[]{"31375"});

	/** /df config: suggest field names for the first token, then value hints for that field. */
	private static CompletableFuture<Suggestions> suggestConfig(CommandContext<FabricClientCommandSource> ctx, SuggestionsBuilder b) {
		String remaining = b.getRemaining();
		int sp = remaining.indexOf(' ');
		if (sp < 0) {
			String tok = remaining.toLowerCase();
			for (Field f : Config.class.getFields()) {
				if (Modifier.isStatic(f.getModifiers()) || f.getName().equals("apiToken")) continue;
				if (f.getName().toLowerCase().startsWith(tok)) b.suggest(f.getName());
			}
			return b.buildFuture();
		}
		String key = remaining.substring(0, sp);
		String valTok = remaining.substring(sp + 1).toLowerCase();
		SuggestionsBuilder vb = b.createOffset(b.getStart() + sp + 1);
		String canon = null;
		for (Field f : Config.class.getFields())
			if (!Modifier.isStatic(f.getModifiers()) && f.getName().equalsIgnoreCase(key)) canon = f.getName();
		for (String h : (canon != null ? CONFIG_VALUES.getOrDefault(canon, new String[0]) : new String[0]))
			if (h.toLowerCase().startsWith(valTok)) vb.suggest(h);
		return vb.buildFuture();
	}

	/** Filesystem directory completion for path arguments (workspace/codec/python). */
	private static CompletableFuture<Suggestions> suggestPaths(CommandContext<FabricClientCommandSource> ctx, SuggestionsBuilder b) {
		String remaining = b.getRemaining();
		try {
			Path dir;
			String prefix;
			if (remaining.isEmpty()) { dir = Path.of("."); prefix = ""; }
			else if (remaining.endsWith("/") || remaining.endsWith("\\")) { dir = Path.of(remaining); prefix = ""; }
			else {
				Path typed = Path.of(remaining);
				dir = typed.getParent() != null ? typed.getParent() : Path.of(".");
				prefix = typed.getFileName() != null ? typed.getFileName().toString().toLowerCase() : "";
			}
			if (Files.isDirectory(dir)) {
				try (Stream<Path> s = Files.list(dir)) {
					s.filter(Files::isDirectory)
						.filter(p -> p.getFileName().toString().toLowerCase().startsWith(prefix))
						.map(p -> p.toString().replace('\\', '/'))
						.sorted()
						.limit(20)
						.forEach(b::suggest);
				}
			}
		} catch (Exception ignored) {
		}
		return b.buildFuture();
	}

	private static void setPlots(String path) {
		Config cfg = DfVibeClient.config;
		cfg.plotsRoot = path.trim();
		cfg.save();
		Chat.good("Plots folder set.", cfg.plotsRoot);
	}

	private static void setCodec(String path) {
		Config cfg = DfVibeClient.config;
		cfg.codecDir = path.trim();
		cfg.save();
		Path script = Path.of(cfg.codecDir).resolve("dfpy.py");
		if (!Files.exists(script)) Chat.warn("dfpy.py not found at " + script + " (set the right folder).");
		else Chat.good("codec = " + cfg.codecDir);
	}

	private static void setPython(String path) {
		Config cfg = DfVibeClient.config;
		cfg.pythonPath = path.trim();
		cfg.save();
		Chat.good("python = " + cfg.pythonPath);
	}

	private static void use(String name) {
		Config cfg = DfVibeClient.config;
		cfg.activeProject = name;
		cfg.save();
		Path dir = cfg.projectDir(name);
		if (!Files.isDirectory(dir)) Chat.warn("active = " + name + " (folder doesn't exist yet; /df pull " + name + " to create it)");
		else Chat.good("active = " + name);
	}

	private static void pull(String name) { pull(parsePull(name)); }

	/** Parsed /df pull arguments, shared by the interactive command and the AFK queue. regionBox = inline
	 *  `-region x1 y1 z1 x2 y2 z2` coords (null if -region was given bare or not at all - see {@code region}). */
	private record PullSpec(String name, boolean save) {}

	/**
	 * Parse `/df pull [name] [-chests] [-nomove] [-spectator] [-eventless-fly] [-save] [-region [x1 y1 z1 x2 y2 z2]]`.
	 * `-chests` rebuilds EVERY line from its physical blocks/chests (slow) instead of reading DF templates.
	 * `-region` scans exactly a codespace box (skip plot auto-detection) - deterministic for AFK/queue; give the
	 * 6 corner coords inline (each queue step can target a DIFFERENT box), or bare `-region` to reuse /df region.
	 */
	private static PullSpec parsePull(String raw) {
		String name = null;
		boolean save = false;
		String[] toks = ((raw == null ? "" : raw) + " " + DfVibeClient.config.pullOpts).trim().split("\\s+");
		for (int i = 0; i < toks.length; i++) {
			String tok = toks[i];
			if (tok.isEmpty()) continue;
			if (tok.equalsIgnoreCase("-save")) save = true;
			else if (name == null && !tok.startsWith("-")) name = tok;
			else if (tok.startsWith("-")) Chat.warn("Unknown pull flag: " + tok, "The only pull flag is -save.");
		}
		return new PullSpec(name, save);
	}

	/** Six consecutive integer tokens starting at {@code start} as an [x1,y1,z1,x2,y2,z2] box, or null if there
	 *  aren't six or any isn't an integer (so a bare `-region` with no coords just falls back to /df region). */
	private static int[] tryParseCoords(String[] toks, int start) {
		if (start + 6 > toks.length) return null;
		int[] box = new int[6];
		for (int k = 0; k < 6; k++) {
			try { box[k] = Integer.parseInt(toks[start + k]); }
			catch (NumberFormatException e) { return null; }
		}
		return box;
	}

	private static void pullArgs(String raw) { pull(parsePull(raw)); }

	/** Interactive pull: lock + run on a worker thread. */
	private static void pull(PullSpec s) {
		Config cfg = DfVibeClient.config;
		if (!SyncService.tryLock()) { Chat.warn("Busy.", "A sync is already running. Stop it with /df end."); return; }
		Chat.info(s.name() != null ? "Pulling '" + s.name() + "'..." : "Detecting plot & pulling...");
		new Thread(() -> { try { runPull(cfg, s); } finally { SyncService.unlock(); } }, "df-pull").start();
	}

	/** Blocking pull (no lock/thread of its own) - called by the interactive worker AND, back-to-back, by the queue. */
	private static void runPull(Config cfg, PullSpec s) {
		SyncService.pull(cfg, s.name(), false, false, s.save());
	}

	/** /df config - list every setting; each key is click-to-edit. apiToken is masked. */
	private static void showConfig() {
		Config cfg = DfVibeClient.config;
		Chat.info("Settings, click a key to edit:");
		for (Field f : Config.class.getFields()) {
			if (Modifier.isStatic(f.getModifiers())) continue;
			String name = f.getName();
			Chat.raw(Text.literal("  ").append(Chat.suggest("[" + name + "]", "/df config " + name + " ", "edit " + name))
					.append(Text.literal(" = ").formatted(Formatting.DARK_GRAY))
					.append(Text.literal(displayVal(f, cfg)).formatted(Formatting.WHITE)));
		}
	}

	/** /df config <key> <value> - set one setting (bool/int/string parsed by field type), then save. */
	private static void setConfig(String kv) {
		Config cfg = DfVibeClient.config;
		String[] parts = kv.trim().split("\\s+", 2);
		if (parts[0].isEmpty()) { showConfig(); return; }
		String key = parts[0];
		String val = parts.length > 1 ? parts[1].trim() : "";
		Field field = null;
		for (Field f : Config.class.getFields())
			if (!Modifier.isStatic(f.getModifiers()) && f.getName().equalsIgnoreCase(key)) { field = f; break; }
		if (field == null) { Chat.error("No such config key: " + key + " (run /df config to list them)."); return; }
		try {
			Class<?> t = field.getType();
			if (t == boolean.class) {
				String v = val.toLowerCase();
				if (!v.matches("true|on|yes|1|false|off|no|0")) { Chat.error("Use on/off (or true/false) for " + field.getName() + "."); return; }
				field.setBoolean(cfg, v.matches("true|on|yes|1"));
			} else if (t == int.class) {
				field.setInt(cfg, Integer.parseInt(val));
			} else if (t == double.class) {
				field.setDouble(cfg, Double.parseDouble(val));
			} else {
				field.set(cfg, val);
			}
			cfg.save();
			Chat.good("config " + field.getName() + " = " + displayVal(field, cfg));
		} catch (NumberFormatException e) {
			Chat.error(field.getName() + " expects a number.");
		} catch (Exception e) {
			Chat.error("Couldn't set " + key + ": " + e.getMessage());
		}
	}

	private static String displayVal(Field f, Config cfg) {
		try {
			Object v = f.get(cfg);
			if (f.getName().equals("apiToken")) return (v != null && !v.toString().isBlank()) ? "(set)" : "(unset)";
			String s = String.valueOf(v);
			return s.isEmpty() ? "(unset)" : s;
		} catch (Exception e) {
			return "?";
		}
	}

	private static void push(Opts o) {
		Config cfg = DfVibeClient.config;
		if (cfg.activeProject == null || cfg.activeProject.isBlank()) {
			Chat.error("No active project. /df use <name> or /df pull <name> first.");
			return;
		}
		if (rejectUnknownFlags("/df push", o)) return;
		if (!SyncService.tryLock()) { Chat.warn("Busy.", "A sync is already running. Stop it with /df end."); return; }
		Chat.info("Pushing '" + cfg.activeProject + "'" + describe(o) + "...");
		new Thread(() -> { try { SyncService.push(cfg, o.batch, o.log, o.underground, o.compact, o.sort, o.gap, o.fix); } finally { SyncService.unlock(); } }, "df-push").start();
	}

	private static void deploy(Opts o) {
		Config cfg = DfVibeClient.config;
		if (cfg.activeProject == null || cfg.activeProject.isBlank()) {
			Chat.error("No active project. /df use <name> or /df pull <name> first.");
			return;
		}
		if (rejectUnknownFlags("/df deploy", o)) return;
		if (!SyncService.tryLock()) { Chat.warn("Busy.", "A sync is already running. Stop it with /df end."); return; }
		Chat.info("Deploying (clear + place) '" + cfg.activeProject + "'" + describe(o) + "...");
		new Thread(() -> { try { SyncService.deploy(cfg, o.batch, o.log, o.underground, o.compact, o.sort, o.gap, o.fix); } finally { SyncService.unlock(); } }, "df-deploy").start();
	}

	private static void fill(Opts o) {
		Config cfg = DfVibeClient.config;
		if (cfg.activeProject == null || cfg.activeProject.isBlank()) {
			Chat.error("No active project. /df use <name> or /df pull <name> first.");
			return;
		}
		if (rejectUnknownFlags("/df fill", o)) return;
		if (!SyncService.tryLock()) { Chat.warn("Busy.", "A sync is already running. Stop it with /df end."); return; }
		Chat.info("Filling lines missing from the plot for '" + cfg.activeProject + "'" + describe(o) + "...");
		new Thread(() -> { try { SyncService.fill(cfg, o.batch, o.log, o.underground, o.compact, o.sort, o.gap, o.fix); } finally { SyncService.unlock(); } }, "df-fill").start();
	}

	/** Typo'd flags are refused on every place command (push/deploy/fill), never silently ignored -
	 *  a dropped -compact or -underground places the whole plot with the wrong layout. */
	private static boolean rejectUnknownFlags(String cmd, Opts o) {
		if (o.unknown.isEmpty()) return false;
		Chat.error("Unknown flag(s) for " + cmd + ": " + String.join(" ", o.unknown)
				+ " - valid: -batch N, -sort MODE, -gap N, -compact, -underground, -log, -fix");
		return true;
	}

	/** Human-readable summary of the active flags for the "...ing" status line. */
	private static String describe(Opts o) {
		StringBuilder sb = new StringBuilder();
		if (o.batch > 0) sb.append(" (batches of ").append(o.batch).append(")");
		sb.append(o.compact ? " [minimal layout]" : " [organized, sort=" + o.sort + ", gap=" + o.gap + "]");
		if (o.underground) sb.append(" underground");
		if (o.fix) sb.append(" [-fix: auto-fix, over-64KB lines segment-place 1-1]");
		if (o.log) sb.append(" (-log -> .df-log.txt)");
		return sb.toString();
	}

	/** Parsed flags for push/deploy/fill: -batch N, -log, -underground, -compact, -sort MODE, -gap N,
	 *  -fix (auto-fix placement errors, e.g. segment-place over-64KB lines 1-1) - any order. */
	private static final class Opts {
		final int batch; final boolean log; final boolean underground; final boolean compact;
		final String sort; final int gap; final boolean fix; final List<String> unknown;
		Opts(int batch, boolean log, boolean underground, boolean compact, String sort, int gap, boolean fix, List<String> unknown) {
			this.batch = batch; this.log = log; this.underground = underground; this.compact = compact;
			this.sort = sort; this.gap = gap; this.fix = fix; this.unknown = unknown;
		}
		static final Opts DEFAULT = new Opts(DEFAULT_BATCH, false, false, false, "alpha", 0, false, List.of());
	}

	/** Flag names suggested by autocomplete (so you don't have to remember them). */
	private static final String[] FLAGS = {"-batch", "-sort", "-gap", "-compact", "-underground", "-log", "-fix"};
	/** Accepted -sort modes. */
	private static final String[] SORT_MODES = {"alpha", "size", "type", "none"};

	private static Opts parseOpts(String s) {
		boolean log = false, underground = false, compact = false, fix = false;
		int batch = DEFAULT_BATCH, gap = 0; String sort = "alpha";
		List<String> unknown = new ArrayList<>();
		if (s != null) {
			String[] toks = s.trim().toLowerCase().split("\\s+");
			for (int i = 0; i < toks.length; i++) {
				String t = toks[i];
				if (t.equals("-fix") || t.equals("fix")) fix = true;
				else if (t.equals("-log") || t.equals("log")) log = true;
				else if (t.equals("-underground") || t.equals("underground") || t.equals("-ug")) underground = true;
				else if (t.equals("-compact") || t.equals("compact")) compact = true;
				else if (t.equals("-sort") || t.equals("sort")) {
					if (i + 1 < toks.length) { String v = normalizeSort(toks[i + 1]); if (v != null) { sort = v; i++; } }
				}
				else if (t.equals("-gap") || t.equals("gap")) {
					gap = 1; // "activated" with no number = 1 empty layer between bands
					if (i + 1 < toks.length) {
						try { gap = Integer.parseInt(toks[i + 1]); i++; } catch (NumberFormatException ignored) {}
					}
				}
				else if (t.equals("-batch") || t.equals("batch")) {
					if (i + 1 < toks.length) {
						try { batch = Integer.parseInt(toks[i + 1]); i++; } catch (NumberFormatException ignored) {}
					}
				}
				else if (!t.isBlank()) unknown.add(t);
			}
		}
		return new Opts(Math.max(0, batch), log, underground, compact, sort, Math.max(-1, gap), fix, unknown);
	}

	/** Map a -sort argument to a canonical mode, or null if it isn't one (so it's left for the next flag). */
	private static String normalizeSort(String v) {
		return switch (v) {
			case "alpha", "alphabetical", "a", "name" -> "alpha";
			case "size", "s", "length", "len" -> "size";
			case "type", "t" -> "type";
			case "none", "file", "n" -> "none";
			default -> null;
		};
	}

	/**
	 * Autocomplete for the push/deploy/fill flag string: suggests the flags you haven't typed yet,
	 * the -sort modes right after "-sort", and a few batch sizes after "-batch". Offsets
	 * to the current (last) token so a suggestion replaces just that token, not the whole line.
	 */
	private static CompletableFuture<Suggestions> suggestOpts(CommandContext<FabricClientCommandSource> ctx, SuggestionsBuilder b) {
		String remaining = b.getRemaining();
		int lastSpace = remaining.lastIndexOf(' ');
		String token = remaining.substring(lastSpace + 1).toLowerCase();   // what's being typed now
		String before = (lastSpace < 0) ? "" : remaining.substring(0, lastSpace).trim().toLowerCase();
		String prev = "";
		if (!before.isEmpty()) { String[] ps = before.split("\\s+"); prev = ps[ps.length - 1]; }
		SuggestionsBuilder ob = b.createOffset(b.getStart() + lastSpace + 1); // suggestions land on the current token

		if (prev.equals("-sort") || prev.equals("sort")) {
			for (String m : SORT_MODES) if (m.startsWith(token)) ob.suggest(m);
		} else if (prev.equals("-batch") || prev.equals("batch")) {
			for (String num : new String[]{"25", "50", "100"}) if (num.startsWith(token)) ob.suggest(num);
		} else if (prev.equals("-gap") || prev.equals("gap")) {
			for (String num : new String[]{"0", "1", "2", "-1"}) if (num.startsWith(token)) ob.suggest(num);
		} else {
			String typed = " " + remaining.toLowerCase() + " ";
			for (String fl : FLAGS) if (fl.startsWith(token) && !typed.contains(" " + fl + " ")) ob.suggest(fl);
		}
		return ob.buildFuture();
	}

	private static void backup() {
		Config cfg = DfVibeClient.config;
		if (!SyncService.tryLock()) { Chat.warn("Busy.", "A sync is already running. Stop it with /df end."); return; }
		Chat.info("Backing up the plot...");
		new Thread(() -> { try { SyncService.backup(cfg); } finally { SyncService.unlock(); } }, "df-backup").start();
	}

	/** /df end | cancel | stop - abort the running pull/push/deploy early. */
	private static void end() {
		if (SyncService.cancel()) Chat.warn("Stopping...", "The plot may be left partly changed.");
		else Chat.info("Nothing is running.");
	}

	/** /df end compile - stop a running scan but KEEP what it grabbed: the pull decompiles + writes the lines
	 *  collected so far (the rest stay missing - pull again for them). Lets you save partial progress. */
	private static void endCompile() {
		if (SyncService.endCompile()) {
			int n = dev.dfonline.codeclient.action.impl.ScanPlot.progressScanned();
			Chat.good("Finishing now, saving ~" + n + " lines.", "Pull again later to get the rest.");
		} else {
			Chat.info("No scan is running. (/df end compile saves partial progress during a /df pull.)");
		}
	}

	private static void backupLoad(String file) {
		Config cfg = DfVibeClient.config;
		if (!SyncService.tryLock()) { Chat.warn("Busy.", "A sync is already running. Stop it with /df end."); return; }
		Chat.info("Restoring backup '" + file + "' (clears the plot first)...");
		new Thread(() -> { try { SyncService.backupLoad(cfg, file); } finally { SyncService.unlock(); } }, "df-restore").start();
	}

	private static CompletableFuture<Suggestions> suggestBackups(CommandContext<FabricClientCommandSource> ctx, SuggestionsBuilder b) {
		Config cfg = DfVibeClient.config;
		String remaining = b.getRemaining().toLowerCase();
		String prefix = cfg.activeProject.isBlank() ? "" : (cfg.activeProject + "-").toLowerCase();
		if (!cfg.plotsRoot.isBlank()) {
			Path bdir = Path.of(cfg.plotsRoot).resolve(".df-backups");
			if (Files.isDirectory(bdir)) {
				try (Stream<Path> s = Files.list(bdir)) {
					s.map(p -> p.getFileName().toString())
						.filter(n -> n.endsWith(".txt"))
						.filter(n -> prefix.isEmpty() || n.toLowerCase().startsWith(prefix))
						.filter(n -> n.toLowerCase().startsWith(remaining))
						.sorted(java.util.Comparator.reverseOrder())
						.limit(20)
						.forEach(b::suggest);
				} catch (Exception ignored) {
				}
			}
		}
		return b.buildFuture();
	}
}
