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

	private record Line(Component text, int colour) { }

	private Hud() { }

	public static void init() {
		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, ID, Hud::render);
	}

	private static void render(GuiGraphicsExtractor graphics, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (!StarClient.hudOn || mc.player == null || mc.options.hideGui) return;

		List<Line> lines = new ArrayList<>();
		for (int i = 0; i < Module.ALL.size(); i++) {
			Module m = Module.ALL.get(i);
			if (i != Module.HUD && StarClient.isOn(i)) lines.add(new Line(Settings.on("hud.colour") ? Component.literal(m.name()).withStyle(m.category().colour) : Component.literal(m.name()),
					0xFF000000 | m.category().colour.getColor()));
		}
		lines.sort(Comparator.comparingInt((Line l) -> mc.font.width(l.text())).reversed());

		boolean alignRight = Settings.on("hud.right");
		boolean shadow = Settings.on("hud.shadow");
		int right = mc.getWindow().getGuiScaledWidth() - MARGIN;
		int y = MARGIN;
		boolean bar = Settings.on("hud.bar");
		for (Line line : lines) {
			int w = mc.font.width(line.text());
			int x = alignRight ? right - w : MARGIN;
			if (bar) {
				graphics.fill(x - 3, y - 1, x + w + 2, y + LINE - 1, 0x90101216);
				int edge = alignRight ? x + w + 2 : x - 4;
				graphics.fill(edge, y - 1, edge + 2, y + LINE - 1, line.colour());
			}
			graphics.text(mc.font, line.text(), x, y, 0xFFFFFFFF, shadow);
			y += LINE;
		}
	}
}
