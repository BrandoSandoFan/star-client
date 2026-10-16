package com.starclient;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Set;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.inventory.ShulkerBoxMenu;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.phys.Vec3;

/** Auto Eat, Anti AFK and the Sorter (player inventory, or an open chest, ender chest, barrel or shulker box). */
public final class Utility {
	/** Auto Eat starts when the food level is at or below this (20 is full). */
	private static final Set<Item> NOT_FOOD = Set.of(Items.ROTTEN_FLESH, Items.SPIDER_EYE, Items.PUFFERFISH, Items.POISONOUS_POTATO,
			Items.CHORUS_FRUIT, Items.CHICKEN, Items.GOLDEN_APPLE, Items.ENCHANTED_GOLDEN_APPLE, Items.SUSPICIOUS_STEW);

	/** Anti AFK: ticks without any movement before a jump and look nudge, and the nudge size in degrees. */

	/** Sorter: main inventory slots in the player menu, and swaps/merges done per tick. */
	private static final int INV_FIRST = 9;
	private static final int INV_END = 36;

	private static boolean eating;
	private static int eatPrevSlot;

	private static Vec3 afkLastPos;
	private static float afkLastYaw;
	private static int afkIdle;
	private static int afkJumpTicks;
	private static boolean afkFlip;

	private static final ArrayDeque<int[]> sortOps = new ArrayDeque<>();
	private static int sortMenuId = -1;

	private Utility() { }

	public static void tick(Minecraft mc) {
		if (StarClient.autoEatOn) autoEat(mc);
		if (StarClient.antiAfkOn) antiAfk(mc);
		if (StarClient.sorterOn) sort(mc);
	}

	public static boolean isEating() {
		return eating;
	}

	private static void autoEat(Minecraft mc) {
		var p = mc.player;
		if (eating) {
			if (p.isUsingItem() && mc.screen == null) {
				mc.options.keyUse.setDown(true);
				return;
			}
			finishEating(mc);
			return;
		}
		if (mc.screen != null || p.isUsingItem() || mc.gameMode == null) return;
		var food = p.getFoodData();
		if (!food.needsFood() || food.getFoodLevel() > Settings.getI("autoeat.hunger")) return;

		int best = -1;
		int bestNutrition = 0;
		for (int i = 0; i < 9; i++) {
			ItemStack stack = p.getInventory().getItem(i);
			var props = stack.get(DataComponents.FOOD);
			if (props == null || NOT_FOOD.contains(stack.getItem())) continue;
			if (props.nutrition() > bestNutrition) {
				best = i;
				bestNutrition = props.nutrition();
			}
		}
		if (best < 0) return;

		eatPrevSlot = p.getInventory().getSelectedSlot();
		p.getInventory().setSelectedSlot(best);
		mc.gameMode.useItem(p, InteractionHand.MAIN_HAND);
		if (p.isUsingItem()) {
			eating = true;
			mc.options.keyUse.setDown(true);
		} else {
			p.getInventory().setSelectedSlot(eatPrevSlot);
		}
	}

	private static void finishEating(Minecraft mc) {
		eating = false;
		mc.options.keyUse.setDown(false);
		if (mc.player != null) mc.player.getInventory().setSelectedSlot(eatPrevSlot);
	}

	public static void autoEatOff(Minecraft mc) {
		if (eating) finishEating(mc);
	}

	private static void antiAfk(Minecraft mc) {
		var p = mc.player;
		if (afkJumpTicks > 0) {
			afkJumpTicks--;
			mc.options.keyJump.setDown(afkJumpTicks > 0);
		}
		Vec3 pos = p.position();
		boolean moved = afkLastPos == null || pos.distanceToSqr(afkLastPos) > 0.0004 || Math.abs(p.getYRot() - afkLastYaw) > 0.01f;
		afkIdle = moved || mc.screen != null ? 0 : afkIdle + 1;
		if (afkIdle >= Settings.getI("antiafk.seconds") * 20) {
			afkIdle = 0;
			afkFlip = !afkFlip;
			p.setYRot(p.getYRot() + ProtocolSanitizer.limitTurn((afkFlip ? 1 : -1) * Settings.getF("antiafk.nudge")));
			afkJumpTicks = 2;
			mc.options.keyJump.setDown(true);
		}
		afkLastPos = pos;
		afkLastYaw = p.getYRot();
	}

	public static void antiAfkOff(Minecraft mc) {
		if (afkJumpTicks > 0) mc.options.keyJump.setDown(false);
		afkJumpTicks = 0;
		afkIdle = 0;
		afkLastPos = null;
	}

