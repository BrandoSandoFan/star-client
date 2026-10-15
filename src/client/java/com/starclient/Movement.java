package com.starclient;

import net.minecraft.client.Minecraft;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.player.Abilities;
import net.minecraft.world.effect.MobEffects;

/** Small per-tick modules: Fullbright, Auto Sprint, Auto Walk, Fly. */
public final class Movement {
	private static final float FLY_SPEED = 0.1f;

	private static boolean nightVisionApplied;

	private Movement() { }

	public static void tick(Minecraft mc) {
		if (StarClient.fullbrightOn) {
			if (!mc.player.hasEffect(MobEffects.NIGHT_VISION)) {
				mc.player.addEffect(new MobEffectInstance(MobEffects.NIGHT_VISION, 1_000_000, 0, false, false, false));
				nightVisionApplied = true;
			}
		}

		if (Freecam.isOn()) return;

		if (StarClient.flyOn) {
			Abilities ab = mc.player.getAbilities();
			ab.mayfly = true;
			ab.flying = true;
			ab.setFlyingSpeed(FLY_SPEED * (mc.options.keySprint.isDown() ? 2.0f : 1.0f));
		}

		if (StarClient.autoWalkOn && mc.screen == null) {
			mc.options.keyUp.setDown(true);
		}
		if (StarClient.autoSprintOn && mc.options.keyUp.isDown() && !mc.player.isShiftKeyDown()
				&& mc.player.getFoodData().getFoodLevel() > 6) {
			mc.player.setSprinting(true);
		}
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

	public static void reset() {
		nightVisionApplied = false;
	}
}
