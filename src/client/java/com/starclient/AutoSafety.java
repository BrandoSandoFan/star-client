package com.starclient;

import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Auto Log (disconnect at low or soon-to-be-low health) and Auto Water (water bucket clutch). */
public final class AutoSafety {
	/** Ring of health lost per tick, for the recent-damage prediction. */
	private static final float[] losses = new float[40];
	private static int lossIdx;
	private static float lastHealth = -1;

	private static final int W_IDLE = 0;
	private static final int W_PLACED = 1;
	private static int waterState = W_IDLE;
	private static int waterTicks;
	private static int waterPrevSlot = -1;
	private static boolean picked;
	private static int pickedAt;

	private AutoSafety() { }

	public static void tick(Minecraft mc) {
		if (StarClient.autoLogOn) autoLog(mc);
		if (StarClient.autoWaterOn) autoWater(mc);
	}

	private static void autoLog(Minecraft mc) {
		var p = mc.player;
		float hp = p.getHealth() + p.getAbsorptionAmount();
		float loss = lastHealth < 0 ? 0 : Math.max(0, lastHealth - hp);
		lastHealth = hp;
		losses[lossIdx] = loss;
		lossIdx = (lossIdx + 1) % losses.length;

		if (Settings.on("autolog.totem") && (p.getMainHandItem().is(Items.TOTEM_OF_UNDYING) || p.getOffhandItem().is(Items.TOTEM_OF_UNDYING))) return;

		float predicted = 0;
		if (Settings.on("autolog.predict")) {
			int window = Math.min(Settings.getI("autolog.window"), losses.length);
			for (int i = 1; i <= window; i++) predicted += losses[(lossIdx - i + losses.length) % losses.length];
		}
		if (Settings.on("autolog.fall")) predicted += fallDamage(mc, p);

		if (hp - predicted > Settings.get("autolog.health")) return;
		StarClient.autoLogOn = false;
		logReset();
		p.connection.getConnection().disconnect(Component.literal("Auto Log: health " + Math.round(hp) + (predicted > 0 ? ", expecting -" + Math.round(predicted) : "")));
	}

	/** Damage from the fall in progress: fall distance so far plus the drop to the ground below, unless that is water. */
	private static float fallDamage(Minecraft mc, Player p) {
		if (p.isFallFlying() || p.getAbilities().flying || p.isInWater() || p.getDeltaMovement().y >= 0) return 0;
		double so = p.fallDistance;
		if (so <= 0.5) return 0;
		Vec3 from = p.position();
		var hit = mc.level.clip(new ClipContext(from, from.add(0, -128, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, p));
		if (hit.getType() == HitResult.Type.MISS) return 0;
		if (mc.level.getFluidState(hit.getBlockPos()).is(FluidTags.WATER)) return 0;
		double total = so + (from.y - hit.getLocation().y);
		return (float) Math.max(0, Math.ceil(total - 3.0));
	}

	public static void logReset() {
		lastHealth = -1;
		java.util.Arrays.fill(losses, 0f);
		lossIdx = 0;
	}

	private static void autoWater(Minecraft mc) {
		var p = mc.player;
		if (mc.gameMode == null || mc.gui.screen() != null || p.isDeadOrDying()) return;
		var inv = p.getInventory();

		if (waterState == W_IDLE) {
			if (p.fallDistance < Settings.get("autowater.fall") || p.isFallFlying() || p.getAbilities().flying || p.isInWater() || p.onGround()
					|| p.getDeltaMovement().y >= 0 || mc.level.dimension() == Level.NETHER) return;
			int bucket = -1;
			for (int i = 0; i < 9 && bucket < 0; i++) if (inv.getItem(i).is(Items.WATER_BUCKET)) bucket = i;
			if (bucket < 0) return;
			Vec3 from = p.position();
			var below = mc.level.clip(new ClipContext(from, from.add(0, -24, 0), ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, p));
			if (below.getType() == HitResult.Type.MISS) return;

			if (inv.getSelectedSlot() != bucket) {
				if (waterPrevSlot < 0) waterPrevSlot = inv.getSelectedSlot();
				inv.setSelectedSlot(bucket);
			}
			// face straight down, within the per-tick turn limit
			float dp = ProtocolSanitizer.limitTurn(90.0f - p.getXRot());
			p.setXRot(Mth.clamp(p.getXRot() + dp, -90.0f, 90.0f));

			double reach = p.blockInteractionRange() - 0.3;
			double eyeToGround = p.getEyePosition().y - below.getLocation().y;
			if (p.getXRot() >= 80.0f && eyeToGround <= reach && mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.BLOCK) {
				mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
				waterState = W_PLACED;
				waterTicks = 0;
			}
			return;
		}

		waterTicks++;
		boolean landed = p.onGround() || p.isInWater();
		if (!landed) {
			// the bucket is still full after two ticks: the placement failed, try again
			if (waterTicks >= 2 && p.getMainHandItem().is(Items.WATER_BUCKET)) waterState = W_IDLE;
			else if (waterTicks > 80) waterReset();
			return;
		}
		if (Settings.on("autowater.pickup") && p.getMainHandItem().is(Items.BUCKET) && !picked && waterTicks < 60) {
			if (waterTicks >= 3) {
				mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
				picked = true;
				pickedAt = waterTicks;
			}
			return;
		}
		if (picked && waterTicks < pickedAt + 3) return;
		waterReset();
	}

	public static void waterReset() {
		var mc = Minecraft.getInstance();
		if (waterPrevSlot >= 0 && mc.player != null) mc.player.getInventory().setSelectedSlot(waterPrevSlot);
		waterPrevSlot = -1;
		waterState = W_IDLE;
		waterTicks = 0;
		picked = false;
	}

	public static void reset() {
		logReset();
		waterPrevSlot = -1;
		waterState = W_IDLE;
		waterTicks = 0;
		picked = false;
	}
}
