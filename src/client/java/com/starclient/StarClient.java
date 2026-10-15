package com.starclient;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;

public class StarClient implements ClientModInitializer {
	public static final String ID = "starclient";

	public static boolean espOn;
	public static boolean baseFinderOn;

	private static KeyMapping espKey;
	private static KeyMapping baseKey;
	private static KeyMapping freecamKey;
	private static KeyMapping menuKey;

	@Override
	public void onInitializeClient() {
		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(ID, "main"));

		espKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.starclient.esp", InputConstants.Type.KEYSYM, InputConstants.KEY_R, category));
		baseKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.starclient.basefinder", InputConstants.Type.KEYSYM, InputConstants.KEY_B, category));
		freecamKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.starclient.freecam", InputConstants.Type.KEYSYM, InputConstants.KEY_F, category));

		menuKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.starclient.menu", InputConstants.Type.KEYSYM, InputConstants.KEY_RSHIFT, category));

		Config.load();
		ClientTickEvents.END_CLIENT_TICK.register(this::tick);
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> StarRenderer.close());
		StarRenderer.init();
	}

	private void tick(Minecraft mc) {
		if (mc.player == null || mc.level == null) {
			Freecam.reset();
			BaseFinder.clear();
			return;
		}

		while (menuKey.consumeClick()) {
			if (mc.screen == null) mc.setScreen(new ModulesScreen());
		}
		KeyMapping[] keys = keys();
		for (int i = 0; i < keys.length; i++) {
			while (keys[i].consumeClick()) {
				toggle(mc, i);
				status(mc, NAMES[i], isOn(i));
			}
		}

		Freecam.tick(mc);
		if (baseFinderOn) BaseFinder.tick(mc);
	}

	private static final String[] NAMES = {"ESP", "Base Finder", "Freecam"};

	/** Module keybinds in module index order: 0 = ESP, 1 = Base Finder, 2 = Freecam. */
	public static KeyMapping[] keys() {
		return new KeyMapping[] {espKey, baseKey, freecamKey};
	}

	public static KeyMapping menuKey() {
		return menuKey;
	}

	public static boolean isOn(int module) {
		return switch (module) {
			case 0 -> espOn;
			case 1 -> baseFinderOn;
			default -> Freecam.isOn();
		};
	}

	public static void toggle(Minecraft mc, int module) {
		switch (module) {
			case 0 -> espOn = !espOn;
			case 1 -> {
				baseFinderOn = !baseFinderOn;
				if (!baseFinderOn) BaseFinder.clear();
			}
			default -> Freecam.toggle(mc);
		}
	}

	private static void status(Minecraft mc, String name, boolean on) {
		mc.gui.setOverlayMessage(Component.literal(name + ": " + (on ? "ON" : "OFF")), false);
	}
}
