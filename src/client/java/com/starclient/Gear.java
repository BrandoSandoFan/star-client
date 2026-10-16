package com.starclient;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/** Hotbar and inventory helpers: Auto Tool. */
public final class Gear {
	/** Slot Auto Tool switched away from, and the slot it switched to (-1 when idle). */
	private static int toolPrev = -1;
	private static int toolSet = -1;

	private Gear() { }

	public static void tick(Minecraft mc) {
		if (StarClient.autoToolOn) autoTool(mc);
	}

	private static float speed(ItemStack stack, BlockState state) {
		if (stack.isEmpty()) return 1.0f;
		if (stack.isDamageableItem() && stack.getMaxDamage() - stack.getDamageValue() <= Settings.getI("autotool.protect")) return 0.0f;
		return stack.getDestroySpeed(state);
	}

	private static void autoTool(Minecraft mc) {
		var p = mc.player;
		Inventory inv = p.getInventory();
		boolean mining = mc.screen == null && mc.options.keyAttack.isDown() && !p.isUsingItem() && !Utility.isEating()
				&& !p.getAbilities().instabuild && mc.hitResult instanceof BlockHitResult hit && hit.getType() == HitResult.Type.BLOCK;
		if (!mining) {
			autoToolOff(mc);
			return;
		}

		BlockPos pos = ((BlockHitResult) mc.hitResult).getBlockPos();
		BlockState state = mc.level.getBlockState(pos);
		if (state.isAir()) return;

		int cur = inv.getSelectedSlot();
		float best = speed(inv.getItem(cur), state);
		int bestSlot = cur;
		for (int i = 0; i < 9; i++) {
			float s = speed(inv.getItem(i), state);
			if (s > best + 0.01f) {
				best = s;
				bestSlot = i;
			}
		}
		if (bestSlot == cur || best <= 1.0f) return;
		if (toolPrev < 0) toolPrev = cur;
		toolSet = bestSlot;
		inv.setSelectedSlot(bestSlot);
	}

	/** Switches back to the slot Auto Tool left, unless you changed slot yourself meanwhile. */
	public static void autoToolOff(Minecraft mc) {
		if (toolPrev >= 0 && mc.player != null && Settings.on("autotool.restore") && mc.player.getInventory().getSelectedSlot() == toolSet) {
			mc.player.getInventory().setSelectedSlot(toolPrev);
		}
		toolPrev = -1;
		toolSet = -1;
	}

	public static void reset() {
		toolPrev = -1;
		toolSet = -1;
	}
}
