package com.dfvibe;

import dev.dfonline.codeclient.action.impl.PlaceTemplates;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

import java.util.function.IntSupplier;

/**
 * A live progress bar rendered in the player's ACTION BAR (above the hotbar) during a place op
 * (deploy / push / fill / restore), with a moving-average ETA.
 *
 * The placer ({@link PlaceTemplates}) ticks a global counter as each line seats or gives up; this
 * class just reads it on a 250ms timer and redraws, so the bar advances smoothly even within one
 * big batch and keeps counting across batches. The ETA is projected from OBSERVED throughput
 * (elapsed / placed), so it self-corrects for batching instead of guessing a fixed per-line
 * cost - whatever the real rate turns out to be, the estimate follows it.
 *
 * Chat output (the "batch 2/8 ok" lines) is untouched; this is a separate, transient HUD.
 */
public final class Progress {
	private Progress() {}

	private static final int BAR_WIDTH = 24;

	private static volatile Thread renderer;
	private static volatile boolean active;
	private static volatile String task = "";
	private static volatile IntSupplier doneFn = () -> 0;
	private static volatile IntSupplier totalFn = () -> 0;

	/** Placement progress (the original use): reads the placer's per-line counter. */
	public static void start(String taskName, int totalLines) {
		PlaceTemplates.resetProgress();
		start(taskName, PlaceTemplates::progressPlaced, () -> totalLines);
	}

	/**
	 * Begin a progress session driven by arbitrary done/total suppliers (so scanning can show the same
	 * bar as placing). {@code total} is a supplier too - the scan discovers lines as it flies, so its
	 * total grows; the bar reads the live value each frame instead of a fixed count.
	 */
	public static synchronized void start(String taskName, IntSupplier done, IntSupplier total) {
		stopRenderer();
		task = taskName;
		doneFn = done;
		totalFn = total;
		active = true;
		final long startMs = System.currentTimeMillis();
		Thread t = new Thread(() -> {
			while (active) {
				render(curDone(), startMs);
				try {
					Thread.sleep(250);
				} catch (InterruptedException e) {
					return;
				}
			}
		}, "df-progress");
		t.setDaemon(true);
		renderer = t;
		t.start();
	}

	private static int curTotal() { return Math.max(0, totalFn.getAsInt()); }
	private static int curDone() { return Math.min(doneFn.getAsInt(), curTotal()); }

	/** End the session: stop the loop and draw one final frame at the count actually reached. */
	public static synchronized void finish() {
		active = false;
		stopRenderer();
		int total = curTotal();
		int done = curDone();
		boolean allDone = total > 0 && done >= total;
		String tail = allDone ? "done" : "stopped";
		send(bar(task, done, total, tail, allDone ? Formatting.GREEN : Formatting.YELLOW));
	}

	private static void stopRenderer() {
		Thread t = renderer;
		if (t != null) {
			t.interrupt();
			renderer = null;
		}
	}

	private static void render(int done, long startMs) {
		long elapsed = System.currentTimeMillis() - startMs;
		int total = curTotal();
		// Prefer the movement-aware estimate when a scan is modelling (it accounts for the fly-out + sweep, so it
		// shows a real countdown even before the first line is grabbed). Fall back to the observed ms/line rate
		// (used by place ops, where there's no Eta model).
		long etaMs = Eta.isActive() ? Eta.remainingMs() : -1;
		String tail;
		if (SyncService.PAUSED) tail = "paused - /df end continue";
		else if (total > 0 && done >= total) tail = "finishing...";
		else if (etaMs >= 0) {
			String ph = Eta.phase();                           // "flying out" vs "scanning"
			tail = "~" + fmtDuration(etaMs) + " left" + (ph.isEmpty() ? "" : " (" + ph + ")");
		}
		else if (done <= 0 || elapsed < 600) tail = "estimating...";
		else {
			double perLine = (double) elapsed / done;          // observed ms/line so far
			long remainMs = (long) (perLine * (total - done));
			tail = "~" + fmtDuration(remainMs) + " left";
		}
		send(bar(task, done, total, tail, Formatting.AQUA));
	}

	private static Text bar(String label, int done, int total, String tail, Formatting color) {
		int pct = total > 0 ? (int) Math.round(100.0 * done / total) : 0;
		int filled = Math.round(BAR_WIDTH * pct / 100f);
		if (filled > BAR_WIDTH) filled = BAR_WIDTH;
		StringBuilder b = new StringBuilder();
		for (int i = 0; i < BAR_WIDTH; i++) b.append(i < filled ? '█' : '░'); // █ / ░ (block elements - in MC's font)
		MutableText out = Text.literal(label + "  ").formatted(color);
		out.append(Text.literal("[").formatted(Formatting.DARK_GRAY));        // ASCII caps = always render
		out.append(Text.literal(b.toString()).formatted(color));
		out.append(Text.literal("]").formatted(Formatting.DARK_GRAY));
		out.append(Text.literal("  " + pct + "%  ").formatted(Formatting.WHITE));
		out.append(Text.literal(done + "/" + total).formatted(Formatting.GRAY));
		out.append(Text.literal("   " + tail).formatted(Formatting.YELLOW));
		return out;
	}

	/** "45s", "1m20s", "2h5m" - compact, only the two largest units. */
	private static String fmtDuration(long ms) {
		long s = Math.max(0, ms / 1000);
		if (s < 60) return s + "s";
		long m = s / 60, rs = s % 60;
		if (m < 60) return m + "m" + (rs > 0 ? rs + "s" : "");
		long h = m / 60, rm = m % 60;
		return h + "h" + (rm > 0 ? rm + "m" : "");
	}

	/** Action bar = sendMessage(text, true). Marshalled onto the client thread. */
	private static void send(Text t) {
		MinecraftClient mc = MinecraftClient.getInstance();
		mc.execute(() -> {
			if (mc.player != null) mc.player.sendMessage(t, true);
		});
	}
}
