package com.starclient;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.Style;

/** ESP customisation: per-category toggle and RGB colour, opacity and range. */
public final class EspScreen extends PixelScreen {
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

		Component heading = Component.literal("\u25C6 ESP Settings").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD);
		addRenderableWidget(new StringWidget(cx - font.width(heading) / 2, 20, font.width(heading), 9, heading, font));

		for (int i = 0; i < Config.ESP_NAMES.length; i++) {
			final int idx = i;
			addRenderableWidget(PixelButton.builder(categoryLabel(idx), b -> {
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

		addRenderableWidget(PixelButton.builder(Component.literal("Mob filter... (" + Config.ESP_MOB_MODES[cfg.espMobMode] + ")"),
				b -> minecraft.setScreen(new ModuleSettingsScreen.MobFilter(this))).bounds(cx - 100, y, 200, 20).build());
		y += 24;

		addRenderableWidget(PixelButton.builder(Component.literal("Reset to defaults"), b -> {
			Config.reset();
			rebuildWidgets();
		}).bounds(cx - 100, y, 200, 20).build());
		y += 24;

		addRenderableWidget(PixelButton.builder(Component.literal("Done"), b -> onClose()).gold().bounds(cx - 100, y, 200, 20).build());
		panel(left - 10, 10, 320, y + 28 - 10);
	}

	private Component categoryLabel(int idx) {
		Config cfg = Config.get();
		return Component.literal((cfg.espEnabled[idx] ? "\u25CF " : "\u25CB ") + Config.ESP_NAMES[idx])
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
