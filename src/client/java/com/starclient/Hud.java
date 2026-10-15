package com.starclient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;

/** HUD module: list of enabled modules in the top-right corner, widest first, coloured by category. */
public final class Hud {
	private static final Identifier ID = Identifier.fromNamespaceAndPath(StarClient.ID, "hud");
	private static final int MARGIN = 4;
	private static final int LINE = 10;

	private Hud() { }

	public static void init() {
		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, ID, Hud::render);
	}

	private static void render(GuiGraphicsExtractor graphics, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (!StarClient.hudOn || mc.player == null || mc.options.hideGui) return;

		List<Component> lines = new ArrayList<>();
		for (int i = 0; i < Module.ALL.size(); i++) {
			Module m = Module.ALL.get(i);
			if (i != Module.HUD && StarClient.isOn(i)) lines.add(Settings.on("hud.colour") ? Component.literal(m.name()).withStyle(m.category().colour) : Component.literal(m.name()));
		}
		lines.sort(Comparator.comparingInt((Component c) -> mc.font.width(c)).reversed());

		boolean alignRight = Settings.on("hud.right");
		boolean shadow = Settings.on("hud.shadow");
		int right = mc.getWindow().getGuiScaledWidth() - MARGIN;
		int y = MARGIN;
		for (Component line : lines) {
			graphics.text(mc.font, line, alignRight ? right - mc.font.width(line) : MARGIN, y, 0xFFFFFFFF, shadow);
			y += LINE;
		}
	}
}
