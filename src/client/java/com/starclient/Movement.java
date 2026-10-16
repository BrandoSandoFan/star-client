package com.starclient;

import net.minecraft.client.Minecraft;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.Items;
import net.minecraft.core.BlockPos;
import net.minecraft.tags.FluidTags;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.effect.MobEffects;

/** Small per-tick modules: Fullbright, Auto Sprint, Auto Walk, Fly, Reach, Jesus and the other movement tweaks. */
public final class Movement {
	/** Tunable values (speeds, angles, thresholds) live in {@link Settings}. Elytra Fly pitch is negative = up. */

	private static int rocketCooldown;
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
				double reach = Settings.get("reach.distance");
				entity.setBaseValue(reach);
				block.setBaseValue(reach);
			}
		}

		if (Freecam.isOn()) return;

		if (StarClient.flyOn) {
			Abilities ab = mc.player.getAbilities();
			ab.mayfly = true;
			ab.flying = true;
			ab.setFlyingSpeed(Settings.getF("fly.speed") * (mc.options.keySprint.isDown() ? Settings.getF("fly.sprint") : 1.0f));
		}

		if (StarClient.jesusOn && !mc.player.isShiftKeyDown() && !mc.options.keyJump.isDown()
				&& !mc.player.getAbilities().flying && !mc.player.isFallFlying() && !mc.player.isPassenger()) {
			BlockPos feet = mc.player.blockPosition();
			var fluid = mc.level.getFluidState(feet);
			if (fluid.is(FluidTags.WATER)) {
				Vec3 v = mc.player.getDeltaMovement();
				boolean submerged = mc.level.getFluidState(feet.above()).is(FluidTags.WATER);
				boolean belowSurface = mc.player.getY() - feet.getY() < fluid.getHeight(mc.level, feet);
				double rise = Settings.get("jesus.rise");
				mc.player.setDeltaMovement(v.x, submerged ? rise : belowSurface ? rise / 2 : 0.0, v.z);
			}
		}

		boolean grounded = mc.player.onGround();
		boolean controllable = mc.screen == null && !mc.player.isFallFlying() && !mc.player.getAbilities().flying
				&& !mc.player.isInWater() && !mc.player.isInLava() && !mc.player.isPassenger();

		// Softfall: once a fall is long enough to hurt, keep telling the server we are on the ground
		if (StarClient.softFallOn && mc.player.fallDistance > Settings.get("softfall.distance") && !grounded && !mc.player.isFallFlying()
				&& !mc.player.getAbilities().flying) {
			mc.player.connection.send(new ServerboundMovePlayerPacket.StatusOnly(true, mc.player.horizontalCollision));
		}

		if (StarClient.highJumpOn && controllable && mc.options.keyJump.isDown() && wasOnGround && !grounded) {
			Vec3 v = mc.player.getDeltaMovement();
			if (v.y > 0) mc.player.setDeltaMovement(v.x, Settings.get("highjump.velocity"), v.z);
		}
		wasOnGround = grounded;

		if (StarClient.speedOn && controllable) {
			double fwd = (mc.options.keyUp.isDown() ? 1 : 0) - (mc.options.keyDown.isDown() ? 1 : 0);
			double side = (mc.options.keyLeft.isDown() ? 1 : 0) - (mc.options.keyRight.isDown() ? 1 : 0);
			if (fwd != 0 || side != 0) {
				double len = Math.sqrt(fwd * fwd + side * side);
				double speed = Settings.get("speed.speed");
				double yaw = Math.toRadians(mc.player.getYRot());
				double sin = Math.sin(yaw);
				double cos = Math.cos(yaw);
				double x = (-sin * fwd + cos * side) / len * speed;
				double z = (cos * fwd + sin * side) / len * speed;
				Vec3 v = mc.player.getDeltaMovement();
				mc.player.setDeltaMovement(x, v.y, z);
			}
		}

		if (StarClient.autoWalkOn && mc.screen == null) {
			mc.options.keyUp.setDown(true);
		}
		if (StarClient.autoSprintOn && mc.options.keyUp.isDown() && !mc.player.isShiftKeyDown()
				&& mc.player.getFoodData().getFoodLevel() > Settings.get("autosprint.food")) {
			mc.player.setSprinting(true);
		}

		if (StarClient.elytraFlyOn && mc.screen == null && mc.player.isFallFlying()) {
			double speed = mc.player.getDeltaMovement().length();
			if (Settings.on("elytrafly.rockets")) autoRocket(mc, speed);
			if (climbing && speed < Settings.get("elytrafly.climbend")) climbing = false;
			else if (!climbing && speed > Settings.get("elytrafly.diveend")) climbing = true;
			float target = climbing ? -Settings.getF("elytrafly.climb") : Settings.getF("elytrafly.dive");
			float pitch = mc.player.getXRot();
			float maxStep = Settings.getF("elytrafly.step");
			float step = Math.max(-maxStep, Math.min(maxStep, (target - pitch) * 0.5f));
			mc.player.setXRot(pitch + step);
		}
	}

	public static void elytraReset() {
		climbing = false;
		rocketCooldown = 0;
	}

	/** Fires a firework rocket from the hotbar when gliding slower than the configured speed. */
	private static void autoRocket(Minecraft mc, double speed) {
		if (rocketCooldown > 0) {
			rocketCooldown--;
			return;
		}
		if (speed >= Settings.get("elytrafly.rocketspeed") || Utility.isEating()) return;
		var inv = mc.player.getInventory();
		int slot = -1;
		for (int i = 0; i < 9; i++) {
			if (inv.getItem(i).is(Items.FIREWORK_ROCKET)) {
				slot = i;
				if (i == inv.getSelectedSlot()) break;
			}
		}
		if (slot < 0) return;
		int prev = inv.getSelectedSlot();
		inv.setSelectedSlot(slot);
		mc.gameMode.useItem(mc.player, InteractionHand.MAIN_HAND);
		inv.setSelectedSlot(prev);
		rocketCooldown = Settings.getI("elytrafly.rocketdelay") * 20;
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
