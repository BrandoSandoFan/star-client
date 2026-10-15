package com.starclient;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;

import net.minecraft.client.Minecraft;

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

	/** Interaction range in blocks while Reach is on. */
	public float reachDistance = 6.0f;

	/** Blocks added to every side of an entity's hitbox while Hitboxes is on. */
	public float hitboxExpand = 0.3f;

	public static final int PROFILE_SLOTS = 5;
	public Profile[] profiles = new Profile[PROFILE_SLOTS];

	/** Snapshot of which modules are on plus the persisted module settings. */
	public static final class Profile {
		public boolean[] modules;
		public boolean[] espEnabled;
		public int[] espColor;
		public float espAlpha;
		public int espMaxDistance;
		public float reachDistance;
		public float hitboxExpand;

		public static Profile capture(Config c) {
			Profile p = new Profile();
			p.modules = new boolean[Module.ALL.size()];
			for (int i = 0; i < p.modules.length; i++) p.modules[i] = isSaved(i) && StarClient.isOn(i);
			p.espEnabled = c.espEnabled.clone();
			p.espColor = c.espColor.clone();
			p.espAlpha = c.espAlpha;
			p.espMaxDistance = c.espMaxDistance;
			p.reachDistance = c.reachDistance;
			p.hitboxExpand = c.hitboxExpand;
			return p;
		}

		/** Copies the settings into {@code c}, then toggles modules until they match the snapshot. */
		public void apply(Minecraft mc, Config c) {
			if (espEnabled != null && espEnabled.length == ESP_NAMES.length) c.espEnabled = espEnabled.clone();
			if (espColor != null && espColor.length == ESP_NAMES.length) c.espColor = espColor.clone();
			c.espAlpha = espAlpha;
			c.espMaxDistance = espMaxDistance;
			c.reachDistance = reachDistance;
			c.hitboxExpand = hitboxExpand;
			if (mc.player == null || modules == null) return;
			for (int i = 0; i < Module.ALL.size(); i++) {
				boolean want = i < modules.length && modules[i];
				if (isSaved(i) && StarClient.isOn(i) != want) StarClient.toggle(mc, i);
			}
		}

		public int enabledCount() {
			int n = 0;
			if (modules != null) for (boolean b : modules) if (b) n++;
			return n;
		}

		/** One-shot actions are not part of a profile. */
		private static boolean isSaved(int module) {
			return module != Module.SORTER && module != Module.PANIC;
		}
	}

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
				&& espColor != null && espColor.length == ESP_NAMES.length
				&& profiles != null && profiles.length == PROFILE_SLOTS;
	}
}
