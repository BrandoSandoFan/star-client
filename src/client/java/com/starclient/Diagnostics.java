package com.starclient;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Locale;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.loader.api.FabricLoader;

/**
 * Test-server diagnostics: per-tick values (what modules asked for, what the player actually did) next to the
 * limit each one is held to, as an overlay and optionally as JSON lines in {@code logs/starclient-diagnostics.jsonl}.
 * "Requested" rows are sampled before {@link ProtocolSanitizer} clamps them; "observed" rows come from the player's
 * real movement, so a server correction shows up as a position snap.
 */
public final class Diagnostics {
	public static final int REQ_HORIZONTAL = 0;
	public static final int REQ_JUMP = 1;
	public static final int REQ_TURN = 2;
	public static final int PKT_HOP = 3;
	public static final int OBS_HORIZONTAL = 4;
	public static final int OBS_RISE = 5;
	public static final int OBS_SNAP = 6;
	public static final int ATTACK_GAP = 7;

	private static final String[] NAMES = {
			"Req. h-velocity", "Req. jump velocity", "Req. view turn", "Packet hop",
			"Observed h-speed", "Observed rise", "Position snap", "Attack gap"};
	private static final String[] KEYS = {
			"req_hvel", "req_jump", "req_turn", "pkt_hop", "obs_hspeed", "obs_rise", "obs_snap", "attack_gap"};
	private static final String[] UNITS = {"b/t", "b/t", "deg", "b", "b/t", "b/t", "b/t", "ticks"};
	/** Rows where the limit is a minimum (the value must stay at or above it). */
	private static final boolean[] MINIMUM = {false, false, false, false, false, false, false, true};
	private static final int N = NAMES.length;
	private static final Identifier ID = Identifier.fromNamespaceAndPath(StarClient.ID, "diagnostics");
	private static final int LINE = 10;

	private static final double[] value = new double[N];
	private static final double[] limit = new double[N];
	private static final boolean[] sampled = new boolean[N];
	private static final double[] shownValue = new double[N];
	private static final double[] shownLimit = new double[N];
	private static final int[] age = new int[N];
	private static final boolean[] tickExceeded = new boolean[N];

	private static BufferedWriter log;
	private static long lastAttackTime = Long.MIN_VALUE / 2;

	static {
		java.util.Arrays.fill(shownLimit, -1);
	}

	private Diagnostics() { }

