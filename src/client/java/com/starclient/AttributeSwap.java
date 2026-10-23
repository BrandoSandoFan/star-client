package com.starclient;

import net.minecraft.client.Minecraft;

/**
 * Attribute Swap: with the main slot selected, an attack is made with the second slot selected instead and the
 * selection goes back to the main slot afterwards. Manual clicks are caught by {@code MultiPlayerGameModeMixin} at
 * the head of {@code MultiPlayerGameMode.attack}, before the carried item is sent; Trigger Bot and Kill Aura attacks
 * go through {@link #around}.
 */
public final class AttributeSwap {
	/** Slot to return to (-1 when idle) and ticks left before returning. */
	private static int restoreSlot = -1;
	private static int restoreIn;

	private AttributeSwap() { }

	private static int main() {
		return Settings.getI("attrswap.main") - 1;
	}

	private static int second() {
		return Settings.getI("attrswap.second") - 1;
	}

	private static boolean ready(Minecraft mc) {
		return StarClient.attributeSwapOn && mc.player != null && mc.level != null && mc.gameMode != null && mc.gui.screen() == null
				&& !Freecam.isOn() && !Utility.isEating() && main() != second();
	}

	/**
	 * Head of every {@code gameMode.attack}: an attack with the main slot selected is made with the second slot. Module
	 * attacks reach here with the second slot already selected by {@link #around}, so they pass through.
	 */
	public static void beforeAttack(Minecraft mc) {
		if (!ready(mc) || !Settings.on("attrswap.manual") || restoreSlot >= 0) return;
		var inv = mc.player.getInventory();
		if (inv.getSelectedSlot() != main()) return;
		inv.setSelectedSlot(second());
		restoreSlot = main();
		restoreIn = Settings.getI("attrswap.back");
	}

	/** End of tick: returns to the main slot once the delay is over, unless the player changed slot meanwhile. */
	public static void end(Minecraft mc) {
		if (restoreSlot < 0 || mc.player == null) return;
		if (--restoreIn > 0) return;
		var inv = mc.player.getInventory();
		if (inv.getSelectedSlot() == second()) inv.setSelectedSlot(restoreSlot);
		restoreSlot = -1;
	}

	/** Runs a module attack with the second slot selected, then selects the main slot again. */
	public static void around(Minecraft mc, Runnable attack) {
		var inv = mc.player == null ? null : mc.player.getInventory();
		if (!ready(mc) || inv.getSelectedSlot() != main() || restoreSlot >= 0) {
			attack.run();
			return;
		}
		inv.setSelectedSlot(second());
		attack.run();
		inv.setSelectedSlot(main());
	}

	public static void reset() {
		restoreSlot = -1;
	}
}
