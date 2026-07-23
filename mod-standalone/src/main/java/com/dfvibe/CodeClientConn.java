package com.dfvibe;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * Minimal client for CodeClient's local WebSocket API (ws://localhost:31375).
 *
 * Protocol (reverse-engineered and validated against the real mod):
 *   auth : send "scopes read_plot write_code" -> approve /auth in-game ->
 *          server sends "auth"; send "scopes" -> reply is granted scopes.
 *   scan : send "scan" -> ONE message, newline-separated; each token is
 *          base64(gzip(templateJSON)) for one code line.
 *   place: "place" -> "place <token>" per line -> "place swap" -> "place go"
 *          -> reply contains "done" (or "error"/"unauth"). swap matches by
 *          header and REPLACES, so it is idempotent (no duplicates).
 *
 * Text frames may arrive fragmented; we reassemble per message (last==true).
 */
public class CodeClientConn implements AutoCloseable {

	private static final class Sink implements WebSocket.Listener {
		final BlockingQueue<String> messages = new LinkedBlockingQueue<>();
		final StringBuilder partial = new StringBuilder();
		volatile String closed;

		@Override public void onOpen(WebSocket ws) { ws.request(1); }

		@Override public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
			partial.append(data);
			if (last) {
				messages.add(partial.toString());
				partial.setLength(0);
			}
			ws.request(1);
			return null;
		}

