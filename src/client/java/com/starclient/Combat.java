package com.starclient;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.Vec3;

/** Aim Bot (smooth rotation toward the nearest target) and Hit Bot (auto attack when the cooldown is ready). */
public final class Combat {
	private static final double AIM_RANGE = 6.0;
	private static final float MAX_TURN = 12.0f;
	private static final float FOV = 90.0f;

	private Combat() { }

	public static void tick(Minecraft mc) {
		if (mc.screen != null || Freecam.isOn() || mc.gameMode == null) return;
		if (!StarClient.aimBotOn && !StarClient.hitBotOn) return;

		LivingEntity target = nearest(mc, StarClient.aimBotOn ? AIM_RANGE : mc.player.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE));
		if (target == null) return;

		if (StarClient.aimBotOn) aim(mc, target);

		if (StarClient.hitBotOn && mc.player.getAttackStrengthScale(0.0f) >= 0.95f
				&& mc.player.distanceTo(target) <= mc.player.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE)) {
			mc.gameMode.attack(mc.player, target);
			mc.player.swing(InteractionHand.MAIN_HAND);
		}
	}

	private static LivingEntity nearest(Minecraft mc, double range) {
		LivingEntity best = null;
		double bestDist = range * range;
		for (Entity e : mc.level.entitiesForRendering()) {
			if (!(e instanceof LivingEntity le) || e == mc.player || e == mc.getCameraEntity()) continue;
			if (!(e instanceof Player || e instanceof Enemy)) continue;
			if (!le.isAlive() || le.isInvulnerable() || le.isSpectator() || le.isInvisible()) continue;
			if (!mc.player.hasLineOfSight(le)) continue;
			double d = mc.player.distanceToSqr(le);
			if (d < bestDist && yawDelta(mc, le) <= FOV / 2) {
				bestDist = d;
				best = le;
			}
		}
		return best;
	}

	private static float yawDelta(Minecraft mc, LivingEntity t) {
		Vec3 eye = mc.player.getEyePosition();
		Vec3 to = t.getBoundingBox().getCenter().subtract(eye);
		float yaw = (float) (Mth.atan2(to.z, to.x) * (180.0 / Math.PI)) - 90.0f;
		return Math.abs(Mth.wrapDegrees(yaw - mc.player.getYRot()));
	}

	private static void aim(Minecraft mc, LivingEntity t) {
		Vec3 eye = mc.player.getEyePosition();
		Vec3 to = t.getBoundingBox().getCenter().subtract(eye);
		double horiz = Math.sqrt(to.x * to.x + to.z * to.z);
		float yaw = (float) (Mth.atan2(to.z, to.x) * (180.0 / Math.PI)) - 90.0f;
		float pitch = (float) -(Mth.atan2(to.y, horiz) * (180.0 / Math.PI));
		float dy = Mth.clamp(Mth.wrapDegrees(yaw - mc.player.getYRot()), -MAX_TURN, MAX_TURN);
		float dp = Mth.clamp(pitch - mc.player.getXRot(), -MAX_TURN, MAX_TURN);
		mc.player.setYRot(mc.player.getYRot() + dy);
		mc.player.setXRot(Mth.clamp(mc.player.getXRot() + dp, -90.0f, 90.0f));
	}
}
