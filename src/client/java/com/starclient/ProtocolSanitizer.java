package com.starclient;

import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.phys.Vec3;

/**
 * Single choke point that keeps module output inside vanilla movement and rotation limits, so the server
 * accepts it instead of rubberbanding. Every module routes velocity writes, view turns and movement packets
 * through here. Disabled with the global setting {@code sanitize.enabled}.
 */
public final class ProtocolSanitizer {
	/** Vanilla sprint-jump peaks at about 0.36 b/t horizontally with base movement speed 0.1. */
	private static final double VANILLA_HORIZONTAL = 0.36;
	private static final double VANILLA_JUMP = 0.42;
	private static final double BASE_SPEED = 0.1;
	/** Largest vertical hop a fake movement packet may carry. */
	private static final double MAX_HOP = 0.0625;

	/** Tallest ledge Step may climb; vanilla players step 0.6 and jump 1.25. */
	private static final double MAX_STEP = 1.0;

	private ProtocolSanitizer() { }

	public static boolean enabled() {
		return Settings.on("sanitize.enabled");
	}

	/** Horizontal speed limit in blocks per tick, scaled by the player's movement speed attribute (speed effects, sprint). */
	public static double horizontalLimit(LocalPlayer p) {
		double scale = Math.max(1.0, p.getAttributeValue(Attributes.MOVEMENT_SPEED) / BASE_SPEED);
		return VANILLA_HORIZONTAL * scale;
	}

	/** Upward take-off speed limit, including Jump Boost. */
	public static double jumpLimit(LocalPlayer p) {
		var boost = p.getEffect(MobEffects.JUMP_BOOST);
		return VANILLA_JUMP + (boost == null ? 0.0 : 0.1 * (boost.getAmplifier() + 1));
	}

	/** Scales the horizontal part of {@code v} down to the vanilla limit; the vertical part is untouched. */
	public static Vec3 clampHorizontal(LocalPlayer p, Vec3 v) {
		double limit = horizontalLimit(p);
		double len = Math.sqrt(v.x * v.x + v.z * v.z);
		Diagnostics.report(Diagnostics.REQ_HORIZONTAL, len, limit);
		if (!enabled()) return v;
		return len <= limit ? v : new Vec3(v.x / len * limit, v.y, v.z / len * limit);
	}

	/** Clamps an upward take-off velocity to the vanilla jump. */
	public static double clampJump(LocalPlayer p, double vy) {
		Diagnostics.report(Diagnostics.REQ_JUMP, vy, jumpLimit(p));
		return enabled() ? Math.min(vy, jumpLimit(p)) : vy;
	}

	/** Clamps a step-height request to {@link #MAX_STEP}. */
	public static double clampStep(double height) {
		return enabled() ? Math.min(height, MAX_STEP) : height;
	}

	/** Per-tick view change cap in degrees. */
	public static float turnLimit() {
		return enabled() ? Settings.getF("sanitize.maxturn") : 180.0f;
	}

	/** Limits a per-tick view change to {@code sanitize.maxturn} degrees. */
	public static float limitTurn(float delta) {
		float max = Settings.getF("sanitize.maxturn");
		Diagnostics.report(Diagnostics.REQ_TURN, Math.abs(delta), max);
		if (!enabled()) return delta;
		return Mth.clamp(delta, -max, max);
	}

	/** Applies {@link #clampHorizontal} then writes the velocity. */
	public static void setVelocity(LocalPlayer p, double x, double y, double z) {
		Vec3 v = clampHorizontal(p, new Vec3(x, y, z));
		p.setDeltaMovement(v.x, v.y, v.z);
	}

	/** Sends a movement-hop packet at {@code p}'s position plus a vertical offset limited to a vanilla-sized hop. */
	public static void sendHop(ClientPacketListener conn, LocalPlayer p, double dy) {
		Diagnostics.report(Diagnostics.PKT_HOP, Math.abs(dy), MAX_HOP);
		double hop = enabled() ? Mth.clamp(dy, -MAX_HOP, MAX_HOP) : dy;
		conn.send(new ServerboundMovePlayerPacket.Pos(p.getX(), p.getY() + hop, p.getZ(), false, p.horizontalCollision));
	}

	/** Reports ground contact only while the player is actually falling, so the server's fall state stays plausible. */
	public static void sendGround(ClientPacketListener conn, LocalPlayer p) {
		if (enabled() && p.getDeltaMovement().y > 0) return;
		conn.send(new ServerboundMovePlayerPacket.StatusOnly(true, p.horizontalCollision));
	}
}
