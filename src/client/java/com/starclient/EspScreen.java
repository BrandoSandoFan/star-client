package com.starclient;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

/** ESP customisation: per-category toggle and RGB colour, opacity and range. */
public final class EspScreen extends Screen {
	private final Screen parent;

	public EspScreen(Screen parent) {
		super(Component.literal("ESP Settings"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		Config cfg = Config.get();
		int cx = width / 2;
		int left = cx - 150;
		int y = 40;

		addRenderableWidget(new StringWidget(cx - font.width(title) / 2, 20, font.width(title), 9, title, font));

		for (int i = 0; i < Config.ESP_NAMES.length; i++) {
			final int idx = i;
			addRenderableWidget(Button.builder(categoryLabel(idx), b -> {
				cfg.espEnabled[idx] = !cfg.espEnabled[idx];
				b.setMessage(categoryLabel(idx));
			}).bounds(left, y, 90, 20).build());

			addRenderableWidget(channelSlider(left + 95, y, idx, 16, "R"));
			addRenderableWidget(channelSlider(left + 95 + 69, y, idx, 8, "G"));
			addRenderableWidget(channelSlider(left + 95 + 138, y, idx, 0, "B"));
			y += 24;
		}

		y += 6;
		addRenderableWidget(new ValueSlider(left, y, 300, 20, cfg.espAlpha,
				v -> Component.literal("Opacity: " + Math.round(v * 100) + "%"),
				v -> cfg.espAlpha = (float) Math.max(0.05, v)));
		y += 24;
		addRenderableWidget(new ValueSlider(left, y, 300, 20, cfg.espMaxDistance / 256.0,
				v -> Component.literal("Range: " + (v < 0.01 ? "Unlimited" : Math.round(v * 256) + " blocks")),
				v -> cfg.espMaxDistance = (int) Math.round(v * 256)));
		y += 30;

		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(cx - 100, y, 200, 20).build());
	}

	private Component categoryLabel(int idx) {
		Config cfg = Config.get();
		return Component.literal(Config.ESP_NAMES[idx] + (cfg.espEnabled[idx] ? ": ON" : ": OFF"))
				.withStyle(Style.EMPTY.withColor(cfg.espColor[idx]));
	}

	private ValueSlider channelSlider(int x, int y, int idx, int shift, String name) {
		Config cfg = Config.get();
		return new ValueSlider(x, y, 65, 20, ((cfg.espColor[idx] >> shift) & 0xFF) / 255.0,
				v -> Component.literal(name + " " + Math.round(v * 255)),
				v -> {
					int c = cfg.espColor[idx] & ~(0xFF << shift);
					cfg.espColor[idx] = c | ((int) Math.round(v * 255) << shift);
				});
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
