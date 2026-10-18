package com.starclient;

import net.minecraft.client.Minecraft;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Aim Bot (smooth rotation toward the nearest target) and Trigger Bot (attacks the entity under the crosshair when the cooldown is ready). */
public final class Combat {
	/** Game time of the last Trigger Bot / Kill Aura attack, for the configured minimum delay. */
	private static long lastAttack = Long.MIN_VALUE / 2;
	private static final AimSpring yawSpring = new AimSpring();
	private static final AimSpring pitchSpring = new AimSpring();
	private static int springTarget = -1;

	private Combat() { }

	private static boolean delayElapsed(Minecraft mc, String key) {
		return mc.level.getGameTime() - lastAttack >= Settings.getI(key);
	}

	public static void tick(Minecraft mc) {
		if (mc.gui.screen() != null || Freecam.isOn() || mc.gameMode == null || Utility.isEating()) return;
		if (!StarClient.aimBotOn && !StarClient.hitBotOn && !StarClient.killAuraOn && !StarClient.maceAuraOn) return;

		if (StarClient.aimBotOn) {
			LivingEntity target = nearest(mc, "aimbot", Settings.get("aimbot.range"), Settings.getF("aimbot.fov"));
			if (target != null) aim(mc, target);
			else springTarget = -1;
		}

		float charge = mc.player.getAttackStrengthScale(0.0f);

		// Mace Aura: a smash attack needs a real fall, so this only fires while falling with a mace in the hotbar
		if (StarClient.maceAuraOn && maceAura(mc, charge)) return;

		// Trigger Bot only attacks what the crosshair is on
		if (StarClient.hitBotOn && charge >= Settings.getF("hitbot.charge") && delayElapsed(mc, "hitbot.delay") && mc.hitResult instanceof EntityHitResult hit
				&& hit.getEntity() instanceof LivingEntity target
				&& isValid(mc, target, Settings.on("hitbot.players"), Settings.on("hitbot.hostiles"))) {
			attack(mc, target, "hitbot.delay");
			return;
		}

		// Kill Aura attacks the nearest valid target in reach, regardless of where you look
		if (StarClient.killAuraOn && charge >= Settings.getF("killaura.charge") && delayElapsed(mc, "killaura.delay")) {
			double range = Settings.get("killaura.range");
			if (range <= 0) range = mc.player.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE);
			LivingEntity target = nearest(mc, "killaura", range, 360.0f);
			if (target != null) attack(mc, target, "killaura.delay");
		}
	}

	private static boolean maceAura(Minecraft mc, float charge) {
		var p = mc.player;
		if (charge < Settings.getF("maceaura.charge") || !delayElapsed(mc, "maceaura.delay")) return false;
		if (p.onGround() || p.isFallFlying() || p.getAbilities().flying || p.isInWater() || p.onClimbable() || p.isPassenger()) return false;
		if (p.fallDistance < Settings.get("maceaura.fall")) return false;
		var inv = p.getInventory();
		int mace = -1;
		if (p.getMainHandItem().is(Items.MACE)) mace = inv.getSelectedSlot();
		else for (int i = 0; i < 9 && mace < 0; i++) if (inv.getItem(i).is(Items.MACE)) mace = i;
		if (mace < 0) return false;
		double range = Settings.get("maceaura.range");
		if (range <= 0) range = p.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE);
		LivingEntity target = nearest(mc, "maceaura", range, 360.0f);
		if (target == null) return false;
		int previous = inv.getSelectedSlot();
		inv.setSelectedSlot(mace);
		attack(mc, target, "maceaura.delay");
		inv.setSelectedSlot(previous);
		return true;
	}

	/** Attacks and swings; with Crits on, first reports a tiny hop so the server counts the hit as a critical. */
	private static void attack(Minecraft mc, LivingEntity target, String delayKey) {
		var p = mc.player;
		if (StarClient.critsOn && p.onGround() && !p.isInWater() && !p.onClimbable() && !p.isPassenger()) {
			ProtocolSanitizer.sendHop(p.connection, p, Settings.get("crits.hop"));
			ProtocolSanitizer.sendHop(p.connection, p, 0.0);
		}
		long now = mc.level.getGameTime();
		Diagnostics.attack(now, Settings.getI(delayKey));
		lastAttack = now;
		AttributeSwap.around(mc, () -> mc.gameMode.attack(p, target));
		p.swing(InteractionHand.MAIN_HAND);
	}

	/**
	 * Hitboxes: runs at the start of the tick, before clicks are handled, and replaces the crosshair target with the
	 * nearest entity whose inflated bounding box the view ray crosses (unless a block is closer).
	 */
	public static void expandHitboxes(Minecraft mc) {
		if (!StarClient.hitboxesOn || mc.player == null || mc.level == null || mc.gui.screen() != null || Freecam.isOn()) return;
		double reach = mc.player.getAttributeValue(Attributes.ENTITY_INTERACTION_RANGE);
		float grow = Settings.getF("hitboxes.expand");
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

	private static boolean isValid(Minecraft mc, LivingEntity e, boolean players, boolean hostiles) {
		return e != mc.player && ((players && e instanceof Player) || (hostiles && e instanceof Enemy))
				&& e.isAlive() && !e.isInvulnerable() && !e.isSpectator();
	}

	private static LivingEntity nearest(Minecraft mc, String module, double range, float fov) {
		LivingEntity best = null;
		double bestDist = range * range;
		boolean players = Settings.on(module + ".players");
		boolean hostiles = Settings.on(module + ".hostiles");
		for (Entity e : mc.level.entitiesForRendering()) {
			if (!(e instanceof LivingEntity le) || !isValid(mc, le, players, hostiles) || le.isInvisible()) continue;
			// Cheap distance and angle tests first; the line-of-sight raycast last.
			double d = mc.player.distanceToSqr(le);
			if (d >= bestDist || yawDelta(mc, le) > fov / 2 || !mc.player.hasLineOfSight(le)) continue;
			bestDist = d;
			best = le;
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
		float ease = Settings.getF("aimbot.ease");
		float maxTurn = Settings.getF("aimbot.turn");
		float errYaw = Mth.wrapDegrees(yaw - mc.player.getYRot());
		float errPitch = pitch - mc.player.getXRot();
		float dy;
		float dp;
		if (Settings.on("aimbot.spring")) {
			if (springTarget != t.getId()) {
				yawSpring.reset();
				pitchSpring.reset();
				springTarget = t.getId();
			}
			double omega = Settings.get("aimbot.omega");
			float cap = Math.min(maxTurn, ProtocolSanitizer.turnLimit());
			dy = ProtocolSanitizer.limitTurn(yawSpring.step(errYaw, omega, cap));
			dp = ProtocolSanitizer.limitTurn(pitchSpring.step(errPitch, omega, cap));
		} else {
			dy = ProtocolSanitizer.limitTurn(Mth.clamp(errYaw * ease, -maxTurn, maxTurn));
			dp = ProtocolSanitizer.limitTurn(Mth.clamp(errPitch * ease, -maxTurn, maxTurn));
		}
		mc.player.setYRot(mc.player.getYRot() + dy);
		mc.player.setXRot(Mth.clamp(mc.player.getXRot() + dp, -90.0f, 90.0f));
	}
}
