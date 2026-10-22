package com.starclient;

import com.mojang.blaze3d.platform.InputConstants;

import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.CreativeModeInventoryScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.GameType;
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
	public static boolean autoToolOn;
	public static boolean tracersOn;
	public static boolean zoomOn;
	public static boolean waypointsOn;
	public static boolean autoTotemOn;
	public static boolean autoArmorOn;
	public static boolean autoRespawnOn;
	public static boolean autoReplenishOn;
	public static boolean stepOn;
	public static boolean safeWalkOn;
	public static boolean noSlowOn;
	public static boolean velocityOn;
	public static boolean portalFinderOn;
	public static boolean signReaderOn;
	public static boolean autoLogOn;
	public static boolean attributeSwapOn;
	public static boolean autoWaterOn;
	public static boolean autoMineOn;
	public static boolean chatMacroOn;
	public static boolean autoTpaOn;
	public static boolean maceAuraOn;
	public static boolean xrayOn;

	private static KeyMapping[] moduleKeys;
	private static KeyMapping menuKey;

	@Override
	public void onInitializeClient() {
		KeyMapping.Category category = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(ID, "main"));

		moduleKeys = new KeyMapping[Module.ALL.size()];
		for (int i = 0; i < moduleKeys.length; i++) {
			moduleKeys[i] = KeyMappingHelper.registerKeyMapping(
					new KeyMapping("key.starclient." + Module.ALL.get(i).id(), InputConstants.Type.KEYBOARD, InputConstants.UNKNOWN.getValue(), category));
		}
		menuKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.starclient.menu", InputConstants.Type.KEYBOARD, InputConstants.KEY_RSHIFT, category));

		Config.load();
		ClientTickEvents.START_CLIENT_TICK.register(Combat::expandHitboxes);
		ClientTickEvents.START_CLIENT_TICK.register(AttributeSwap::start);
		ClientTickEvents.END_CLIENT_TICK.register(AttributeSwap::end);
		ClientTickEvents.END_CLIENT_TICK.register(this::tick);
		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			Zoom.off(client);
			StarRenderer.close();
		});
		StarRenderer.init();
		Hud.init();
		Waypoints.init();
		SignReader.init();
		Diagnostics.init();
		SortButton.init();
	}

	private static GameType creativeRestore;

	private void tick(Minecraft mc) {
		if (mc.player == null || mc.level == null) {
			creativeRestore = null;
			Freecam.reset();
			BaseFinder.clear();
			ChestEsp.clear();
			Movement.reset();
			Utility.reset();
			AntiBait.clear();
			Diagnostics.reset();
			Gear.reset();
			Zoom.off(mc);
			Waypoints.reset();
			PortalFinder.clear();
			Xray.clear();
			SignReader.clear();
			AttributeSwap.reset();
			AutoSafety.reset();
			Automation.reset();
			Clipper.reset();
			return;
		}

		if (creativeRestore != null && !(mc.gui.screen() instanceof CreativeModeInventoryScreen)) {
			mc.gameMode.setLocalMode(creativeRestore);
			creativeRestore = null;
		}

		while (menuKey.consumeClick()) {
			if (mc.gui.screen() == null) mc.gui.setScreen(new ModulesScreen());
		}
		KeyMapping[] keys = keys();
		for (int i = 0; i < keys.length; i++) {
			while (keys[i].consumeClick()) {
				toggle(mc, i);
				if (i != Module.PANIC && i != Module.CLIPPER && i != Module.CREATIVE_MENU) status(mc, Module.ALL.get(i).name(), isOn(i));
			}
		}

		Freecam.tick(mc);
		if (antiBaitOn) AntiBait.tick(mc);
		if (baseFinderOn) BaseFinder.tick(mc);
		if (chestEspOn) ChestEsp.tick(mc);
		if (portalFinderOn) PortalFinder.tick(mc);
		if (signReaderOn) SignReader.tick(mc);
		if (xrayOn) Xray.tick(mc);
		Movement.tick(mc);
		Utility.tick(mc);
		Combat.tick(mc);
		Diagnostics.tick(mc);
		Gear.tick(mc);
		Zoom.tick(mc);
		Waypoints.tick(mc);
		AutoSafety.tick(mc);
		Automation.tick(mc);
		Clipper.tick(mc);
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
			case Module.AUTO_TOOL -> autoToolOn;
			case Module.TRACERS -> tracersOn;
			case Module.ZOOM -> zoomOn;
			case Module.WAYPOINTS -> waypointsOn;
			case Module.AUTO_TOTEM -> autoTotemOn;
			case Module.AUTO_ARMOR -> autoArmorOn;
			case Module.AUTO_RESPAWN -> autoRespawnOn;
			case Module.AUTO_REPLENISH -> autoReplenishOn;
			case Module.STEP -> stepOn;
			case Module.SAFE_WALK -> safeWalkOn;
			case Module.NO_SLOW -> noSlowOn;
			case Module.VELOCITY -> velocityOn;
			case Module.PORTAL_FINDER -> portalFinderOn;
			case Module.SIGN_READER -> signReaderOn;
			case Module.AUTO_LOG -> autoLogOn;
			case Module.ATTRIBUTE_SWAP -> attributeSwapOn;
			case Module.AUTO_WATER -> autoWaterOn;
			case Module.AUTO_MINE -> autoMineOn;
			case Module.CHAT_MACRO -> chatMacroOn;
			case Module.AUTO_TPA -> autoTpaOn;
			case Module.MACE_AURA -> maceAuraOn;
			case Module.XRAY -> xrayOn;
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
			case Module.AUTO_TOOL -> {
				autoToolOn = !autoToolOn;
				if (!autoToolOn) Gear.autoToolOff(mc);
			}
			case Module.TRACERS -> tracersOn = !tracersOn;
			case Module.ZOOM -> {
				zoomOn = !zoomOn;
				if (!zoomOn) Zoom.off(mc);
			}
			case Module.WAYPOINTS -> waypointsOn = !waypointsOn;
			case Module.AUTO_TOTEM -> autoTotemOn = !autoTotemOn;
			case Module.AUTO_ARMOR -> autoArmorOn = !autoArmorOn;
			case Module.AUTO_RESPAWN -> autoRespawnOn = !autoRespawnOn;
			case Module.AUTO_REPLENISH -> {
				autoReplenishOn = !autoReplenishOn;
				Gear.replenishReset();
			}
			case Module.STEP -> {
				stepOn = !stepOn;
				if (!stepOn) Movement.stepOff(mc);
			}
			case Module.SAFE_WALK -> safeWalkOn = !safeWalkOn;
			case Module.NO_SLOW -> noSlowOn = !noSlowOn;
			case Module.VELOCITY -> velocityOn = !velocityOn;
			case Module.PORTAL_FINDER -> {
				portalFinderOn = !portalFinderOn;
				if (!portalFinderOn) PortalFinder.clear();
			}
			case Module.SIGN_READER -> {
				signReaderOn = !signReaderOn;
				if (!signReaderOn) SignReader.clear();
			}
			case Module.AUTO_LOG -> {
				autoLogOn = !autoLogOn;
				AutoSafety.logReset();
			}
			case Module.ATTRIBUTE_SWAP -> {
				attributeSwapOn = !attributeSwapOn;
				AttributeSwap.reset();
			}
			case Module.AUTO_WATER -> {
				autoWaterOn = !autoWaterOn;
				AutoSafety.waterReset();
			}
			case Module.AUTO_MINE -> {
				autoMineOn = !autoMineOn;
				if (!autoMineOn) Automation.mineOff(mc);
			}
			case Module.CHAT_MACRO -> {
				chatMacroOn = !chatMacroOn;
				Automation.macroReset();
			}
			case Module.AUTO_TPA -> {
				autoTpaOn = !autoTpaOn;
				Automation.tpaReset();
			}
			case Module.MACE_AURA -> maceAuraOn = !maceAuraOn;
			case Module.XRAY -> {
				xrayOn = !xrayOn;
				if (!xrayOn) Xray.clear();
				Xray.reload(mc);
			}
			case Module.CLIPPER -> Clipper.save(mc);
			case Module.CREATIVE_MENU -> {
				if (mc.player != null && mc.gameMode != null) {
					// Item picks only send ServerboundSetCreativeModeSlotPacket while the client thinks it is in creative
					GameType cur = mc.gameMode.getPlayerMode();
					if (cur != GameType.CREATIVE && creativeRestore == null) {
						creativeRestore = cur;
						mc.gameMode.setLocalMode(GameType.CREATIVE);
					}
					mc.gui.setScreen(new CreativeModeInventoryScreen(mc.player, mc.player.connection.enabledFeatures(), mc.options.operatorItemsTab().get()));
				}
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
		if (Settings.on("panic.close")) mc.gui.setScreen(null);
		mc.gui.hud.setOverlayMessage(Component.literal("Panic: all modules off").withStyle(ChatFormatting.RED), false);
	}

	private static void status(Minecraft mc, String name, boolean on) {
		Component state = Component.literal(on ? "ON" : "OFF").withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED);
		mc.gui.hud.setOverlayMessage(Component.literal(name + ": ").withStyle(ChatFormatting.WHITE).append(state), false);
	}
}
