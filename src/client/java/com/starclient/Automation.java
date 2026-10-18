package com.starclient;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Automine, Chat Macro and Auto TPA. */
public final class Automation {
	private static boolean mining;

	private static int macroWait = -1;
	private static int macroIdx;

	private static int tpaWait;
	private static Vec3 tpaLastPos;

	private Automation() { }

	public static void tick(Minecraft mc) {
		if (StarClient.autoMineOn) autoMine(mc);
		if (StarClient.chatMacroOn) chatMacro(mc);
		if (StarClient.autoTpaOn) autoTpa(mc);
	}

	private static void autoMine(Minecraft mc) {
		var p = mc.player;
		boolean ok = mc.gui.screen() == null && !Freecam.isOn() && !Utility.isEating() && p.getHealth() >= Settings.get("automine.minhealth")
				&& mc.hitResult != null && mc.hitResult.getType() == HitResult.Type.BLOCK;
		if (ok) {
			mc.options.keyAttack.setDown(true);
			mining = true;
		} else {
			mineOff(mc);
		}
	}

	public static void mineOff(Minecraft mc) {
		if (mining) mc.options.keyAttack.setDown(false);
		mining = false;
	}

	/** Delay in ticks to the next message: the interval varied by up to the jitter percentage either way. */
	private static int macroDelay() {
		double base = Settings.get("chatmacro.interval") * 20.0;
		double jitter = Settings.get("chatmacro.jitter") / 100.0;
		double factor = 1.0 + jitter * (ThreadLocalRandom.current().nextDouble() * 2.0 - 1.0);
		return Math.max(10, (int) Math.round(base * factor));
	}

	private static void chatMacro(Minecraft mc) {
		if (macroWait < 0) macroWait = macroDelay();
		if (--macroWait > 0) return;
		macroWait = -1;
		List<String> messages = new ArrayList<>();
		for (String m : Settings.text("chatmacro.messages").split("\\|")) {
			if (!m.isBlank()) messages.add(m.trim());
		}
		if (messages.isEmpty()) return;
		int i = Settings.on("chatmacro.random") ? ThreadLocalRandom.current().nextInt(messages.size()) : macroIdx++ % messages.size();
		send(mc, messages.get(i));
	}

	private static void send(Minecraft mc, String text) {
		if (text.startsWith("/")) mc.player.connection.sendCommand(text.substring(1));
		else mc.player.connection.sendChat(text);
	}

	private static void autoTpa(Minecraft mc) {
		var p = mc.player;
		String name = Settings.text("autotpa.player").trim();
		if (name.isEmpty()) {
			StarClient.autoTpaOn = false;
			mc.gui.hud.setOverlayMessage(Component.literal("Auto TPA: set a username first").withStyle(ChatFormatting.RED), false);
			return;
		}
		boolean here = Settings.on("autotpa.here");
		boolean arrived = false;
		if (here) {
			for (var other : mc.level.players()) {
				if (other != p && other.getName().getString().equalsIgnoreCase(name) && other.distanceToSqr(p) < 36) arrived = true;
			}
		} else {
			// a jump of 8+ blocks in one tick is the teleport
			Vec3 pos = p.position();
			arrived = tpaLastPos != null && pos.distanceToSqr(tpaLastPos) > 64;
			tpaLastPos = pos;
		}
		if (arrived) {
			StarClient.autoTpaOn = false;
			tpaReset();
			mc.gui.hud.setOverlayMessage(Component.literal("Auto TPA: teleported").withStyle(ChatFormatting.GREEN), false);
			return;
		}
		if (tpaWait > 0) {
			tpaWait--;
			return;
		}
		p.connection.sendCommand((here ? "tpahere " : "tpa ") + name);
		tpaWait = Settings.getI("autotpa.cooldown") * 20;
	}

	public static void macroReset() {
		macroWait = -1;
		macroIdx = 0;
	}

	public static void tpaReset() {
		tpaWait = 0;
		tpaLastPos = null;
	}

	public static void reset() {
		mining = false;
		macroReset();
		tpaReset();
	}
}
