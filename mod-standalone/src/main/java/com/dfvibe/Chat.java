package com.dfvibe;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.text.Style;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * Thread-safe chat feedback for the /df tooling. Sync ops run on a worker thread, so all
 * output is marshalled back onto the client thread via MinecraftClient.execute().
 *
 * Keep the visible line short. Two ways to add detail without cluttering it:
 *   - a hover tooltip on the whole line:  Chat.warn("Busy.", "A sync is already running.");
 *   - trailing clickable parts:           Chat.good("Pulled 42 lines.", Chat.openFile("[open]", dir));
 */
public final class Chat {
	private Chat() {}

	private static void send(Text t) {
		MinecraftClient mc = MinecraftClient.getInstance();
		mc.execute(() -> {
			if (mc.player != null) mc.player.sendMessage(t, false);
		});
	}

	/** The "[DF]" prefix - aqua, no bold, no icon. */
	private static MutableText prefix() {
		return Text.literal("[DF] ").formatted(Formatting.AQUA);
	}

	private static void emit(String msg, Formatting color, String hover, Text[] extra) {
		MutableText body = Text.literal(msg).formatted(color);
		if (hover != null && !hover.isBlank())
			body.setStyle(body.getStyle().withHoverEvent(
					new HoverEvent.ShowText(Text.literal(hover).formatted(Formatting.GRAY))));
		MutableText out = prefix().append(body);
		if (extra != null) for (Text e : extra) out.append(Text.literal(" ")).append(e);
		send(out);
	}

	public static void info(String msg, Text... extra)  { emit(msg, Formatting.WHITE, null, extra); }
	public static void good(String msg, Text... extra)  { emit(msg, Formatting.GREEN, null, extra); }
	public static void warn(String msg, Text... extra)  { emit(msg, Formatting.GOLD,  null, extra); }
	public static void error(String msg, Text... extra) { emit(msg, Formatting.RED,   null, extra); }

	/** Same levels, but with a hover tooltip on the whole line (for detail you don't want on-screen). */
	public static void info(String msg, String hover)  { emit(msg, Formatting.WHITE, hover, null); }
	public static void good(String msg, String hover)  { emit(msg, Formatting.GREEN, hover, null); }
	public static void warn(String msg, String hover)  { emit(msg, Formatting.GOLD,  hover, null); }
	public static void error(String msg, String hover) { emit(msg, Formatting.RED,   hover, null); }

	/** Send a fully-built component verbatim, with the [DF] prefix but no level color. */
	public static void raw(Text body) { send(prefix().append(body)); }

	/** Send a line with no [DF] prefix (for multi-line blocks like /df help). */
	public static void plain(Text body) { send(body); }

	// ---- clickable building blocks -------------------------------------------------

	/** Clickable text that RUNS a command when clicked (underlined aqua, hover shows the command). */
	public static MutableText runCmd(String label, String command, String hover) {
		return Text.literal(label).setStyle(Style.EMPTY
				.withColor(Formatting.AQUA).withUnderline(true)
				.withClickEvent(new ClickEvent.RunCommand(command))
				.withHoverEvent(new HoverEvent.ShowText(Text.literal(
						(hover == null || hover.isBlank() ? "Click to run " : hover + "\n") + command)
						.formatted(Formatting.GRAY))));
	}

	public static MutableText runCmd(String label, String command) { return runCmd(label, command, null); }

	/** Clickable text that PUTS a command in the chat box (so you can edit before sending). */
	public static MutableText suggest(String label, String command, String hover) {
		return Text.literal(label).setStyle(Style.EMPTY
				.withColor(Formatting.AQUA).withUnderline(true)
				.withClickEvent(new ClickEvent.SuggestCommand(command))
				.withHoverEvent(new HoverEvent.ShowText(Text.literal(
						(hover == null || hover.isBlank() ? "Click to type " : hover + "\n") + command)
						.formatted(Formatting.GRAY))));
	}

	/** Clickable text that OPENS a file/folder in the OS (for log + backup paths). */
	public static MutableText openFile(String label, String path) {
		return Text.literal(label).setStyle(Style.EMPTY
				.withColor(Formatting.GRAY).withUnderline(true)
				.withClickEvent(new ClickEvent.OpenFile(path))
				.withHoverEvent(new HoverEvent.ShowText(Text.literal("Open " + path).formatted(Formatting.GRAY))));
	}
}
