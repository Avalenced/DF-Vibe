package com.dfvibe;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.File;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs the Python codec (dfpy.py) as a subprocess. This is the only thing that
 * understands the DiamondFire template format / .py mapping; the mod just shuttles
 * JSON in and out.
 *
 *   decompile : {"lines":[b64gzip,...]} -> {"files":[{id,type,key,file,source},...]}
 *   recompile : {"files":[{file,source},...]} -> {"lines":[b64gzip,...],"placed":[...]}
 */
public class Codec {

	private final Config cfg;

	public Codec(Config cfg) {
		this.cfg = cfg;
	}

	public JsonObject run(String command, String stdinJson) throws Exception {
		Path codecDir = Path.of(cfg.codecDir);
		Path script = codecDir.resolve("dfpy.py");
		// Fail fast with a fixable message instead of a 120s "codec timed out" mystery.
		if (!java.nio.file.Files.isRegularFile(script)) {
			throw new RuntimeException("dfpy.py not found at " + script + " - point /df codec at the folder containing dfpy.py.");
		}

		List<String> cmd = new ArrayList<>();
		cmd.add(cfg.pythonPath);
		cmd.add(script.toString());
		cmd.add(command);

		ProcessBuilder pb = new ProcessBuilder(cmd);
		pb.directory(new File(cfg.codecDir));
		Process p;
		try {
			p = pb.start();
		} catch (java.io.IOException e) {
			throw new RuntimeException("couldn't run '" + cfg.pythonPath + "' (" + e.getMessage()
					+ ") - set /df python to your Python executable (e.g. python3 or a full path).");
		}

		// Drain stdout/stderr on separate threads so a full pipe can't deadlock us.
		AtomicReference<String> errOut = new AtomicReference<>("");
		Thread errThread = new Thread(() -> {
			try {
				errOut.set(new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
			} catch (Exception ignored) {
			}
		});
		errThread.start();
		AtomicReference<String> stdOut = new AtomicReference<>("");
		Thread outThread = new Thread(() -> {
			try {
				stdOut.set(new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
			} catch (Exception ignored) {
			}
		});
		outThread.start();

		// Feed stdin on a thread too: a codec that never reads it would otherwise block the write
		// forever (pipe full) and dodge the timeout below.
		Thread inThread = new Thread(() -> {
			try (OutputStream os = p.getOutputStream()) {
				os.write(stdinJson.getBytes(StandardCharsets.UTF_8));
			} catch (Exception e) {
				// The codec died before reading stdin - the exit-code/stderr path reports the real
				// failure; record this so the root cause is traceable in the log.
				Log.info("codec '" + command + "': stdin write failed (" + e.getMessage() + ")");
			}
		});
		inThread.start();

		// Generous cap: a hung python must not wedge the sync lock until the game restarts.
		if (!p.waitFor(120, TimeUnit.SECONDS)) {
			p.destroyForcibly();
			throw new RuntimeException("codec '" + command + "' timed out after 120s and was killed - "
					+ "is python hanging? Check the codec manually. " + errOut.get());
		}
		int code = p.exitValue();
		outThread.join(2000);
		errThread.join(2000);
		String out = stdOut.get();

		if (out.isBlank()) {
			throw new RuntimeException("codec produced no output (exit " + code + "). " + errOut.get());
		}
		JsonObject obj = JsonParser.parseString(out).getAsJsonObject();
		if (obj.has("error")) {
			throw new RuntimeException(obj.get("error").getAsString());
		}
		if (code != 0) {
			throw new RuntimeException("codec exit " + code + ": " + errOut.get());
		}
		return obj;
	}
}
