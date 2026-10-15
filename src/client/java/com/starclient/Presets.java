package com.starclient;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Ready-made block and mob lists for Chest ESP and the ESP mob filter. Ids without a namespace are vanilla. */
public final class Presets {
	private static final String[] COLOURS = {"white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray",
			"light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"};

	public static final List<String> SHULKERS = shulkers();
	public static final List<String> CHEST_DEFAULT = ids(
			"chest", "trapped_chest", "ender_chest", "barrel", "hopper", "dropper", "dispenser", "crafter",
			"furnace", "blast_furnace", "smoker", "brewing_stand", "beacon",
			"budding_amethyst", "amethyst_cluster", "large_amethyst_bud", "medium_amethyst_bud", "small_amethyst_bud");
	static {
		CHEST_DEFAULT.addAll(SHULKERS);
	}

	public static final Map<String, List<String>> BLOCKS = new LinkedHashMap<>();
	public static final Map<String, List<String>> MOBS = new LinkedHashMap<>();

	static {
		List<String> storage = ids("chest", "trapped_chest", "ender_chest", "barrel");
		storage.addAll(SHULKERS);
		BLOCKS.put("Storage", storage);
		BLOCKS.put("Default", CHEST_DEFAULT);
		BLOCKS.put("Ores", ids("coal_ore", "deepslate_coal_ore", "iron_ore", "deepslate_iron_ore", "copper_ore",
				"deepslate_copper_ore", "gold_ore", "deepslate_gold_ore", "nether_gold_ore", "redstone_ore",
				"deepslate_redstone_ore", "lapis_ore", "deepslate_lapis_ore", "diamond_ore", "deepslate_diamond_ore",
				"emerald_ore", "deepslate_emerald_ore", "nether_quartz_ore", "ancient_debris"));
		BLOCKS.put("Diamonds", ids("diamond_ore", "deepslate_diamond_ore", "ancient_debris", "emerald_ore", "deepslate_emerald_ore"));
		BLOCKS.put("Spawners", ids("spawner", "trial_spawner", "vault"));
		BLOCKS.put("Redstone", ids("hopper", "dropper", "dispenser", "crafter", "observer", "piston", "sticky_piston"));
		BLOCKS.put("Amethyst", ids("budding_amethyst", "amethyst_cluster", "large_amethyst_bud", "medium_amethyst_bud", "small_amethyst_bud"));
		BLOCKS.put("Workstations", ids("furnace", "blast_furnace", "smoker", "brewing_stand", "beacon", "enchanting_table", "anvil", "crafting_table"));
		BLOCKS.put("Clear", List.of());

		MOBS.put("Dangerous", ids("creeper", "skeleton", "zombie", "enderman", "witch", "phantom", "blaze", "ghast",
				"wither_skeleton", "warden", "piglin_brute", "vindicator", "evoker", "ravager", "breeze"));
		MOBS.put("Bosses", ids("ender_dragon", "wither", "warden", "elder_guardian"));
		MOBS.put("Nether", ids("ghast", "blaze", "piglin", "piglin_brute", "hoglin", "zoglin", "wither_skeleton", "magma_cube", "strider"));
		MOBS.put("Farm", ids("cow", "pig", "sheep", "chicken", "horse", "donkey", "mule", "rabbit", "goat", "llama", "camel", "mooshroom"));
		MOBS.put("Villagers", ids("villager", "wandering_trader", "zombie_villager", "iron_golem"));
		MOBS.put("Rare", ids("axolotl", "panda", "allay", "armadillo", "sniffer", "frog", "fox", "ocelot", "parrot"));
		MOBS.put("Clear", List.of());
	}

	private Presets() { }

	private static List<String> shulkers() {
		List<String> out = new ArrayList<>();
		out.add("minecraft:shulker_box");
		for (String c : COLOURS) out.add("minecraft:" + c + "_shulker_box");
		return out;
	}

	private static List<String> ids(String... names) {
		List<String> out = new ArrayList<>();
		for (String n : names) out.add("minecraft:" + n);
		return out;
	}
}
