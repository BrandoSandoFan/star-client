package com.starclient;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.ChatFormatting;
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
	public static boolean chestEspOn;
	public static boolean fullbrightOn;
	public static boolean autoSprintOn;
	public static boolean autoWalkOn;

	private static final int[] DEFAULT_KEYS = {
			InputConstants.KEY_R, InputConstants.KEY_B, InputConstants.KEY_F,
			InputConstants.KEY_C, InputConstants.KEY_G, InputConstants.KEY_V, InputConstants.KEY_N};

	private static KeyMapping[] moduleKeys;
	private static KeyMapping menuKey;

	@Override
	public void onInitializeClient() {
		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(ID, "main"));

		moduleKeys = new KeyMapping[Module.ALL.size()];
		for (int i = 0; i < moduleKeys.length; i++) {
			moduleKeys[i] = KeyMappingHelper.registerKeyMapping(
					new KeyMapping("key.starclient." + Module.ALL.get(i).id(), InputConstants.Type.KEYSYM, DEFAULT_KEYS[i], category));
		}
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
			ChestEsp.clear();
			Movement.reset();
			return;
		}

		while (menuKey.consumeClick()) {
			if (mc.screen == null) mc.setScreen(new ModulesScreen());
		}
		KeyMapping[] keys = keys();
		for (int i = 0; i < keys.length; i++) {
			while (keys[i].consumeClick()) {
				toggle(mc, i);
				status(mc, Module.ALL.get(i).name(), isOn(i));
			}
		}

		Freecam.tick(mc);
		if (baseFinderOn) BaseFinder.tick(mc);
		if (chestEspOn) ChestEsp.tick(mc);
		Movement.tick(mc);
	}

	/** Module keybinds in {@link Module#ALL} order. */
	public static KeyMapping[] keys() {
		return moduleKeys;
	}

	public static KeyMapping menuKey() {
		return menuKey;
	}

	public static boolean isOn(int module) {
		return switch (module) {
			case Module.ESP -> espOn;
			case Module.BASE_FINDER -> baseFinderOn;
			case Module.FREECAM -> Freecam.isOn();
			case Module.CHEST_ESP -> chestEspOn;
			case Module.FULLBRIGHT -> fullbrightOn;
			case Module.AUTO_SPRINT -> autoSprintOn;
			case Module.AUTO_WALK -> autoWalkOn;
			default -> false;
		};
	}

	public static void toggle(Minecraft mc, int module) {
		switch (module) {
			case Module.ESP -> espOn = !espOn;
			case Module.BASE_FINDER -> {
				baseFinderOn = !baseFinderOn;
				if (!baseFinderOn) BaseFinder.clear();
			}
			case Module.FREECAM -> Freecam.toggle(mc);
			case Module.CHEST_ESP -> {
				chestEspOn = !chestEspOn;
				if (!chestEspOn) ChestEsp.clear();
			}
			case Module.FULLBRIGHT -> {
				fullbrightOn = !fullbrightOn;
				if (!fullbrightOn) Movement.fullbrightOff(mc);
			}
			case Module.AUTO_SPRINT -> autoSprintOn = !autoSprintOn;
			case Module.AUTO_WALK -> {
				autoWalkOn = !autoWalkOn;
				if (!autoWalkOn) Movement.autoWalkOff(mc);
			}
			default -> { }
		}
	}

	private static void status(Minecraft mc, String name, boolean on) {
		Component state = Component.literal(on ? "ON" : "OFF").withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED);
		mc.gui.setOverlayMessage(Component.literal(name + ": ").withStyle(ChatFormatting.WHITE).append(state), false);
	}
}