	public static void init() {
		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, ID, Diagnostics::render);
	}

	public static boolean active() {
		return StarClient.diagnosticsOn;
	}

	/** Records a sample for this tick; the largest value per tick is kept. {@code lim} is the limit the value is held to. */
	public static void report(int metric, double v, double lim) {
		if (!StarClient.diagnosticsOn) return;
		if (!sampled[metric] || (MINIMUM[metric] ? v < value[metric] : v > value[metric])) {
			value[metric] = v;
			limit[metric] = lim;
			sampled[metric] = true;
		}
	}

	/** Records the gap in ticks between two attacks against the configured minimum delay. */
	public static void attack(long gameTime, double minDelay) {
		long gap = gameTime - lastAttackTime;
		lastAttackTime = gameTime;
		if (gap < 100) report(ATTACK_GAP, gap, minDelay);
	}

	/** Runs at the end of each client tick: samples observed movement, ages the overlay values and writes the log. */
	public static void tick(Minecraft mc) {
		if (!StarClient.diagnosticsOn) return;
		LocalPlayer p = mc.player;
		double hLimit = ProtocolSanitizer.horizontalLimit(p);
		double dx = p.getX() - p.xo;
		double dz = p.getZ() - p.zo;
		double horizontal = Math.sqrt(dx * dx + dz * dz);
		report(OBS_HORIZONTAL, horizontal, hLimit);
		report(OBS_RISE, Math.max(0.0, p.getY() - p.yo), ProtocolSanitizer.jumpLimit(p));
		// A step this far above the speed limit in one tick is what a server teleport or rubberband looks like.
		report(OBS_SNAP, horizontal, hLimit * 3.0);

		boolean any = false;
		int hold = Settings.getI("diag.hold");
		for (int i = 0; i < N; i++) {
			if (sampled[i]) {
				shownValue[i] = value[i];
				shownLimit[i] = limit[i];
				age[i] = 0;
				tickExceeded[i] = exceeded(i, value[i], limit[i]);
				any |= tickExceeded[i];
			} else {
				tickExceeded[i] = false;
				if (++age[i] > hold) shownValue[i] = 0;
			}
		}
		if (Settings.on("diag.log") && (any || Settings.on("diag.logall"))) writeLog(mc, p);
		else if (!Settings.on("diag.log")) closeLog();
		java.util.Arrays.fill(sampled, false);
	}

	private static boolean exceeded(int metric, double v, double lim) {
		return MINIMUM[metric] ? v < lim : v > lim + 1.0e-6;
	}

	private static void render(GuiGraphicsExtractor graphics, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (!StarClient.diagnosticsOn || !Settings.on("diag.overlay") || mc.player == null || mc.gui.hud.isHidden()) return;
		int x = 6;
		int y = mc.getWindow().getGuiScaledHeight() / 2 - N * LINE / 2;
		graphics.fill(x - 3, y - 14, x + 168, y + N * LINE + 2, 0x90101216);
		graphics.text(mc.font, Component.literal("Diagnostics"), x, y - 11, 0xFFFFFFFF, true);
		for (int i = 0; i < N; i++) {
			int colour = 0xFF8A8F98;
			String text = NAMES[i] + ": -";
			if (age[i] <= Settings.getI("diag.hold") && shownLimit[i] >= 0) {
				double v = shownValue[i];
				double lim = shownLimit[i];
				colour = exceeded(i, v, lim) ? 0xFFFF5555 : (!MINIMUM[i] && v >= lim * 0.8) ? 0xFFFFAA00 : 0xFF55FF55;
				text = String.format(Locale.ROOT, "%s: %.3f / %.3f %s", NAMES[i], v, lim, UNITS[i]);
			}
			graphics.text(mc.font, Component.literal(text), x, y + i * LINE, colour, true);
		}
	}

	private static void writeLog(Minecraft mc, LocalPlayer p) {
		try {
			if (log == null) {
				Path file = FabricLoader.getInstance().getGameDir().resolve("logs").resolve("starclient-diagnostics.jsonl");
				Files.createDirectories(file.getParent());
				log = Files.newBufferedWriter(file, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
			}
			StringBuilder sb = new StringBuilder(256);
			sb.append(String.format(Locale.ROOT, "{\"tick\":%d,\"x\":%.3f,\"y\":%.3f,\"z\":%.3f,\"sanitize\":%b",
					mc.level.getGameTime(), p.getX(), p.getY(), p.getZ(), ProtocolSanitizer.enabled()));
			for (int i = 0; i < N; i++) {
				if (!sampled[i]) continue;
				sb.append(String.format(Locale.ROOT, ",\"%s\":{\"value\":%.4f,\"limit\":%.4f,\"exceeded\":%b}",
						KEYS[i], value[i], limit[i], tickExceeded[i]));
			}
			log.write(sb.append("}\n").toString());
			log.flush();
		} catch (IOException e) {
			StarClient.LOGGER.warn("Diagnostics log write failed", e);
			closeLog();
		}
	}

	private static void closeLog() {
		if (log == null) return;
		try {
			log.close();
		} catch (IOException ignored) {
			// nothing to recover
		}
		log = null;
	}

	/** Clears samples and closes the log; called when diagnostics turn off or the world unloads. */
	public static void reset() {
		java.util.Arrays.fill(sampled, false);
		java.util.Arrays.fill(shownValue, 0);
		java.util.Arrays.fill(shownLimit, -1);
		java.util.Arrays.fill(age, 0);
		lastAttackTime = Long.MIN_VALUE / 2;
		closeLog();
	}
}
