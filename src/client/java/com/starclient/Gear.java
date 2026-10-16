package com.starclient;

import java.util.Arrays;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.DeathScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.component.DataComponents;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.item.equipment.Equippable;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/** Hotbar and inventory helpers: Auto Tool, Auto Totem, Auto Armor, Auto Replenish and Auto Respawn. */
public final class Gear {
	/** Slot Auto Tool switched away from, and the slot it switched to (-1 when idle). */
	private static int toolPrev = -1;
	private static int toolSet = -1;

	/** Player inventory menu slots: armor 5-8 (head to feet), main 9-35, hotbar 36-44, offhand 45. */
	private static final int ARMOR_FIRST = 5;
	private static final int MAIN_FIRST = 9;
	private static final int HOTBAR_FIRST = 36;
	private static final int OFFHAND = 45;
	private static final EquipmentSlot[] ARMOR = {EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET};

	/** Ticks left before the next inventory click sequence. */
	private static int cooldown;
	private static int respawnWait;
	/** Item last seen in each hotbar slot (null when empty), for Auto Replenish. */
	private static final Item[] lastHotbar = new Item[9];

	private Gear() { }

	public static void tick(Minecraft mc) {
		if (StarClient.autoToolOn) autoTool(mc);
		if (StarClient.autoRespawnOn) autoRespawn(mc);
		trackHotbar(mc);
		if (cooldown > 0) cooldown--;
		if (cooldown > 0 || !canClick(mc)) return;
		if (StarClient.autoTotemOn && autoTotem(mc)) return;
		if (StarClient.autoArmorOn && autoArmor(mc)) return;
		if (StarClient.autoReplenishOn) autoReplenish(mc);
	}

	/** True when inventory clicks are safe: the player menu is the open menu, nothing is on the cursor and the Sorter is idle. */
	private static boolean canClick(Minecraft mc) {
		var p = mc.player;
		return mc.gameMode != null && !StarClient.sorterOn && !p.isDeadOrDying()
				&& p.containerMenu == p.inventoryMenu && p.inventoryMenu.getCarried().isEmpty();
	}

	private static void click(Minecraft mc, int slot) {
		var p = mc.player;
		mc.gameMode.handleContainerInput(p.inventoryMenu.containerId, slot, 0, ContainerInput.PICKUP, p);
	}

	private static ItemStack slotItem(Minecraft mc, int slot) {
		return mc.player.inventoryMenu.getSlot(slot).getItem();
	}

	private static boolean autoTotem(Minecraft mc) {
		var p = mc.player;
		ItemStack off = p.getOffhandItem();
		if (off.is(Items.TOTEM_OF_UNDYING)) return false;
		boolean fill = off.isEmpty() && Settings.on("autototem.empty");
		if (!fill && p.getHealth() > Settings.get("autototem.health")) return false;

		int src = -1;
		for (int s = MAIN_FIRST; s < OFFHAND && src < 0; s++) {
			if (slotItem(mc, s).is(Items.TOTEM_OF_UNDYING)) src = s;
		}
		if (src < 0) return false;

		click(mc, src);
		click(mc, OFFHAND);
		if (!off.isEmpty()) click(mc, src);
		cooldown = Settings.getI("autototem.delay");
		return true;
	}

	/** Armor attribute value of {@code stack} in {@code slot}, with toughness at half weight. */
	private static double armorScore(ItemStack stack, EquipmentSlot slot) {
		double[] sum = {0};
		stack.forEachModifier(slot, (attribute, modifier) -> {
			if (attribute.equals(Attributes.ARMOR)) sum[0] += modifier.amount();
			else if (attribute.equals(Attributes.ARMOR_TOUGHNESS)) sum[0] += modifier.amount() * 0.5;
		});
		return sum[0];
	}

	private static boolean autoArmor(Minecraft mc) {
		for (int a = 0; a < ARMOR.length; a++) {
			ItemStack worn = slotItem(mc, ARMOR_FIRST + a);
			if (worn.is(Items.ELYTRA) && Settings.on("autoarmor.elytra")) continue;
			double best = worn.isEmpty() ? 0 : armorScore(worn, ARMOR[a]);
			int src = -1;
			for (int s = MAIN_FIRST; s < OFFHAND; s++) {
				ItemStack st = slotItem(mc, s);
				Equippable eq = st.get(DataComponents.EQUIPPABLE);
				if (eq == null || eq.slot() != ARMOR[a]) continue;
				double score = armorScore(st, ARMOR[a]);
				if (score > best) {
					best = score;
					src = s;
				}
			}
			if (src < 0) continue;

			click(mc, src);
			click(mc, ARMOR_FIRST + a);
			if (!worn.isEmpty()) click(mc, src);
			cooldown = Settings.getI("autoarmor.delay");
			return true;
		}
		return false;
	}

	/** Remembers what each hotbar slot held; follows the screen contents while one is open so manual moves never count as use. */
	private static void trackHotbar(Minecraft mc) {
		for (int i = 0; i < 9; i++) {
			ItemStack st = mc.player.getInventory().getItem(i);
			if (!st.isEmpty()) lastHotbar[i] = st.getItem();
			else if (mc.screen != null) lastHotbar[i] = null;
		}
	}

	private static void autoReplenish(Minecraft mc) {
		if (mc.screen != null) return;
		var p = mc.player;
		int threshold = Settings.getI("autoreplenish.threshold");
		for (int i = 0; i < 9; i++) {
			ItemStack st = p.getInventory().getItem(i);
			boolean used = st.isEmpty() && lastHotbar[i] != null;
			boolean low = !st.isEmpty() && st.getMaxStackSize() > 1 && st.getCount() <= threshold && st.getCount() < st.getMaxStackSize();
			if (!used && !low) continue;

			int src = -1;
			for (int s = MAIN_FIRST; s < HOTBAR_FIRST && src < 0; s++) {
				ItemStack cand = slotItem(mc, s);
				if (cand.isEmpty()) continue;
				if (used ? cand.is(lastHotbar[i]) : ItemStack.isSameItemSameComponents(cand, st)) src = s;
			}
			if (used) lastHotbar[i] = null;
			if (src < 0) continue;

			click(mc, src);
			click(mc, HOTBAR_FIRST + i);
			if (!p.inventoryMenu.getCarried().isEmpty()) click(mc, src);
			cooldown = Settings.getI("autoreplenish.delay");
			return;
		}
	}

	public static void replenishReset() {
		Arrays.fill(lastHotbar, null);
	}

	private static void autoRespawn(Minecraft mc) {
		if (mc.screen instanceof DeathScreen && mc.player.isDeadOrDying()) {
			if (++respawnWait > Settings.getI("autorespawn.delay")) {
				respawnWait = 0;
				mc.player.respawn();
			}
		} else {
			respawnWait = 0;
		}
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
		cooldown = 0;
		respawnWait = 0;
		replenishReset();
	}
}
