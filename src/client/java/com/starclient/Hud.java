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

	private record Line(Component text, int colour, int width) { }

	private static List<Line> cached = List.of();
	private static long cachedKey = -1;

	private Hud() { }

	public static void init() {
		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, ID, Hud::render);
	}

	private static void render(GuiGraphicsExtractor graphics, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (!StarClient.hudOn || mc.player == null || mc.gui.hud.isHidden()) return;

		// Rebuild the sorted list only when the set of enabled modules or the colour setting changes.
		boolean coloured = Settings.on("hud.colour");
		long key = coloured ? 1L << 62 : 0L;
		for (int i = 0; i < Module.ALL.size(); i++) {
			if (i != Module.HUD && StarClient.isOn(i)) key |= 1L << i;
		}
		if (key != cachedKey) {
			List<Line> built = new ArrayList<>();
			for (int i = 0; i < Module.ALL.size(); i++) {
				if ((key & (1L << i)) == 0) continue;
				Module m = Module.ALL.get(i);
				Component text = coloured ? Component.literal(m.name()).withStyle(m.category().colour) : Component.literal(m.name());
				built.add(new Line(text, 0xFF000000 | m.category().rgb, mc.font.width(text)));
			}
			built.sort(Comparator.comparingInt(Line::width).reversed());
			cached = built;
			cachedKey = key;
		}
		List<Line> lines = cached;

		boolean alignRight = Settings.on("hud.right");
		boolean shadow = Settings.on("hud.shadow");
		int right = mc.getWindow().getGuiScaledWidth() - MARGIN;
		int y = MARGIN;
		boolean bar = Settings.on("hud.bar");
		for (Line line : lines) {
			int w = line.width();
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
