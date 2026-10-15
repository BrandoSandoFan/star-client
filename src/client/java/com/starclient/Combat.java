package com.starclient;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

/** Aim Bot (smooth rotation toward the nearest target) and Hit Bot (attacks the entity under the crosshair when the cooldown is ready). */
public final class Combat {
	private static final double AIM_RANGE = 6.0;
	private static final float MAX_TURN = 8.0f;
	private static final float EASE = 0.2f;
	private static final float FOV = 90.0f;

	private Combat() { }

	public static void tick(Minecraft mc) {
		if (mc.screen != null || Freecam.isOn() || mc.gameMode == null) return;
		if (!StarClient.aimBotOn && !StarClient.hitBotOn) return;

		if (StarClient.aimBotOn) {
			LivingEntity target = nearest(mc, AIM_RANGE);
			if (target != null) aim(mc, target);
		}

		// Hit Bot only attacks what the crosshair is on, once the cooldown is ready
		if (StarClient.hitBotOn && mc.hitResult instanceof EntityHitResult hit
				&& hit.getEntity() instanceof LivingEntity target && isValid(mc, target)
				&& mc.player.getAttackStrengthScale(0.0f) >= 0.95f) {
			mc.gameMode.attack(mc.player, target);
			mc.player.swing(InteractionHand.MAIN_HAND);
		}
	}

	private static boolean isValid(Minecraft mc, LivingEntity e) {
		return e != mc.player && (e instanceof Player || e instanceof Enemy)
				&& e.isAlive() && !e.isInvulnerable() && !e.isSpectator();
	}

	private static LivingEntity nearest(Minecraft mc, double range) {
		LivingEntity best = null;
		double bestDist = range * range;
		for (Entity e : mc.level.entitiesForRendering()) {
			if (!(e instanceof LivingEntity le) || !isValid(mc, le) || le.isInvisible()) continue;
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
		float dy = Mth.clamp(Mth.wrapDegrees(yaw - mc.player.getYRot()) * EASE, -MAX_TURN, MAX_TURN);
		float dp = Mth.clamp((pitch - mc.player.getXRot()) * EASE, -MAX_TURN, MAX_TURN);
		mc.player.setYRot(mc.player.getYRot() + dy);
		mc.player.setXRot(Mth.clamp(mc.player.getXRot() + dp, -90.0f, 90.0f));
	}
}
