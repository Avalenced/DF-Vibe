package com.dfvibe;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.GZIPInputStream;

/**
 * Decode a scanned/compiled code-line token to measure its placed length.
 *
 * A token is base64(gzip(templateJSON)), templateJSON = {"blocks":[...]}.
 * DiamondFire places each code block over 2 units of length and each bracket over 1,
 * so "Length: N Blocks" = (code blocks x2) + (brackets x1). A line longer than the
 * plot's codeLength runs off the plot edge when placed (the placer can't handle that),
 * so we measure here and refuse to place over-long lines.
 */
public final class Templates {
	private Templates() {}

	/** Max token length we'll ever send in one template item. The item's codetemplatedata NBT string is
	 *  ~token+75 chars, and MC's packet encoder caps a single NBT string at 65535 bytes (writeUTF) - over
	 *  that, sending the item DISCONNECTS the client (netty EncoderException). Headroom for the wrapper. */
	public static final int MAX_TOKEN = 65000;

	/**
	 * Split one over-{@link #MAX_TOKEN} line token into several placeable segment tokens carrying the SAME
	 * blocks in the same order. Placed contiguously along one physical line they rebuild the ORIGINAL line
	 * 1-1 - byte-identical code, delivered in parts (exactly how a player merges templates mid-line). This
	 * is how a line DiamondFire itself refuses to hand over whole (pull salvages it from blocks) gets back
	 * INTO a plot without modifying the game's code. Returns null if the token can't be decoded or one
	 * single block is itself over the cap (nothing left to split).
	 */
	public static java.util.List<String> split(String token) {
		try {
			byte[] gz = Base64.getDecoder().decode(token.trim());
			byte[] json;
			try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
				json = in.readAllBytes();
			}
			JsonArray blocks = JsonParser.parseString(new String(json, StandardCharsets.UTF_8))
					.getAsJsonObject().getAsJsonArray("blocks");
			if (blocks == null || blocks.size() < 2) return null;
			java.util.List<String> out = new java.util.ArrayList<>();
			JsonArray cur = new JsonArray();
			long curRaw = 0; // rough raw-JSON bytes in cur; only re-encode once this nears the cap
			for (var el : blocks) {
				String bj = el.toString();
				cur.add(el);
				curRaw += bj.length() + 1;
				// raw < 40KB can NEVER encode over 65535 (gzip <= raw + ~0.1% + 20, b64 = 4/3) - skip the check.
				if (curRaw < 40_000) continue;
				if (encodeBlocks(cur).length() <= MAX_TOKEN) continue;
				cur.remove(cur.size() - 1); // close the segment WITHOUT this block
				if (cur.size() == 0) return null; // one block alone is over the cap - can't split further
				out.add(encodeBlocks(cur));
				cur = new JsonArray();
				cur.add(el);
				curRaw = bj.length() + 1;
				if (curRaw >= 40_000 && encodeBlocks(cur).length() > MAX_TOKEN) return null;
			}
			if (cur.size() > 0) out.add(encodeBlocks(cur));
			return out.size() >= 2 ? out : null;
		} catch (Exception e) {
			return null;
		}
	}

	/** gzip+base64 a {"blocks":[...]} template - the exact token shape the placer/codec use. */
	private static String encodeBlocks(JsonArray blocks) throws java.io.IOException {
		JsonObject root = new JsonObject();
		root.add("blocks", blocks);
		byte[] raw = root.toString().getBytes(StandardCharsets.UTF_8);
		java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
		try (java.util.zip.GZIPOutputStream gz = new java.util.zip.GZIPOutputStream(baos)) { gz.write(raw); }
		return Base64.getEncoder().encodeToString(baos.toByteArray());
	}

	/**
	 * Layout rank of a line by its starter block: player event (0), game event (1),
	 * entity event (2), function (3), process (4), unknown (9). Placement is sorted by this
	 * so the codespace reads events-first (lowest layer), then functions, then processes.
	 * Unknown sorts last and never blocks placement.
	 */
	public static int lineRank(String token) {
		try {
			byte[] gz = Base64.getDecoder().decode(token.trim());
			byte[] json;
			try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
				json = in.readAllBytes();
			}
			JsonObject root = JsonParser.parseString(new String(json, StandardCharsets.UTF_8)).getAsJsonObject();
			JsonArray blocks = root.getAsJsonArray("blocks");
			if (blocks == null || blocks.size() == 0) return 9;
			JsonObject first = blocks.get(0).getAsJsonObject();
			String block = first.has("block") && !first.get("block").isJsonNull() ? first.get("block").getAsString() : null;
			if (block == null) return 9;
			return switch (block.toLowerCase()) {
				case "event" -> 0;          // player event
				case "game_event" -> 1;     // game event
				case "entity_event" -> 2;   // entity event
				case "func" -> 3;
				case "process" -> 4;
				default -> 9;
			};
		} catch (Exception e) {
			return 9;
		}
	}

	/** Placed length of a line in blocks, or -1 if the token can't be decoded. */
	public static int length(String token) {
		try {
			byte[] gz = Base64.getDecoder().decode(token.trim());
			byte[] json;
			try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
				json = in.readAllBytes();
			}
			JsonObject root = JsonParser.parseString(new String(json, StandardCharsets.UTF_8)).getAsJsonObject();
			JsonArray blocks = root.getAsJsonArray("blocks");
			if (blocks == null) return -1;
			int len = 0;
			for (var el : blocks) {
				JsonObject b = el.getAsJsonObject();
				String id = b.has("id") ? b.get("id").getAsString() : "";
				len += "bracket".equals(id) ? 1 : 2;
			}
			return len;
		} catch (Exception e) {
			return -1; // unknown -> caller proceeds without blocking placement
		}
	}

	/** Readable name of a line from its header block (e.g. "func admin commands", "event Join"),
	 *  for naming over-long / failed lines in messages. "(line)" if it can't be decoded. */
	public static String name(String token) {
		try {
			byte[] gz = Base64.getDecoder().decode(token.trim());
			byte[] json;
			try (GZIPInputStream in = new GZIPInputStream(new ByteArrayInputStream(gz))) {
				json = in.readAllBytes();
			}
			JsonObject root = JsonParser.parseString(new String(json, StandardCharsets.UTF_8)).getAsJsonObject();
			JsonArray blocks = root.getAsJsonArray("blocks");
			if (blocks == null || blocks.size() == 0) return "(empty line)";
			JsonObject first = blocks.get(0).getAsJsonObject();
			String block = first.has("block") && !first.get("block").isJsonNull() ? first.get("block").getAsString() : "?";
			String nm = first.has("data") && !first.get("data").isJsonNull() ? first.get("data").getAsString()
					: first.has("action") && !first.get("action").isJsonNull() ? first.get("action").getAsString() : null;
			return block + (nm != null ? " " + nm : "");
		} catch (Exception e) {
			return "(line)";
		}
	}
}
