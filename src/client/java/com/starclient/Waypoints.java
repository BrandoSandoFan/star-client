package com.starclient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;

/** Waypoints: saved positions (and the last death point) drawn as beams by {@link StarRenderer}, listed at the top of the screen. */
public final class Waypoints {
	private static final Identifier ID = Identifier.fromNamespaceAndPath(StarClient.ID, "waypoints");
	private static final String[] ARROWS = {"↑", "↗", "→", "↘", "↓", "↙", "←", "↖"};
	private static final int MAX_LISTED = 5;
	private static final int LINE = 10;

	private static boolean wasDead;

	private Waypoints() { }

	public static void init() {
		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, ID, Waypoints::render);
	}

	public static String dimension(Minecraft mc) {
		return mc.level.dimension().toString();
	}

	/** Waypoints made in the current dimension and within the configured distance, nearest first. */
	public static List<Config.Waypoint> visible(Minecraft mc) {
		List<Config.Waypoint> out = new ArrayList<>();
		String dim = dimension(mc);
		double range = Settings.get("waypoints.range");
		for (Config.Waypoint w : Config.get().waypoints) {
			if (!w.dim.equals(dim)) continue;
			if (range > 0 && distance(mc, w) > range) continue;
			out.add(w);
		}
		out.sort(Comparator.comparingDouble(w -> distance(mc, w)));
		return out;
	}

	public static double distance(Minecraft mc, Config.Waypoint w) {
		double dx = w.x + 0.5 - mc.player.getX();
		double dy = w.y - mc.player.getY();
		double dz = w.z + 0.5 - mc.player.getZ();
		return Math.sqrt(dx * dx + dy * dy + dz * dz);
	}

	public static void addHere(Minecraft mc, String name) {
		Config.Waypoint w = new Config.Waypoint();
		w.name = name.isBlank() ? "Waypoint " + (Config.get().waypoints.size() + 1) : name.trim();
		w.dim = dimension(mc);
		w.x = mc.player.getBlockX();
		w.y = mc.player.getBlockY();
		w.z = mc.player.getBlockZ();
		Config.get().waypoints.add(w);
		Config.save();
	}

	public static void tick(Minecraft mc) {
		boolean dead = mc.player.isDeadOrDying();
		if (dead && !wasDead && StarClient.waypointsOn && Settings.on("waypoints.death")) {
			Config.get().waypoints.removeIf(w -> w.death);
			addHere(mc, "Death");
			List<Config.Waypoint> all = Config.get().waypoints;
			Config.Waypoint w = all.get(all.size() - 1);
			w.death = true;
			w.rgb = 0xFF3030;
			Config.save();
		}
		wasDead = dead;
	}

	public static void reset() {
		wasDead = false;
	}

	/** Arrow showing where (x, z) lies relative to the way the player is facing. */
	public static String arrow(Minecraft mc, double x, double z) {
		double dx = x - mc.player.getX();
		double dz = z - mc.player.getZ();
		float bearing = (float) (Mth.atan2(-dx, dz) * Mth.RAD_TO_DEG);
		return ARROWS[Math.floorMod(Math.round(Mth.wrapDegrees(bearing - mc.player.getYRot()) / 45f), 8)];
	}

	private static void render(GuiGraphicsExtractor graphics, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (!StarClient.waypointsOn || !Settings.on("waypoints.hud") || mc.player == null || mc.level == null || mc.options.hideGui) return;
		List<Config.Waypoint> list = visible(mc);
		int centre = mc.getWindow().getGuiScaledWidth() / 2;
		int y = 4;
		for (int i = 0; i < list.size() && i < MAX_LISTED; i++) {
			Config.Waypoint w = list.get(i);
			Component text = Component.literal(arrow(mc, w.x + 0.5, w.z + 0.5) + " " + w.name + "  " + Math.round(distance(mc, w)) + "m").withStyle(ChatFormatting.WHITE);
			int x = centre - mc.font.width(text) / 2;
			graphics.text(mc.font, text, x, y, 0xFF000000 | w.rgb, true);
			y += LINE;
		}
	}
}
