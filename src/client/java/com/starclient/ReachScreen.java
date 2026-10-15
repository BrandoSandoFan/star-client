package com.starclient;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Reach settings: interaction range in blocks. */
public final class ReachScreen extends Screen {
	private static final double MIN = 3.0;
	private static final double MAX = 12.0;

	private final Screen parent;

	public ReachScreen(Screen parent) {
		super(Component.literal("Reach Settings"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		Config cfg = Config.get();
		int cx = width / 2;
		int y = 40;

		Component heading = Component.literal("\u25C6 Reach Settings").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD);
		addRenderableWidget(new StringWidget(cx - font.width(heading) / 2, 20, font.width(heading), 9, heading, font));

		addRenderableWidget(new ValueSlider(cx - 150, y, 300, 20, (cfg.reachDistance - MIN) / (MAX - MIN),
				v -> Component.literal("Reach: " + snap(v) + " blocks"),
				v -> cfg.reachDistance = (float) snap(v)));
		y += 30;

		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(cx - 100, y, 200, 20).build());
	}

	/** Slider fraction to blocks, in 0.5 steps. */
	private static double snap(double v) {
		return Math.round((MIN + v * (MAX - MIN)) * 2) / 2.0;
	}

	@Override
	public void removed() {
		Config.save();
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}
}
