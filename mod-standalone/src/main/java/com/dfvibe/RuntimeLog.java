package com.dfvibe;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * /df log start|stop|status - the runtime feedback channel. While active, every game-chat line
 * (DF output, %var prints, DF error messages) is appended - timestamped, formatting stripped -
 * to {@code <project>/.df-runtime.log}, so the AI can read what the code actually did at runtime.
 * Only logs while on the active project's plot (when both plot ids are known); auto-stops on
 * disconnect.
 */
public final class RuntimeLog {
	private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss");
	private static volatile Path file;     // null = inactive
	private static volatile String plotId; // the project's recorded plot; skip chat seen on other plots

	private RuntimeLog() {}

	/** Register the chat + disconnect listeners. Call once at client init. */
	public static void install() {
		ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
			if (!overlay) append(message.getString());
			return true;
		});
		ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> file = null);
	}

	public static void start(Config cfg) {
		Path dir = cfg.activeDir();
		if (dir == null || !Files.isDirectory(dir)) {
			Chat.error("No active project folder. /df use <name> or /df pull <name> first.");
			return;
		}
		plotId = SyncService.readMetaPlotId(dir);
		file = dir.resolve(".df-runtime.log");
		Chat.good("Runtime log on" + (plotId != null ? " (this plot only)" : "")
				+ ". Stops on /df debug log stop, or disconnect.",
				Chat.openFile("[open log]", file.toString()));
	}

	public static void stop() {
		Path f = file;
		if (f == null) { Chat.info("Runtime log is not running."); return; }
		file = null;
		Chat.good("Runtime log off.", Chat.openFile("[open log]", f.toString()));
	}

	public static void status() {
		Path f = file;
		if (f == null) Chat.info("Runtime log: off.", Chat.runCmd("[start]", "/df debug log start", "begin capturing"));
		else Chat.info("Runtime log: on.", Chat.openFile("[open log]", f.toString()));
	}

	/** True while the runtime log is capturing (for /df status). */
	public static boolean isOn() { return file != null; }

	private static void append(String line) {
		Path f = file;
		if (f == null) return;
		// Only log while on the project's plot (when both sides are known).
		String want = plotId;
		PlotDetector.PlotInfo here = PlotDetector.cached();
		if (want != null && here != null && !want.equals(here.id())) return;
		try {
			Files.writeString(f, "[" + LocalDateTime.now().format(TS) + "] " + line + System.lineSeparator(),
					StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (Exception ignored) {
		}
	}
}
