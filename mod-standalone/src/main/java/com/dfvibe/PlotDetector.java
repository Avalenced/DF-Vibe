package com.dfvibe;

import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Figures out which DiamondFire plot you're on by reading the "/locate" output:
 *
 *   You are currently coding on:
 *   -> parkour practice [173110]
 *   -> Owner: <name> [Whitelisted]
 *   -> Server: Node 7
 *
 * The plot id in brackets (173110) is the stable identity. We passively cache the
 * latest such message (DF prints it on /locate and on entering dev mode), and
 * detect() actively runs /locate then waits for a fresh capture.
 *
 * CodeClient's API does NOT expose plot identity, so this client-side read is the
 * only reliable source. The command can be changed via Config.locateCommand if DF
 * ever renames it.
 */
public final class PlotDetector {
	private PlotDetector() {}

	public record PlotInfo(String id, String name, String owner, String server, long capturedAt) {}

	// "-> <name> [<id>]"  (the arrow is U+2192)
	private static final Pattern NAME_ID = Pattern.compile("→\\s*(.+?)\\s*\\[(\\d+)\\]");
	private static final Pattern OWNER = Pattern.compile("Owner:\\s*(\\S+)");
	private static final Pattern SERVER = Pattern.compile("Server:\\s*(.+)");

	private static volatile PlotInfo last;
	private static volatile long suppressUntil = 0;

	/** Register the passive chat listener. Call once at client init. */
	public static void install() {
		ClientReceiveMessageEvents.ALLOW_GAME.register((message, overlay) -> {
			if (overlay) return true;
			boolean coding = parse(message);
			// Hide the /locate output that WE triggered during detect() (don't spam chat).
			return !(coding && System.currentTimeMillis() < suppressUntil);
		});
	}

	/** Returns true if this was a "currently coding on" message (parsed for the plot id). */
	private static boolean parse(Text message) {
		String s = message.getString();
		if (s == null || !s.contains("currently coding on")) return false;
		String name = null, id = null, owner = null, server = null;
		Matcher m = NAME_ID.matcher(s);
		if (m.find()) { name = m.group(1).trim(); id = m.group(2); }
		Matcher mo = OWNER.matcher(s);
		if (mo.find()) owner = mo.group(1).trim();
		Matcher ms = SERVER.matcher(s);
		if (ms.find()) server = ms.group(1).trim();
		if (id != null) last = new PlotInfo(id, name, owner, server, System.currentTimeMillis());
		return true;
	}

	public static PlotInfo cached() {
		return last;
	}

	/**
	 * Actively run /locate and wait for a fresh result. Returns null if the plot
	 * identity couldn't be read within timeoutMs. Safe to call from a worker thread.
	 */
	public static PlotInfo detect(String locateCommand, long timeoutMs) {
		long t0 = System.currentTimeMillis();
		suppressUntil = t0 + timeoutMs + 1000; // hide the /locate output we're about to cause
		MinecraftClient mc = MinecraftClient.getInstance();
		mc.execute(() -> {
			if (mc.player != null && mc.player.networkHandler != null) {
				mc.player.networkHandler.sendChatCommand(locateCommand);
			}
		});
		long deadline = t0 + timeoutMs;
		while (System.currentTimeMillis() < deadline) {
			PlotInfo info = last;
			if (info != null && info.capturedAt() >= t0) return info;
			try {
				Thread.sleep(100);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				break;
			}
		}
		return null;
	}
}
