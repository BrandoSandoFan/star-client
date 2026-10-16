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
	public static final org.slf4j.Logger LOGGER = org.slf4j.LoggerFactory.getLogger(ID);

	public static boolean espOn;
	public static boolean baseFinderOn;
	public static boolean chestEspOn;
	public static boolean fullbrightOn;
	public static boolean autoSprintOn;
	public static boolean autoWalkOn;
	public static boolean flyOn;
	public static boolean reachOn;
	public static boolean aimBotOn;
	public static boolean hitBotOn;
	public static boolean elytraFlyOn;
	public static boolean killAuraOn;
	public static boolean critsOn;
	public static boolean hitboxesOn;
	public static boolean speedOn;
	public static boolean highJumpOn;
	public static boolean softFallOn;
	public static boolean trajectoriesOn;
	public static boolean jesusOn;
	public static boolean autoEatOn;
	public static boolean antiAfkOn;
	public static boolean sorterOn;
	public static boolean hudOn;
	public static boolean antiBaitOn;
	public static boolean diagnosticsOn;

	private static KeyMapping[] moduleKeys;
	private static KeyMapping menuKey;

	@Override
	public void onInitializeClient() {
		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(ID, "main"));

		moduleKeys = new KeyMapping[Module.ALL.size()];
		for (int i = 0; i < moduleKeys.length; i++) {
			moduleKeys[i] = KeyMappingHelper.registerKeyMapping(
					new KeyMapping("key.starclient." + Module.ALL.get(i).id(), InputConstants.Type.KEYSYM, InputConstants.UNKNOWN.getValue(), category));
		}
		menuKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.starclient.menu", InputConstants.Type.KEYSYM, InputConstants.KEY_RSHIFT, category));

		Config.load();
		ClientTickEvents.START_CLIENT_TICK.register(Combat::expandHitboxes);
		ClientTickEvents.END_CLIENT_TICK.register(this::tick);
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> StarRenderer.close());
		StarRenderer.init();
		Hud.init();
		Diagnostics.init();
		SortButton.init();
	}

	private void tick(Minecraft mc) {
		if (mc.player == null || mc.level == null) {
			Freecam.reset();
			BaseFinder.clear();
			ChestEsp.clear();
			Movement.reset();
			Utility.reset();
			AntiBait.clear();
			Diagnostics.reset();
			return;
		}

		while (menuKey.consumeClick()) {
			if (mc.screen == null) mc.setScreen(new ModulesScreen());
		}
		KeyMapping[] keys = keys();
		for (int i = 0; i < keys.length; i++) {
			while (keys[i].consumeClick()) {
				toggle(mc, i);
				if (i != Module.PANIC) status(mc, Module.ALL.get(i).name(), isOn(i));
			}
		}

		Freecam.tick(mc);
		if (antiBaitOn) AntiBait.tick(mc);
		if (baseFinderOn) BaseFinder.tick(mc);
		if (chestEspOn) ChestEsp.tick(mc);
		Movement.tick(mc);
		Utility.tick(mc);
		Combat.tick(mc);
		Diagnostics.tick(mc);
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
			case Module.FLY -> flyOn;
			case Module.REACH -> reachOn;
			case Module.AIM_BOT -> aimBotOn;
			case Module.HIT_BOT -> hitBotOn;
			case Module.ELYTRA_FLY -> elytraFlyOn;
			case Module.KILL_AURA -> killAuraOn;
			case Module.CRITS -> critsOn;
			case Module.HITBOXES -> hitboxesOn;
			case Module.SPEED -> speedOn;
			case Module.HIGH_JUMP -> highJumpOn;
			case Module.SOFT_FALL -> softFallOn;
			case Module.TRAJECTORIES -> trajectoriesOn;
			case Module.JESUS -> jesusOn;
			case Module.AUTO_EAT -> autoEatOn;
			case Module.ANTI_AFK -> antiAfkOn;
			case Module.SORTER -> sorterOn;
			case Module.HUD -> hudOn;
			case Module.ANTI_BAIT -> antiBaitOn;
			case Module.DIAGNOSTICS -> diagnosticsOn;
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
			case Module.FLY -> {
				flyOn = !flyOn;
				if (!flyOn) Movement.flyOff(mc);
			}
			case Module.REACH -> {
				reachOn = !reachOn;
				if (!reachOn) Movement.reachOff(mc);
			}
			case Module.AIM_BOT -> aimBotOn = !aimBotOn;
			case Module.HIT_BOT -> hitBotOn = !hitBotOn;
			case Module.KILL_AURA -> killAuraOn = !killAuraOn;
			case Module.CRITS -> critsOn = !critsOn;
			case Module.HITBOXES -> hitboxesOn = !hitboxesOn;
			case Module.SPEED -> speedOn = !speedOn;
			case Module.HIGH_JUMP -> highJumpOn = !highJumpOn;
			case Module.SOFT_FALL -> softFallOn = !softFallOn;
			case Module.TRAJECTORIES -> trajectoriesOn = !trajectoriesOn;
			case Module.JESUS -> jesusOn = !jesusOn;
			case Module.AUTO_EAT -> {
				autoEatOn = !autoEatOn;
				if (!autoEatOn) Utility.autoEatOff(mc);
			}
			case Module.ANTI_AFK -> {
				antiAfkOn = !antiAfkOn;
				if (!antiAfkOn) Utility.antiAfkOff(mc);
			}
			case Module.SORTER -> {
				sorterOn = !sorterOn;
				if (sorterOn) Utility.sorterStart(mc);
				else Utility.sorterStop();
			}
			case Module.HUD -> hudOn = !hudOn;
			case Module.ANTI_BAIT -> {
				antiBaitOn = !antiBaitOn;
				if (!antiBaitOn) AntiBait.clear();
			}
			case Module.DIAGNOSTICS -> {
				diagnosticsOn = !diagnosticsOn;
				if (!diagnosticsOn) Diagnostics.reset();
			}
			case Module.PANIC -> panic(mc);
			case Module.ELYTRA_FLY -> {
				elytraFlyOn = !elytraFlyOn;
				Movement.elytraReset();
			}
			default -> { }
		}
	}

	/** Turns every module off (including the HUD) and closes any open screen. */
	public static void panic(Minecraft mc) {
		for (int i = 0; i < Module.ALL.size(); i++) {
			if (i != Module.PANIC && isOn(i)) toggle(mc, i);
		}
		if (Settings.on("panic.close")) mc.setScreen(null);
		mc.gui.setOverlayMessage(Component.literal("Panic: all modules off").withStyle(ChatFormatting.RED), false);
	}

	private static void status(Minecraft mc, String name, boolean on) {
		Component state = Component.literal(on ? "ON" : "OFF").withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED);
		mc.gui.setOverlayMessage(Component.literal(name + ": ").withStyle(ChatFormatting.WHITE).append(state), false);
	}
}
