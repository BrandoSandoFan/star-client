package com.starclient;

import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.effect.MobEffects;

/** Small per-tick modules: Fullbright, Auto Sprint, Auto Walk, Fly, Reach. */
public final class Movement {
	private static final float FLY_SPEED = 0.1f;

	/** Elytra Fly: dive to build speed, then climb until it bleeds off. Speeds in blocks/tick, angles in degrees (negative = up). */
	private static final float DIVE_PITCH = 35f;
	private static final float CLIMB_PITCH = -45f;
	private static final float PITCH_STEP = 6f;
	private static final double DIVE_END_SPEED = 1.7;
	private static final double CLIMB_END_SPEED = 0.9;

	/** Speed: horizontal blocks/tick while moving (vanilla sprint is about 0.28). High Jump: upward blocks/tick on take-off (vanilla is 0.42). */
	private static final double SPEED = 0.45;
	private static final double HIGH_JUMP_VELOCITY = 0.9;

	private static boolean wasOnGround;
	private static boolean climbing;
	private static boolean nightVisionApplied;
	private static double origEntityRange = -1;
	private static double origBlockRange = -1;

	private Movement() { }

	public static void tick(Minecraft mc) {
		if (StarClient.fullbrightOn) {
			if (!mc.player.hasEffect(MobEffects.NIGHT_VISION)) {
				mc.player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 1_000_000, 0, false, false, false));
				nightVisionApplied = true;
			}
		}

		if (StarClient.reachOn) {
			AttributeInstance entity = mc.player.getAttribute(Attributes.ENTITY_INTERACTION_RANGE);
			AttributeInstance block = mc.player.getAttribute(Attributes.BLOCK_INTERACTION_RANGE);
			if (entity != null && block != null) {
				if (origEntityRange < 0) {
					origEntityRange = entity.getBaseValue();
					origBlockRange = block.getBaseValue();
				}
				double reach = Config.get().reachDistance;
				entity.setBaseValue(reach);
				block.setBaseValue(reach);
			}
		}

		if (Freecam.isOn()) return;

		if (StarClient.flyOn) {
			Abilities ab = mc.player.getAbilities();
			ab.mayfly = true;
			ab.flying = true;
			ab.setFlyingSpeed(FLY_SPEED * (mc.options.keySprint.isDown() ? 2.0f : 1.0f));
		}

		boolean grounded = mc.player.onGround();
		boolean controllable = mc.screen == null && !mc.player.isFallFlying() && !mc.player.getAbilities().flying
				&& !mc.player.isInWater() && !mc.player.isInLava() && !mc.player.isPassenger();

		// No Fall: once a fall is long enough to hurt, keep telling the server we are on the ground
		if (StarClient.noFallOn && mc.player.fallDistance > 2.0 && !grounded && !mc.player.isFallFlying()
				&& !mc.player.getAbilities().flying) {
			mc.player.connection.send(new ServerboundMovePlayerPacket.StatusOnly(true, mc.player.horizontalCollision));
		}

		if (StarClient.highJumpOn && controllable && mc.options.keyJump.isDown() && wasOnGround && !grounded) {
			Vec3 v = mc.player.getDeltaMovement();
			if (v.y > 0) mc.player.setDeltaMovement(v.x, HIGH_JUMP_VELOCITY, v.z);
		}
		wasOnGround = grounded;

		if (StarClient.speedOn && controllable) {
			double fwd = (mc.options.keyUp.isDown() ? 1 : 0) - (mc.options.keyDown.isDown() ? 1 : 0);
			double side = (mc.options.keyLeft.isDown() ? 1 : 0) - (mc.options.keyRight.isDown() ? 1 : 0);
			if (fwd != 0 || side != 0) {
				double len = Math.sqrt(fwd * fwd + side * side);
				double yaw = Math.toRadians(mc.player.getYRot());
				double sin = Math.sin(yaw);
				double cos = Math.cos(yaw);
				double x = (-sin * fwd + cos * side) / len * SPEED;
				double z = (cos * fwd + sin * side) / len * SPEED;
				Vec3 v = mc.player.getDeltaMovement();
				mc.player.setDeltaMovement(x, v.y, z);
			}
		}

		if (StarClient.autoWalkOn && mc.screen == null) {
			mc.options.keyUp.setDown(true);
		}
		if (StarClient.autoSprintOn && mc.options.keyUp.isDown() && !mc.player.isShiftKeyDown()
				&& mc.player.getFoodData().getFoodLevel() > 6) {
			mc.player.setSprinting(true);
		}

		if (StarClient.elytraFlyOn && mc.screen == null && mc.player.isFallFlying()) {
			double speed = mc.player.getDeltaMovement().length();
			if (climbing && speed < CLIMB_END_SPEED) climbing = false;
			else if (!climbing && speed > DIVE_END_SPEED) climbing = true;
			float target = climbing ? CLIMB_PITCH : DIVE_PITCH;
			float pitch = mc.player.getXRot();
			float step = Math.max(-PITCH_STEP, Math.min(PITCH_STEP, (target - pitch) * 0.5f));
			mc.player.setXRot(pitch + step);
		}
	}

	public static void elytraReset() {
		climbing = false;
	}

	public static void fullbrightOff(Minecraft mc) {
		if (nightVisionApplied && mc.player != null) mc.player.removeEffect(MobEffects.NIGHT_VISION);
		nightVisionApplied = false;
	}

	public static void flyOff(Minecraft mc) {
		if (mc.player == null) return;
		Abilities ab = mc.player.getAbilities();
		ab.setFlyingSpeed(0.05f);
		if (!mc.player.isCreative() && !mc.player.isSpectator()) {
			ab.mayfly = false;
			ab.flying = false;
		}
	}

	public static void autoWalkOff(Minecraft mc) {
		mc.options.keyUp.setDown(false);
	}

	public static void reachOff(Minecraft mc) {
		if (mc.player != null && origEntityRange >= 0) {
			AttributeInstance entity = mc.player.getAttribute(Attributes.ENTITY_INTERACTION_RANGE);
			AttributeInstance block = mc.player.getAttribute(Attributes.BLOCK_INTERACTION_RANGE);
			if (entity != null) entity.setBaseValue(origEntityRange);
			if (block != null) block.setBaseValue(origBlockRange);
		}
		origEntityRange = -1;
		origBlockRange = -1;
	}

	public static void reset() {
		origEntityRange = -1;
		origBlockRange = -1;
		nightVisionApplied = false;
		wasOnGround = false;
	}
}
