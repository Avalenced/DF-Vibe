package com.dfvibe;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Persistent settings, stored at .minecraft/config/df-vibe.json.
 *
 * plotsRoot : folder that holds one subfolder per project (codebase).
 * codecDir      : folder containing dfpy.py + actiondump.json (the Python codec).
 * pythonPath    : python executable to run the codec with.
 * activeProject : project (subfolder) that pull/push currently target.
 */
public class Config {
	public String plotsRoot = "";
	public String codecDir = "";
	public String pythonPath = "python";
	public int codeClientPort = 31375;
	public String activeProject = "";
	public String locateCommand = "locate"; // DF command (no slash) that prints the plot id
	public String rank = ""; // your DF rank: none (free), Noble, Emperor, Mythic or Overlord. Stamped into each project's .df-vibe.json so the codec's RANK_LOCKED check warns on actions above it. Blank = check off; 'none' = free player (flags every rank-locked action).
	public String apiToken = "";   // CodeClient API token; persisted to disk so /auth is needed at most once (re-prompts only if CodeClient invalidates the token)
	public boolean salvageReach = true;  // salvage: equip an offhand +reach item so chests pick from far (fewer flights, much faster)
	public boolean placeReach = true;    // place (push/deploy): equip an offhand +reach item so lines place from far (fewer flights, fewer rubber-band failures)
	public String schemRoot = ""; // folder that holds one subfolder per saved build (schematic save|load), like plotsRoot for code
	public String pullOpts = "";   // default flags appended to every /df pull (e.g. "-rebuild")
	public String pushOpts = "";   // default flags appended to every /df push  (e.g. "-compact -batch 25")
	public String deployOpts = ""; // default flags appended to every /df deploy

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

	private static Path file() {
		return FabricLoader.getInstance().getConfigDir().resolve("df-vibe.json");
	}

	public static Config load() {
		try {
			Path f = file();
			if (Files.exists(f)) {
				Config c = GSON.fromJson(Files.readString(f), Config.class);
				if (c != null) return c;
			}
		} catch (Exception e) {
			System.err.println("[df-vibe] config load failed: " + e);
		}
		return new Config();
	}

	public void save() {
		try {
			Files.writeString(file(), GSON.toJson(this));
		} catch (IOException e) {
			System.err.println("[df-vibe] config save failed: " + e);
		}
	}

	public boolean ready() {
		return !plotsRoot.isBlank() && !codecDir.isBlank();
	}

	public Path projectDir(String name) {
		return Path.of(plotsRoot).resolve(name);
	}

	public Path activeDir() {
		return activeProject.isBlank() ? null : projectDir(activeProject);
	}

	/** The folder for a saved build, under schemRoot (mirrors projectDir for code projects). */
	public Path schemDir(String name) {
		return Path.of(schemRoot).resolve(name);
	}
}
