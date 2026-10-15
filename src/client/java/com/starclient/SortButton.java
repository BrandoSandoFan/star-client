package com.starclient;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;

/** Adds a "Sort" button above chest, ender chest, barrel and shulker box screens. */
public final class SortButton {
	private static final int GUI_WIDTH = 176;
	private static final int BUTTON_WIDTH = 40;
	private static final int BUTTON_HEIGHT = 16;

	private SortButton() { }

	public static void init() {
		ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
			if (!(screen instanceof AbstractContainerScreen<?> container) || !Utility.isSortableContainer(container.getMenu())) return;
			// Vanilla chest layout: 114px of player inventory and padding plus 18px per container row
			int rows = Utility.containerSize(container.getMenu()) / 9;
			int top = (height - (114 + rows * 18)) / 2;
			int right = (width + GUI_WIDTH) / 2;
			Screens.getButtons(screen).add(Button.builder(Component.literal("Sort"), b -> Utility.sortOpenContainer(client))
					.bounds(right - BUTTON_WIDTH, top - BUTTON_HEIGHT - 4, BUTTON_WIDTH, BUTTON_HEIGHT).build());
		});
	}
}