		@Override public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
			closed = "closed (" + code + "): " + reason;
			return null;
		}

		@Override public void onError(WebSocket ws, Throwable error) {
			closed = "error: " + error;
		}
	}

	private final WebSocket ws;
	private final Sink sink;

	private CodeClientConn(WebSocket ws, Sink sink) {
		this.ws = ws;
		this.sink = sink;
	}

	public static CodeClientConn connect(int port) throws Exception {
		// CodeClient may bind to IPv4 or IPv6 localhost; try the common forms in order.
		String[] hosts = {"127.0.0.1", "[::1]", "localhost"};
		Exception last = null;
		for (String host : hosts) {
			try {
				Sink sink = new Sink();
				WebSocket ws = HttpClient.newHttpClient().newWebSocketBuilder()
						.connectTimeout(Duration.ofSeconds(4))
						.buildAsync(URI.create("ws://" + host + ":" + port), sink)
						.get(6, TimeUnit.SECONDS);
				return new CodeClientConn(ws, sink);
			} catch (Exception e) {
				last = e;
			}
		}
		throw new RuntimeException("can't reach CodeClient on port " + port
				+ " - is CodeClient running with its API enabled? (try /codeclient in-game)", last);
	}

	private void send(String msg) {
		if (Log.isVerbose()) Log.step("-> " + (msg.length() > 60 ? msg.substring(0, 40) + "...[" + msg.length() + " chars]" : msg));
		ws.sendText(msg, true).join();
	}

	private String poll(long sec) throws InterruptedException {
		String m = sink.messages.poll(sec, TimeUnit.SECONDS);
		if (m != null && Log.isVerbose()) Log.step("<- " + (m.length() > 200 ? m.substring(0, 200) + "...[" + m.length() + " chars]" : m));
		return m;
	}

	private String awaitEquals(String want, long perMsgSec, int maxMsgs) throws InterruptedException {
		for (int i = 0; i < maxMsgs; i++) {
			String m = poll(perMsgSec);
			if (m == null) return null;
			if (m.trim().equalsIgnoreCase(want)) return m;
		}
		return null;
	}

	private String awaitContains(String[] needles, long perMsgSec, int maxMsgs) throws InterruptedException {
		for (int i = 0; i < maxMsgs; i++) {
			String m = poll(perMsgSec);
			if (m == null) return null;
			String low = m.toLowerCase();
			for (String n : needles) if (low.contains(n)) return m;
		}
		return null;
	}

	/** Ask CodeClient for the current mode (spawn/play/build/code). null if no reply. */
	public String mode() throws InterruptedException {
		send("mode");
		String m = poll(8);
		return m == null ? null : m.trim();
	}

	/**
	 * Request the given (space-separated) scopes and confirm all are granted.
	 * Fast-path if already authed; otherwise prompts and waits ~60s for /auth.
	 * e.g. auth("read_plot write_code") or auth("read_plot write_code clear_plot").
	 */
	/** All scopes, granted in one /auth so pull/push/deploy never re-prompt.
	 *  movement covers the "mode" query (the API gates it on AuthScope.MOVEMENT). */
	private static final String SCOPES = "read_plot write_code clear_plot movement";

	/**
	 * Authorize this connection. Uses a saved session token to skip /auth entirely;
	 * only prompts /auth when there's no valid token (once per game launch, since
	 * CodeClient clears tokens on restart). After a fresh /auth we fetch + persist a
	 * new token so subsequent commands this session are silent.
	 */
	public boolean auth(Config cfg) throws InterruptedException {
		// 1. Try a saved token — no user interaction.
		if (cfg.apiToken != null && !cfg.apiToken.isBlank()) {
			send("token " + cfg.apiToken);
			awaitEquals("auth", 6, 4);
			send("scopes");
			if (hasScopes(poll(10), SCOPES)) return true;
			// token is stale (game was restarted) -> fall through to /auth
		}

		// 2. Request scopes; this is what prompts /auth.
		send("scopes " + SCOPES);
		awaitEquals("auth", 5, 4); // quiet auto-grant window
		send("scopes");
		String granted = poll(10);
		if (!hasScopes(granted, SCOPES)) {
			Chat.warn("Waiting for the bundled API to authorize this session...");
			awaitEquals("auth", 60, 12);
			send("scopes");
			granted = poll(10);
		}
		if (!hasScopes(granted, SCOPES)) return false;

		// 3. Grab + persist a token so we never prompt again until the game restarts.
		send("token");
		String tok = poll(10);
		if (tok != null && !tok.isBlank() && !tok.trim().equalsIgnoreCase("auth") && !tok.trim().contains(" ")) {
			cfg.apiToken = tok.trim();
			cfg.save();
		}
		return true;
	}

	private static boolean hasScopes(String granted, String requested) {
		if (granted == null) return false;
		for (String s : requested.split("\\s+")) {
			if (!s.isBlank() && !granted.contains(s)) return false;
		}
		return true;
	}

	/** Clear the codespace via {@code /plot clear} (needs clear_plot scope). Returns true if it cleared (we're
	 *  the plot owner); false if DF refused it because we're NOT the owner - the caller then falls back to
	 *  {@link #clearByScan} (break every line). Throws if the scope was missing. */
	public boolean clear() throws InterruptedException {
		send("clear");
		while (true) {
			String m = poll(120); // wait for the API's status (it navigates the clear GUI / catches the error first)
			if (m == null) { // no status in 2 min: assume it cleared (placement skip-protects anyway) - but say so
				Chat.warn("No clear confirmation after 2 min - assuming the plot cleared and placing anyway. If old lines survive, /df fill afterwards.");
				return true;
			}
			String low = m.toLowerCase();
			if (low.contains("unauth"))
				throw new RuntimeException("clear was rejected (unauthed - the API didn't grant clear_plot)");
			if (low.contains("notowner")) return false; // not the owner - caller breaks instead
			if (low.startsWith("clear")) return true;   // "clear ok"
			// ignore anything unrelated and keep waiting for the status
		}
	}

	/** No-owner clear: read every line (the returned tokens are a BACKUP of the old code) AND break each one as
	 *  it's read, so the codespace ends up empty without {@code /plot clear}. Same streaming protocol as scan. */
	public List<String> clearByScan(java.util.function.Consumer<String> onLine) throws InterruptedException {
		return scanWith("scanclear", onLine);
	}

	/** Capture a region's live state (blocks + signs/heads/containers) to the schem .capture/ dir. The mod
	 *  writes the files itself; we just drive the WS verb, relay heartbeats, and return the final status line
	 *  (e.g. "capture done blocks 1234 ..."), or null if the capture fell silent. */
	public String capture(java.util.function.Consumer<String> onHeartbeat) throws InterruptedException {
		send("capture");
		while (true) {
			String m = poll(180); // 180s with not even a heartbeat = the capture is dead
			if (m == null) return null;
			if (m.startsWith("scan-progress")) { // keep-alive
				if (onHeartbeat != null) try { onHeartbeat.accept(m); } catch (Throwable ignored) {}
				continue;
			}
			if (m.startsWith("capture done")) return m.substring("capture done".length()).trim();
			if (m.toLowerCase().contains("unauth")) throw new RuntimeException("capture was rejected (unauthed)");
			// ignore anything unrelated and keep waiting
		}
	}

	/** Scan the plot. Returns one base64+gzip token per code line ("empty" sentinel = zero lines). */
	public List<String> scan() throws InterruptedException {
		return scan(false, false, null);
	}

	public List<String> scan(boolean deep) throws InterruptedException {
		return scan(deep, false, null);
	}

	/**
	 * Scan the plot. {@code deep} = rebuild EVERY line from its physical blocks (the salvage path) instead
	 * of reading DF templates. {@code save} = save-as-you-go: the mod streams each line as it's grabbed
	 * ("scan-line &lt;token&gt;") and {@code onLine} is invoked per token so the caller can checkpoint progress
	 * to disk; if the scan then dies mid-way, the streamed tokens collected so far are returned instead of
	 * nothing, so the partial result isn't lost.
	 */
	public List<String> scan(boolean deep, boolean save, java.util.function.Consumer<String> onLine) throws InterruptedException {
		return scanWith(deep ? (save ? "scandeep save" : "scandeep") : (save ? "scan save" : "scan"), onLine);
	}

	/** Shared scan/clear loop: send {@code verb}, then collect the streamed/batched line tokens (ignoring the
	 *  ~20s keep-alive pings) until the result arrives or the scan falls silent. */
	private List<String> scanWith(String verb, java.util.function.Consumer<String> onLine) throws InterruptedException {
		send(verb);
		// No TOTAL timeout - a -chests scan can run 15-20 min. The scan pings "scan-progress N/M" every
		// ~20s; we ignore those and keep waiting. Only give up after a long SILENCE (no ping = scan died).
		List<String> streamed = new ArrayList<>();
		while (true) {
			String m = poll(180); // 180s with not even a heartbeat = the scan is dead
			if (m == null) return streamed; // died: keep whatever -save streamed (empty for a normal scan)
			if (m.startsWith("scan-progress")) continue; // keep-alive; still scanning
			if (m.startsWith("scan-line ")) {            // -save: a single line just landed
				String tok = m.substring("scan-line ".length()).trim();
				if (!tok.isEmpty()) {
					streamed.add(tok);
					if (onLine != null) try { onLine.accept(tok); } catch (Throwable ignored) {}
				}
				continue;
			}
			List<String> out = new ArrayList<>();
			if (!m.trim().equalsIgnoreCase("empty")) {
				for (String s : m.split("\n")) {
					String t = s.trim();
					if (!t.isEmpty()) out.add(t);
				}
			}
			return out;
		}
	}

	/** Place lines in swap mode (replace-by-header, idempotent). Returns the server's result line. */
	public String placeSwap(List<String> lines) throws InterruptedException {
		send("place");
		for (String t : lines) send("place " + t);
		send("place swap");
		send("place go");
		return awaitContains(new String[]{"done", "error", "unauth", "fail"}, 300, 32);
	}

	/**
	 * Place templates at PRECOMPUTED absolute positions (swap mode). The layout is decided up front by
	 * {@link Layout}, so chunking a big deploy across several of these calls can't make chunks overlap.
	 * Returns the server's result line.
	 */
	public String placeAt(List<Layout.Placement> placements) throws InterruptedException {
		send("placepos");
		for (Layout.Placement p : placements) send("placepos " + p.x() + " " + p.y() + " " + p.z() + " " + p.token());
		send("placepos go");
		return awaitContains(new String[]{"done", "error", "unauth", "fail"}, 300, 32);
	}

	public String closedReason() {
		return sink.closed;
	}

	@Override
	public void close() {
		try {
			ws.sendClose(WebSocket.NORMAL_CLOSURE, "bye");
		} catch (Exception ignored) {
		}
	}
}
