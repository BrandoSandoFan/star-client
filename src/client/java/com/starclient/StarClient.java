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

	@Override
	public void onInitializeClient() {
		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(ID, "main"));

		espKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.starclient.esp", InputConstants.Type.KEYSYM, InputConstants.KEY_R, category));
		baseKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.starclient.basefinder", InputConstants.Type.KEYSYM, InputConstants.KEY_B, category));
		freecamKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.starclient.freecam", InputConstants.Type.KEYSYM, InputConstants.KEY_F, category));

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

		while (espKey.consumeClick()) {
			espOn = !espOn;
			status(mc, "ESP", espOn);
		}
		while (baseKey.consumeClick()) {
			baseFinderOn = !baseFinderOn;
			if (!baseFinderOn) BaseFinder.clear();
			status(mc, "Base Finder", baseFinderOn);
		}
		while (freecamKey.consumeClick()) {
			Freecam.toggle(mc);
			status(mc, "Freecam", Freecam.isOn());
		}

		Freecam.tick(mc);
		if (baseFinderOn) BaseFinder.tick(mc);
	}

	private static void status(Minecraft mc, String name, boolean on) {
		mc.player.displayClientMessage(Component.literal(name + ": " + (on ? "ON" : "OFF")), true);
	}
}
