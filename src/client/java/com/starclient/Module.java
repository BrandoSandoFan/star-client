package com.starclient;

import java.util.List;

import net.minecraft.ChatFormatting;

/** Static module metadata. Index in {@link #ALL} is the module id used across the client. */
public record Module(String id, String name, String description, Category category, boolean hasSettings) {
	public enum Category {
		RENDER("Render", ChatFormatting.AQUA),
		MOVEMENT("Movement", ChatFormatting.GREEN),
		WORLD("World", ChatFormatting.LIGHT_PURPLE),
		COMBAT("Combat", ChatFormatting.RED),
		BASE_FINDING("Bases", ChatFormatting.YELLOW);

		public final String label;
		public final ChatFormatting colour;

		Category(String label, ChatFormatting colour) {
			this.label = label;
			this.colour = colour;
		}
	}

	public static final int ESP = 0;
	public static final int BASE_FINDER = 1;
	public static final int FREECAM = 2;
	public static final int CHEST_ESP = 3;
	public static final int FULLBRIGHT = 4;
	public static final int AUTO_SPRINT = 5;
	public static final int AUTO_WALK = 6;
	public static final int FLY = 7;
	public static final int REACH = 8;
	public static final int AIM_BOT = 9;
	public static final int HIT_BOT = 10;
	public static final int ELYTRA_FLY = 11;
	public static final int KILL_AURA = 12;
	public static final int CRITS = 13;
	public static final int HITBOXES = 14;
	public static final int SPEED = 15;
	public static final int HIGH_JUMP = 16;

	public static final List<Module> ALL = List.of(
			new Module("esp", "ESP", "Boxes around players, mobs, animals and items through walls.", Category.RENDER, true),
			new Module("basefinder", "Base Finder", "Marks chunks with many player-placed blocks.", Category.BASE_FINDING, false),
			new Module("freecam", "Freecam", "Detached camera. Your character stays in place.", Category.MOVEMENT, false),
			new Module("chestesp", "Chest ESP", "Highlights chests, barrels, ender chests and shulker boxes.", Category.RENDER, false),
			new Module("fullbright", "Fullbright", "Client-side night vision.", Category.RENDER, false),
			new Module("autosprint", "Auto Sprint", "Sprints whenever you walk forward.", Category.MOVEMENT, false),
			new Module("autowalk", "Auto Walk", "Holds the forward key for you.", Category.MOVEMENT, false),
			new Module("fly", "Fly", "Creative-style flight. Servers without flight permission will kick you.", Category.MOVEMENT, false),
			new Module("reach", "Reach", "Extends block and entity interaction range. Most servers reject far hits.", Category.WORLD, true),
			new Module("aimbot", "Aim Bot", "Smoothly turns toward the nearest player or hostile mob within 6 blocks.", Category.COMBAT, false),
			new Module("hitbot", "Hit Bot", "Attacks whatever your crosshair is on once your attack is fully charged.", Category.COMBAT, false),
				new Module("elytrafly", "Elytra Fly", "While gliding, alternates dive and climb angles to keep flying without fireworks.", Category.MOVEMENT, false),
				new Module("killaura", "Kill Aura", "Attacks the nearest player or hostile mob in reach with line of sight, no aiming needed.", Category.COMBAT, false),
				new Module("crits", "Crits", "Makes Hit Bot and Kill Aura hits critical by sending fake fall packets first. Anticheats flag this.", Category.COMBAT, false),
				new Module("hitboxes", "Hitboxes", "Enlarges the area that counts as hitting an entity, for aiming, Hit Bot and Kill Aura. Servers still check real reach.", Category.COMBAT, true),
				new Module("speed", "Speed", "Moves faster on the ground and in the air. Servers with movement checks will rubber-band you.", Category.MOVEMENT, false),
				new Module("highjump", "High Jump", "Jumps much higher. Landing still hurts.", Category.MOVEMENT, false));
}
