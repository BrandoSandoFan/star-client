package com.starclient;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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

	public static final String[] ESP_MOB_MODES = {"All mobs", "Only selected", "Hide selected"};
	/** 0 shows mobs by category toggle, 1 shows only {@link #espMobs}, 2 hides {@link #espMobs}. */
	public int espMobMode = 0;
	/** Entity type ids (e.g. {@code minecraft:creeper}) the mob filter applies to. */
	public List<String> espMobs = new ArrayList<>();
	private transient Set<String> espMobSet;

	/** Block ids Chest ESP highlights. */
	public List<String> chestBlocks = new ArrayList<>(Presets.CHEST_DEFAULT);
	private transient Set<String> chestBlockSet;
	/** Bumped whenever {@link #chestBlocks} changes so scanners can drop cached results. */
	public transient int chestVersion;

	/** Per-module settings, keyed as in {@link Settings}. Missing keys use the registered default. */
	public Map<String, Double> values = new HashMap<>();
	/** Per-module free-text settings (usernames, chat messages), keyed as in {@link Settings.TextDef}. */
	public Map<String, String> texts = new HashMap<>();

	public boolean hasMob(String id) {
		if (espMobSet == null) espMobSet = new HashSet<>(espMobs);
		return espMobSet.contains(id);
	}

	public void toggleMob(String id) {
		if (!espMobs.remove(id)) espMobs.add(id);
		espMobSet = null;
	}

	public void setMobs(Collection<String> ids) {
		espMobs = new ArrayList<>(ids);
		espMobSet = null;
	}

	public boolean hasChestBlock(String id) {
		if (chestBlockSet == null) chestBlockSet = new HashSet<>(chestBlocks);
		return chestBlockSet.contains(id);
	}

	public void toggleChestBlock(String id) {
		if (!chestBlocks.remove(id)) chestBlocks.add(id);
		chestBlockSet = null;
		chestVersion++;
	}

	public void setChestBlocks(Collection<String> ids) {
		chestBlocks = new ArrayList<>(ids);
		chestBlockSet = null;
		chestVersion++;
	}

	/** A saved world position. {@code dim} is the dimension key string, so waypoints only show in the dimension they were made in. */
	public static final class Waypoint {
		public String name = "Waypoint";
		public String dim = "";
		public int x;
		public int y;
		public int z;
		public int rgb = 0xFFD700;
		/** True for the automatic death marker; only the latest one is kept. */
		public boolean death;
	}

	public List<Waypoint> waypoints = new ArrayList<>();

	public static final int PROFILE_SLOTS = 5;
	public Profile[] profiles = new Profile[PROFILE_SLOTS];

	/** Snapshot of which modules are on plus the persisted module settings. */
	public static final class Profile {
		public boolean[] modules;
		public boolean[] espEnabled;
		public int[] espColor;
		public float espAlpha;
		public int espMaxDistance;
		public int espMobMode;
		public List<String> espMobs;
		public List<String> chestBlocks;
		public Map<String, Double> values;

		public static Profile capture(Config c) {
			Profile p = new Profile();
			p.modules = new boolean[Module.ALL.size()];
			for (int i = 0; i < p.modules.length; i++) p.modules[i] = isSaved(i) && StarClient.isOn(i);
			p.espEnabled = c.espEnabled.clone();
			p.espColor = c.espColor.clone();
			p.espAlpha = c.espAlpha;
			p.espMaxDistance = c.espMaxDistance;
			p.espMobMode = c.espMobMode;
			p.espMobs = new ArrayList<>(c.espMobs);
			p.chestBlocks = new ArrayList<>(c.chestBlocks);
			p.values = new HashMap<>(c.values);
			return p;
		}

		/** Copies the settings into {@code c}, then toggles modules until they match the snapshot. */
		public void apply(Minecraft mc, Config c) {
			if (espEnabled != null && espEnabled.length == ESP_NAMES.length) c.espEnabled = espEnabled.clone();
			if (espColor != null && espColor.length == ESP_NAMES.length) c.espColor = espColor.clone();
			c.espAlpha = espAlpha;
			c.espMaxDistance = espMaxDistance;
			c.espMobMode = espMobMode;
			if (espMobs != null) c.setMobs(espMobs);
			if (chestBlocks != null) c.setChestBlocks(chestBlocks);
			if (values != null) c.values = new HashMap<>(values);
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
			return module != Module.SORTER && module != Module.PANIC && module != Module.CLIPPER
					&& module != Module.CREATIVE_MENU;
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
				&& espMobs != null && chestBlocks != null && values != null && texts != null
				&& profiles != null && profiles.length == PROFILE_SLOTS;
	}
}
