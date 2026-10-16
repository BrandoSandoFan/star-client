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
	public static final int SOFT_FALL = 17;
	public static final int TRAJECTORIES = 18;
	public static final int JESUS = 19;
	public static final int AUTO_EAT = 20;
	public static final int ANTI_AFK = 21;
	public static final int SORTER = 22;
	public static final int HUD = 23;
	public static final int PANIC = 24;
	public static final int ANTI_BAIT = 25;
	public static final int DIAGNOSTICS = 26;
	public static final int AUTO_TOOL = 27;
	public static final int TRACERS = 28;
	public static final int ZOOM = 29;
	public static final int WAYPOINTS = 30;
	public static final int AUTO_TOTEM = 31;
	public static final int AUTO_ARMOR = 32;
	public static final int AUTO_RESPAWN = 33;
	public static final int AUTO_REPLENISH = 34;
	public static final int STEP = 35;
	public static final int SAFE_WALK = 36;
	public static final int NO_SLOW = 37;
	public static final int VELOCITY = 38;
	public static final int PORTAL_FINDER = 39;
	public static final int SIGN_READER = 40;

	public static final List<Module> ALL = List.of(
			new Module("esp", "ESP", "Boxes around players, mobs, animals and items through walls.", Category.RENDER, true),
			new Module("basefinder", "Base Finder", "Marks chunks with many player-placed blocks.", Category.BASE_FINDING, true),
			new Module("freecam", "Freecam", "Detached camera. Your character stays in place.", Category.MOVEMENT, true),
			new Module("chestesp", "Chest ESP", "Highlights chests, barrels, ender chests and shulker boxes.", Category.RENDER, true),
			new Module("fullbright", "Fullbright", "Client-side night vision.", Category.RENDER, true),
			new Module("autosprint", "Auto Sprint", "Sprints whenever you walk forward.", Category.MOVEMENT, true),
			new Module("autowalk", "Auto Walk", "Holds the forward key for you.", Category.MOVEMENT, true),
			new Module("fly", "Fly", "Creative-style flight. Servers without flight permission will kick you.", Category.MOVEMENT, true),
			new Module("reach", "Reach", "Extends block and entity interaction range. Most servers reject far hits.", Category.WORLD, true),
			new Module("aimbot", "Aim Bot", "Smoothly turns toward the nearest player or hostile mob within 6 blocks.", Category.COMBAT, true),
			new Module("hitbot", "Hit Bot", "Attacks whatever your crosshair is on once your attack is fully charged.", Category.COMBAT, true),
				new Module("elytrafly", "Elytra Fly", "While gliding, alternates dive and climb angles to keep flying without fireworks.", Category.MOVEMENT, true),
				new Module("killaura", "Kill Aura", "Attacks the nearest player or hostile mob in reach with line of sight, no aiming needed.", Category.COMBAT, true),
				new Module("crits", "Crits", "Makes Hit Bot and Kill Aura hits critical by sending fake fall packets first. Anticheats flag this.", Category.COMBAT, true),
				new Module("hitboxes", "Hitboxes", "Enlarges the area that counts as hitting an entity, for aiming, Hit Bot and Kill Aura. Servers still check real reach.", Category.COMBAT, true),
				new Module("speed", "Speed", "Moves faster on the ground and in the air. Servers with movement checks will rubber-band you.", Category.MOVEMENT, true),
				new Module("highjump", "High Jump", "Jumps much higher. Landing still hurts.", Category.MOVEMENT, true),
				new Module("softfall", "Softfall", "Cancels fall damage by reporting that you are on the ground. Anticheats may flag it.", Category.MOVEMENT, true),
				new Module("trajectories", "Trajectories", "Draws where arrows, tridents, pearls, snowballs, eggs and potions you hold will fly and land.", Category.RENDER, true),
				new Module("jesus", "Jesus", "Walk on water. Hold sneak to sink, jump to swim. Servers with movement checks will flag it.", Category.MOVEMENT, true),
				new Module("autoeat", "Auto Eat", "Eats the best food in your hotbar when hunger drops to 7 bars, then switches back.", Category.WORLD, true),
				new Module("antiafk", "Anti AFK", "Jumps and nudges your view every 20 seconds while you stand still.", Category.WORLD, true),
				new Module("sorter", "Sorter", "Merges and sorts your main inventory once, then turns itself off.", Category.WORLD, true),
				new Module("hud", "HUD", "Lists your enabled modules in the top-right corner.", Category.RENDER, true),
				new Module("panic", "Panic", "Turns every module off at once and hides the HUD.", Category.WORLD, true),
				new Module("antibait", "Anti Bait", "Hides chests that appear in bulk, and new spawners, in chunks you already had loaded, like admin-placed bait stashes.", Category.RENDER, true),
				new Module("diagnostics", "Diagnostics", "Overlay and optional log of per-tick velocity, turn and packet values against their limits, for finding which check a test server reacts to.", Category.RENDER, true),
				new Module("autotool", "Auto Tool", "Switches to the fastest hotbar tool for the block you are mining, then switches back.", Category.WORLD, true),
				new Module("tracers", "Tracers", "Lines from your crosshair to every entity ESP shows, in the ESP category colours.", Category.RENDER, true),
				new Module("zoom", "Zoom", "Narrows your field of view for a closer look. Restores it when switched off.", Category.RENDER, true),
				new Module("waypoints", "Waypoints", "Shows saved positions and your last death point as beams through walls, with distance and direction on screen.", Category.RENDER, true),
				new Module("autototem", "Auto Totem", "Moves a totem of undying into your offhand when it is empty or your health is low.", Category.COMBAT, true),
				new Module("autoarmor", "Auto Armor", "Equips the best armor piece in your inventory for each slot.", Category.COMBAT, true),
				new Module("autorespawn", "Auto Respawn", "Clicks Respawn on the death screen for you.", Category.WORLD, true),
				new Module("autoreplenish", "Auto Replenish", "Refills a hotbar slot from your inventory when its stack runs low or is used up.", Category.WORLD, true),
				new Module("step", "Step", "Walk up ledges taller than a slab. Servers with movement checks may rubber-band you.", Category.MOVEMENT, true),
				new Module("safewalk", "Safe Walk", "Stops you walking off block edges without sneaking. Works best on normal ground; ice still slides.", Category.MOVEMENT, true),
				new Module("noslow", "No Slow", "Cancels the slowdown while eating, drawing a bow or using a shield. Servers with movement checks may flag it.", Category.MOVEMENT, true),
				new Module("velocity", "Velocity", "Reduces the knockback you take when hit.", Category.MOVEMENT, true),
				new Module("portalfinder", "Portal Finder", "Marks nether portals, end portals and end portal frames in loaded chunks.", Category.BASE_FINDING, true),
				new Module("signreader", "Sign Reader", "Lists the text of nearby signs on screen and highlights them.", Category.BASE_FINDING, true));
}
