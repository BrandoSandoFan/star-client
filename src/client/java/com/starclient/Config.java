package com.starclient;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import net.fabricmc.loader.api.FabricLoader;

/** Persistent user settings, stored as JSON in the Fabric config directory. */
public final class Config {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("starclient.json");

	public static final String[] ESP_NAMES = {"Players", "Hostile", "Animals", "Items"};

	public boolean[] espEnabled = {true, true, true, true};
	/** RGB per ESP category, same order as {@link #ESP_NAMES}. */
	public int[] espColor = {0xFF2626, 0xFF9900, 0x4DFF4D, 0x33E6FF};
	public float espAlpha = 0.35f;
	/** Max distance in blocks; 0 means unlimited. */
	public int espMaxDistance = 0;

	private static Config instance = new Config();

	public static Config get() {
		return instance;
	}

	public static void reset() {
		instance = new Config();
	}

	public static void load() {
		if (!Files.exists(FILE)) return;
		try (Reader r = Files.newBufferedReader(FILE)) {
			Config c = GSON.fromJson(r, Config.class);
			if (c != null && c.valid()) instance = c;
		} catch (IOException | RuntimeException e) {
			// Keep defaults on a missing or corrupt file.
		}
	}

	public static void save() {
		try (Writer w = Files.newBufferedWriter(FILE)) {
			GSON.toJson(instance, w);
		} catch (IOException e) {
			// Non-fatal: settings just won't persist.
		}
	}

	private boolean valid() {
		return espEnabled != null && espEnabled.length == ESP_NAMES.length
				&& espColor != null && espColor.length == ESP_NAMES.length;
	}
}
