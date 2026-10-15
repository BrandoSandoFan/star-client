package com.starclient;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

import com.mojang.blaze3d.platform.InputConstants;

/** Module list: toggle each module, rebind its key, open ESP settings. */
public final class ModulesScreen extends Screen {
	private static final String[] NAMES = {"ESP", "Base Finder", "Freecam"};

	private KeyMapping listening;

	public ModulesScreen() {
		super(Component.literal("Star Client"));
	}

	@Override
	protected void init() {
		int cx = width / 2;
		int left = cx - 150;
		int y = 40;

		addRenderableWidget(new StringWidget(left, 20, 300, 9, title, font).alignCenter());

		for (int i = 0; i < NAMES.length; i++) {
			final int idx = i;
			addRenderableWidget(Button.builder(moduleLabel(idx), b -> {
				StarClient.toggle(minecraft, idx);
				b.setMessage(moduleLabel(idx));
			}).bounds(left, y, 110, 20).build());

			KeyMapping key = StarClient.keys()[idx];
			Component keyLabel = listening == key
					? Component.literal("> Press a key <")
					: key.getTranslatedKeyMessage();
			addRenderableWidget(Button.builder(keyLabel, b -> {
				listening = key;
				rebuildWidgets();
			}).bounds(left + 115, y, 105, 20).build());

			if (idx == 0) {
				addRenderableWidget(Button.builder(Component.literal("Settings"), b -> minecraft.setScreen(new EspScreen(this)))
						.bounds(left + 225, y, 75, 20).build());
			}
			y += 24;
		}

		y += 6;
		KeyMapping menuKey = StarClient.menuKey();
		addRenderableWidget(Button.builder(Component.literal("Menu key: ").append(
				listening == menuKey ? Component.literal("> Press a key <") : menuKey.getTranslatedKeyMessage()), b -> {
			listening = menuKey;
			rebuildWidgets();
		}).bounds(left, y, 300, 20).build());
		y += 30;

		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(cx - 100, y, 200, 20).build());
	}

	private Component moduleLabel(int idx) {
		return Component.literal(NAMES[idx] + (StarClient.isOn(idx) ? ": ON" : ": OFF"));
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (listening != null) {
			if (event.key() != InputConstants.KEY_ESCAPE) {
				listening.setKey(InputConstants.Type.KEYSYM.getOrCreate(event.key()));
				KeyMapping.resetMapping();
				minecraft.options.save();
			}
			listening = null;
			rebuildWidgets();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void onClose() {
		minecraft.setScreen(null);
	}
}
