package com.starclient;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Registry of per-module numeric and on/off settings. Values persist in {@link Config#values}. */
public final class Settings {
	/** A slider ({@code toggle} false) from min to max in {@code step} increments, or an on/off switch (stored as 0/1). */
	public record Def(String key, String label, double min, double max, double step, double def, String unit, boolean toggle) {
		public String format(double v) {
			if (toggle) return v >= 0.5 ? "On" : "Off";
			int decimals = step >= 1 ? 0 : step >= 0.1 ? 1 : step >= 0.01 ? 2 : 3;
			return String.format(Locale.ROOT, "%." + decimals + "f", v) + (unit.isEmpty() ? "" : " " + unit);
		}

		public double snap(double fraction) {
			double v = min + fraction * (max - min);
			v = Math.round(v / step) * step;
			return Math.max(min, Math.min(max, Math.round(v * 1000) / 1000.0));
		}
	}

	/**
	 * Elytra Fly dive/climb cycle that gains altitude in a simulation of vanilla elytra physics (no rockets):
	 * dive 35 deg until 2.2 b/t, climb 40 deg until 0.7 b/t. The cycle swings roughly 150 blocks vertically.
	 */
	private static final Map<String, Double> ELYTRA_INFINITE = Map.of(
			"elytrafly.dive", 35.0, "elytrafly.climb", 40.0, "elytrafly.diveend", 2.2, "elytrafly.climbend", 0.7, "elytrafly.step", 6.0);

	private static final Map<Integer, List<Def>> BY_MODULE = new HashMap<>();
	private static final Map<String, Def> BY_KEY = new HashMap<>();

	private static Def slider(String key, String label, double min, double max, double step, double def, String unit) {
		return new Def(key, label, min, max, step, def, unit, false);
	}

	private static Def toggle(String key, String label, boolean def) {
		return new Def(key, label, 0, 1, 1, def ? 1 : 0, "", true);
	}

	private static void add(int module, Def... defs) {
		BY_MODULE.put(module, List.of(defs));
		for (Def d : defs) BY_KEY.put(d.key(), d);
	}

	/** Global settings with no module page. */
	static {
		for (Def d : new Def[] {
				toggle("sanitize.enabled", "Clamp to vanilla limits", true),
				slider("sanitize.maxturn", "Max turn per tick", 1, 90, 1, 30, "deg")}) {
			BY_KEY.put(d.key(), d);
		}
	}

	static {
		add(Module.BASE_FINDER, slider("basefinder.min", "Min blocks per chunk", 1, 40, 1, 10, ""));
		add(Module.FREECAM,
				slider("freecam.speed", "Speed", 1, 60, 1, 10, "b/s"),
				slider("freecam.sprint", "Sprint multiplier", 1, 5, 0.5, 2.5, "x"));
		add(Module.CHEST_ESP,
				slider("chestesp.alpha", "Opacity", 0.1, 1, 0.05, 0.4, ""),
				slider("chestesp.radius", "Radius", 1, 8, 1, 8, "chunks"));
		add(Module.FULLBRIGHT, slider("fullbright.maxlight", "Only when light is at most", 0, 15, 1, 15, ""));
		add(Module.AUTO_WALK,
				toggle("autowalk.sneak", "Pause while sneaking", false),
				slider("autowalk.minhealth", "Pause below health", 0, 20, 1, 0, ""));
		add(Module.AUTO_SPRINT, slider("autosprint.food", "Min food level", 0, 20, 1, 6, ""));
		add(Module.FLY,
				slider("fly.speed", "Flight speed", 0.05, 1, 0.05, 0.1, ""),
				slider("fly.sprint", "Sprint multiplier", 1, 5, 0.5, 2, "x"));
		add(Module.REACH, slider("reach.distance", "Reach", 3, 12, 0.5, 6, "blocks"));
		add(Module.AIM_BOT,
				slider("aimbot.range", "Range", 1, 12, 0.5, 6, "blocks"),
				slider("aimbot.turn", "Max turn per tick", 1, 30, 1, 8, "deg"),
				slider("aimbot.ease", "Smoothing", 0.05, 1, 0.05, 0.2, ""),
				toggle("aimbot.spring", "Critically damped spring", false),
				slider("aimbot.omega", "Spring stiffness", 0.1, 1.5, 0.05, 0.6, "/tick"),
				slider("aimbot.fov", "Field of view", 30, 360, 10, 90, "deg"),
				toggle("aimbot.players", "Target players", true),
				toggle("aimbot.hostiles", "Target hostile mobs", true));
		add(Module.HIT_BOT,
				slider("hitbot.charge", "Min attack charge", 0.5, 1, 0.05, 0.95, ""),
				slider("hitbot.delay", "Min ticks between attacks", 0, 20, 1, 0, "ticks"),
				toggle("hitbot.players", "Target players", true),
				toggle("hitbot.hostiles", "Target hostile mobs", true));
		add(Module.ELYTRA_FLY,
				slider("elytrafly.dive", "Dive angle", 10, 60, 5, 35, "deg"),
				slider("elytrafly.climb", "Climb angle", 10, 70, 5, 45, "deg"),
				slider("elytrafly.diveend", "Climb above speed", 1, 3, 0.1, 1.7, "b/t"),
				slider("elytrafly.climbend", "Dive below speed", 0.5, 1.5, 0.1, 0.9, "b/t"),
				slider("elytrafly.step", "Pitch change per tick", 1, 15, 1, 6, "deg"),
				toggle("elytrafly.rockets", "Auto use rockets", false),
				slider("elytrafly.rocketspeed", "Rocket below speed", 0.3, 2, 0.1, 0.6, "b/t"),
				slider("elytrafly.rocketdelay", "Min delay between rockets", 1, 15, 1, 3, "s"));
		add(Module.KILL_AURA,
				slider("killaura.range", "Range (0 = Reach)", 0, 12, 0.5, 0, "blocks"),
				slider("killaura.charge", "Min attack charge", 0.5, 1, 0.05, 0.95, ""),
				slider("killaura.delay", "Min ticks between attacks", 0, 20, 1, 0, "ticks"),
				toggle("killaura.players", "Target players", true),
				toggle("killaura.hostiles", "Target hostile mobs", true));
		add(Module.CRITS, slider("crits.hop", "Fake hop height", 0.02, 0.25, 0.01, 0.06, "blocks"));
		add(Module.HITBOXES, slider("hitboxes.expand", "Expansion per side", 0, 2, 0.05, 0.3, "blocks"));
		add(Module.SPEED, slider("speed.speed", "Speed", 0.1, 1.5, 0.05, 0.45, "b/t"));
		add(Module.HIGH_JUMP, slider("highjump.velocity", "Jump velocity", 0.5, 2, 0.05, 0.9, "b/t"));
		add(Module.SOFT_FALL, slider("softfall.distance", "Start after falling", 1, 10, 0.5, 2, "blocks"));
		add(Module.TRAJECTORIES,
				slider("trajectories.steps", "Max simulation steps", 50, 400, 10, 200, ""),
				toggle("trajectories.path", "Show path", true),
				toggle("trajectories.landing", "Show landing marker", true));
		add(Module.JESUS, slider("jesus.rise", "Rise speed", 0.05, 0.5, 0.05, 0.2, "b/t"));
		add(Module.AUTO_EAT, slider("autoeat.hunger", "Eat at food level", 1, 19, 1, 14, ""));
		add(Module.ANTI_AFK,
				slider("antiafk.seconds", "Idle time", 5, 120, 5, 20, "s"),
				slider("antiafk.nudge", "View nudge", 0, 45, 5, 10, "deg"));
		add(Module.SORTER, slider("sorter.ops", "Clicks per tick", 1, 10, 1, 2, ""));
		add(Module.HUD,
				toggle("hud.right", "Align right", true),
				toggle("hud.colour", "Category colours", true),
				toggle("hud.shadow", "Text shadow", true),
				toggle("hud.bar", "Pixel backing", true));
		add(Module.PANIC, toggle("panic.close", "Close open screen", true));
		add(Module.DIAGNOSTICS,
				toggle("diag.overlay", "Show overlay", true),
				slider("diag.hold", "Hold values", 5, 100, 5, 20, "ticks"),
				toggle("diag.log", "Write logs/starclient-diagnostics.jsonl", false),
				toggle("diag.logall", "Log every tick (else only exceeded)", false));
		add(Module.ANTI_BAIT,
				slider("antibait.min", "Min new blocks in one tick", 2, 10, 1, 3, ""),
				slider("antibait.area", "Area", 4, 32, 1, 16, "blocks"),
				toggle("antibait.spawners", "Hide any new spawner", true));
		add(Module.AUTO_TOOL,
				toggle("autotool.restore", "Switch back when done", true),
				slider("autotool.protect", "Skip tools at or below durability", 0, 50, 1, 2, ""));
		add(Module.TRACERS,
				slider("tracers.alpha", "Opacity", 0.1, 1, 0.05, 0.8, ""),
				slider("tracers.width", "Thickness", 0.005, 0.05, 0.005, 0.01, ""),
				slider("tracers.range", "Max distance (0 = ESP setting)", 0, 256, 8, 0, "blocks"));
		add(Module.ZOOM, slider("zoom.fov", "Zoom field of view", 30, 70, 5, 30, "deg"));
		add(Module.WAYPOINTS,
				toggle("waypoints.death", "Mark death point", true),
				toggle("waypoints.hud", "Show list on screen", true),
				slider("waypoints.range", "Max distance (0 = any)", 0, 2000, 50, 0, "blocks"),
				slider("waypoints.beam", "Beam height", 4, 128, 4, 32, "blocks"),
				slider("waypoints.alpha", "Opacity", 0.1, 1, 0.05, 0.5, ""));
	}

	/** Named value sets a module's settings page offers as one-click presets. Keys are setting keys. */
	public static final Map<Integer, Map<String, Map<String, Double>>> PRESETS = new HashMap<>();

	static {
		PRESETS.put(Module.ELYTRA_FLY, new java.util.LinkedHashMap<>());
		PRESETS.get(Module.ELYTRA_FLY).put("Infinite (no rockets)", ELYTRA_INFINITE);
		PRESETS.get(Module.ELYTRA_FLY).put("Default", Map.of());
	}

	private Settings() { }

	/** Applies a preset: resets the module, then writes the preset's values. */
	public static void applyPreset(int module, Map<String, Double> values) {
		reset(module);
		Config.get().values.putAll(values);
	}

	public static List<Def> defs(int module) {
		return BY_MODULE.getOrDefault(module, List.of());
	}

	public static double get(String key) {
		Double v = Config.get().values.get(key);
		if (v != null) return v;
		Def d = BY_KEY.get(key);
		return d == null ? 0 : d.def();
	}

	public static float getF(String key) {
		return (float) get(key);
	}

	public static int getI(String key) {
		return (int) Math.round(get(key));
	}

	public static boolean on(String key) {
		return get(key) >= 0.5;
	}

	public static void set(String key, double value) {
		Config.get().values.put(key, value);
	}

	public static void reset(int module) {
		for (Def d : defs(module)) Config.get().values.remove(d.key());
	}
}
