package com.starclient;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Hitboxes settings: how many blocks to grow each side of an entity's hitbox. */
public final class HitboxScreen extends Screen {
	private static final double MAX = 2.0;

	private final Screen parent;

	public HitboxScreen(Screen parent) {
		super(Component.literal("Hitbox Settings"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		Config cfg = Config.get();
		int cx = width / 2;
		int y = 40;

		Component heading = Component.literal("\u25C6 Hitbox Settings").withStyle(ChatFormatting.RED, ChatFormatting.BOLD);
		addRenderableWidget(new StringWidget(cx - font.width(heading) / 2, 20, font.width(heading), 9, heading, font));

		addRenderableWidget(new ValueSlider(cx - 150, y, 300, 20, cfg.hitboxExpand / MAX,
				v -> Component.literal("Expansion: +" + snap(v) + " blocks per side"),
				v -> cfg.hitboxExpand = (float) snap(v)));
		y += 30;

		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(cx - 100, y, 200, 20).build());
	}

	/** Slider fraction to blocks, in 0.05 steps. */
	private static double snap(double v) {
		return Math.round(v * MAX * 20) / 20.0;
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
