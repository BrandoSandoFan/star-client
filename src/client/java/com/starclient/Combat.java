package com.starclient;

import net.minecraft.client.Minecraft;
import net.minecraft.network.protocol.game.ServerboundMovePlayerPacket;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Aim Bot (smooth rotation toward the nearest target) and Hit Bot (attacks the entity under the crosshair when the cooldown is ready). */
public final class Combat {
	private static final double AIM_RANGE = 6.0;
	private static final float MAX_TURN = 8.0f;
	private static final float EASE = 0.2f;
	private static final float FOV = 90.0f;

	private Combat() { }

	public static void tick(Minecraft mc) {
		if (mc.screen != null || Freecam.isOn() || mc.gameMode == null || Utility.isEating()) return;
		if (!StarClient.aimBotOn && !StarClient.hitBotOn && !StarClient.killAuraOn) return;

		if (StarClient.aimBotOn) {
			LivingEntity target = nearest(mc, AIM_RANGE, FOV);
			if (target != null) aim(mc, target);
		}

		if (mc.player.getAttackStrengthScale(0.0f) < 0.95f) return;

		// Hit Bot only attacks what the crosshair is on
		if (StarClient.hitBotOn && mc.hitResult instanceof EntityHitResult hit
				&& hit.getEntity() instanceof LivingEntity target && isValid(mc, target)) {
			attack(mc, target);
			return;
		}

		// Kill Aura attacks the nearest valid target in reach, regardless of where you look
		if (StarClient.killAuraOn) {
			LivingEntity target = nearest(mc, mc.player.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE), 360.0f);
			if (target != null) attack(mc, target);
		}
	}

	/** Attacks and swings; with Crits on, first reports a tiny hop so the server counts the hit as a critical. */
	private static void attack(Minecraft mc, LivingEntity target) {
		var p = mc.player;
		if (StarClient.critsOn && p.onGround() && !p.isInWater() && !p.onClimbable() && !p.isPassenger()) {
			p.connection.send(new ServerboundMovePlayerPacket.Pos(p.getX(), p.getY() + 0.0625, p.getZ(), false, p.horizontalCollision));
			p.connection.send(new ServerboundMovePlayerPacket.Pos(p.getX(), p.getY(), p.getZ(), false, p.horizontalCollision));
		}
		mc.gameMode.attack(p, target);
		p.swing(InteractionHand.MAIN_HAND);
	}

	/**
	 * Hitboxes: runs at the start of the tick, before clicks are handled, and replaces the crosshair target with the
	 * nearest entity whose inflated bounding box the view ray crosses (unless a block is closer).
	 */
	public static void expandHitboxes(Minecraft mc) {
		if (!StarClient.hitboxesOn || mc.player == null || mc.level == null || mc.screen != null || Freecam.isOn()) return;
		double reach = mc.player.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE);
		float grow = Config.get().hitboxExpand;
		Vec3 eye = mc.player.getEyePosition();
		Vec3 end = eye.add(mc.player.getViewVector(1.0f).scale(reach));

		double best = reach * reach;
		if (mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.BLOCK) {
			best = Math.min(best, mc.hitResult.getLocation().distanceToSqr(eye));
		}
		Entity bestEntity = null;
		Vec3 bestPos = null;
		for (Entity e : mc.level.entitiesForRendering()) {
			if (e == mc.player || e.isSpectator() || !e.isPickable()) continue;
			AABB box = e.getBoundingBox().inflate(grow);
			Vec3 pos = box.contains(eye) ? eye : box.clip(eye, end).orElse(null);
			if (pos == null) continue;
			double d = pos.distanceToSqr(eye);
			if (d < best) {
				best = d;
				bestEntity = e;
				bestPos = pos;
			}
		}
		if (bestEntity != null) mc.hitResult = new EntityHitResult(bestEntity, bestPos);
	}

	private static boolean isValid(Minecraft mc, LivingEntity e) {
		return e != mc.player && (e instanceof Player || e instanceof Enemy)
				&& e.isAlive() && !e.isInvulnerable() && !e.isSpectator();
	}

	private static LivingEntity nearest(Minecraft mc, double range, float fov) {
		LivingEntity best = null;
		double bestDist = range * range;
		for (Entity e : mc.level.entitiesForRendering()) {
			if (!(e instanceof LivingEntity le) || !isValid(mc, le) || le.isInvisible()) continue;
			if (!mc.player.hasLineOfSight(le)) continue;
			double d = mc.player.distanceToSqr(le);
			if (d < bestDist && yawDelta(mc, le) <= fov / 2) {
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
