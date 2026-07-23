package com.dfvibe;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Diagnostic log written to {@code <workspace>/.df-log.txt}.
 *
 * - Op headers, results, and errors (with stack traces) are ALWAYS written, so a failure
 *   can be diagnosed after the fact.
 * - Verbose steps + the raw WebSocket exchange are written only when an op is run with the
 *   {@code -log} flag (so normal use doesn't spam the file).
 */
public final class Log {
	private static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss");
	private static volatile boolean verbose = false;
	private static volatile Path file;

	private Log() {}

	/** Start logging for an op. verboseOn = the -log flag was passed. */
	public static void begin(Config cfg, String op, boolean verboseOn) {
		verbose = verboseOn;
		file = null;
		try {
			if (cfg != null && cfg.plotsRoot != null && !cfg.plotsRoot.isBlank()) {
				file = Path.of(cfg.plotsRoot).resolve(".df-log.txt");
			}
		} catch (Exception ignored) {
		}
		write("==== " + op + (verboseOn ? "  [-log]" : "") + " ====");
	}

	public static boolean isVerbose() { return verbose; }

	public static Path file() { return file; }

	/** Verbose detail (WS exchange, per-step) — only when -log is on. */
	public static void step(String msg) { if (verbose) write(msg); }

	/** Always written (results, notable notes). */
	public static void info(String msg) { write(msg); }

	/** Always written, with the full stack trace. */
	public static void error(String msg, Throwable t) {
		String trace = "";
		if (t != null) {
			StringWriter sw = new StringWriter();
			t.printStackTrace(new PrintWriter(sw));
			trace = System.lineSeparator() + sw;
		}
		write("ERROR: " + msg + trace);
	}

	private static void write(String msg) {
		Path f = file;
		if (f == null) return;
		try {
			Files.writeString(f, "[" + LocalDateTime.now().format(TS) + "] " + msg + System.lineSeparator(),
					StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		} catch (IOException ignored) {
		}
	}
}