	/** Plans a merge-then-sort of the main inventory; {@link #sort} then replays it a few clicks per tick. */
	public static void sorterStart(Minecraft mc) {
		var p = mc.player;
		if (p == null) {
			StarClient.sorterOn = false;
			return;
		}
		plan(mc, p.inventoryMenu, INV_FIRST, INV_END);
	}

	/** True for chests, ender chests, barrels (all {@link ChestMenu}) and shulker boxes. */
	public static boolean isSortableContainer(AbstractContainerMenu menu) {
		return menu instanceof ChestMenu || menu instanceof ShulkerBoxMenu;
	}

	/** Slots in the container part of a chest-like menu; the 36 player slots follow. */
	public static int containerSize(AbstractContainerMenu menu) {
		return menu.slots.size() - 36;
	}

	/** Sorts the chest, ender chest, barrel or shulker box that is currently open. */
	public static void sortOpenContainer(Minecraft mc) {
		var p = mc.player;
		if (p == null || !isSortableContainer(p.containerMenu)) return;
		StarClient.sorterOn = true;
		plan(mc, p.containerMenu, 0, containerSize(p.containerMenu));
	}

	private static void plan(Minecraft mc, AbstractContainerMenu menu, int first, int end) {
		sortOps.clear();
		var p = mc.player;
		if (mc.gameMode == null || p.containerMenu != menu || !menu.getCarried().isEmpty()) {
			StarClient.sorterOn = false;
			return;
		}
		sortMenuId = menu.containerId;
		int n = end - first;
		ItemStack[] sim = new ItemStack[n];
		for (int i = 0; i < n; i++) sim[i] = menu.getSlot(first + i).getItem().copy();

		// merge partial stacks of the same item into the earliest one
		for (int i = 0; i < n; i++) {
			if (sim[i].isEmpty()) continue;
			int max = sim[i].getMaxStackSize();
			for (int j = i + 1; j < n && sim[i].getCount() < max; j++) {
				if (sim[j].isEmpty() || !ItemStack.isSameItemSameComponents(sim[i], sim[j])) continue;
				int moved = Math.min(max - sim[i].getCount(), sim[j].getCount());
				sortOps.add(new int[] { first + j, first + i, first + j });
				sim[i].grow(moved);
				sim[j].shrink(moved);
				if (sim[j].isEmpty()) sim[j] = ItemStack.EMPTY;
			}
		}

		// selection sort by item id then count (largest first), empty slots last
		Comparator<ItemStack> order = (a, b) -> {
			if (a.isEmpty() || b.isEmpty()) return Boolean.compare(a.isEmpty(), b.isEmpty());
			int c = BuiltInRegistries.ITEM.getKey(a.getItem()).toString().compareTo(BuiltInRegistries.ITEM.getKey(b.getItem()).toString());
			return c != 0 ? c : Integer.compare(b.getCount(), a.getCount());
		};
		List<ItemStack> target = new ArrayList<>(Arrays.asList(sim));
		target.sort(order);
		for (int i = 0; i < n; i++) {
			if (sim[i] == target.get(i)) continue;
			for (int k = i + 1; k < n; k++) {
				if (sim[k] != target.get(i)) continue;
				sortOps.add(new int[] { first + i, first + k, first + i });
				ItemStack t = sim[i];
				sim[i] = sim[k];
				sim[k] = t;
				break;
			}
		}
		if (sortOps.isEmpty()) {
			StarClient.sorterOn = false;
			mc.gui.setOverlayMessage(Component.literal("Sorter: already sorted").withStyle(ChatFormatting.GRAY), false);
		}
	}

	private static void sort(Minecraft mc) {
		var p = mc.player;
		if (mc.gameMode == null || p.containerMenu.containerId != sortMenuId) {
			sorterStop();
			return;
		}
		for (int n = 0; n < Settings.getI("sorter.ops") && !sortOps.isEmpty(); n++) {
			for (int slot : sortOps.poll()) {
				mc.gameMode.handleContainerInput(sortMenuId, slot, 0, ContainerInput.PICKUP, p);
			}
		}
		if (sortOps.isEmpty()) {
			StarClient.sorterOn = false;
			mc.gui.setOverlayMessage(Component.literal("Sorter: done").withStyle(ChatFormatting.GREEN), false);
		}
	}

	public static void sorterStop() {
		sortOps.clear();
		StarClient.sorterOn = false;
	}

	public static void reset() {
		eating = false;
		afkJumpTicks = 0;
		afkIdle = 0;
		afkLastPos = null;
		sortOps.clear();
	}
}
