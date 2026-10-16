package com.starclient;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Profiles: save, load or clear the enabled modules and ESP/Reach/Hitbox settings in {@link Config#PROFILE_SLOTS} slots. */
public final class ProfilesScreen extends PixelScreen {
	private final Screen parent;

	public ProfilesScreen(Screen parent) {
		super(Component.literal("Profiles"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		Config cfg = Config.get();
		int cx = width / 2;
		int left = cx - 150;
		int y = 20;

		Component heading = Component.literal("◆ Profiles").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD);
		addRenderableWidget(new StringWidget(cx - font.width(heading) / 2, y, font.width(heading), 9, heading, font));
		y += 24;

		for (int slot = 0; slot < Config.PROFILE_SLOTS; slot++) {
			final int s = slot;
			Config.Profile p = cfg.profiles[slot];
			Component label = p == null
					? Component.literal("Slot " + (slot + 1) + ": empty").withStyle(ChatFormatting.GRAY)
					: Component.literal("Slot " + (slot + 1) + ": " + p.enabledCount() + " on").withStyle(ChatFormatting.WHITE);
			addRenderableWidget(new StringWidget(left, y + 6, 110, 9, label, font));

			PixelButton save = PixelButton.builder(Component.literal("Save"), b -> {
				cfg.profiles[s] = Config.Profile.capture(cfg);
				Config.save();
				rebuildWidgets();
			}).bounds(left + 115, y, 60, 20).build();
			addRenderableWidget(save);

			PixelButton load = PixelButton.builder(Component.literal("Load"), b -> {
				cfg.profiles[s].apply(minecraft, cfg);
				Config.save();
				rebuildWidgets();
			}).bounds(left + 179, y, 60, 20).build();
			load.active = p != null;
			addRenderableWidget(load);

			PixelButton clear = PixelButton.builder(Component.literal("Clear"), b -> {
				cfg.profiles[s] = null;
				Config.save();
				rebuildWidgets();
			}).danger().bounds(left + 243, y, 57, 20).build();
			clear.active = p != null;
			addRenderableWidget(clear);
			y += 24;
		}

		y += 8;
		addRenderableWidget(PixelButton.builder(Component.literal("Done"), b -> onClose()).gold().bounds(cx - 100, y, 200, 20).build());
		panel(left - 10, 10, 320, y + 28 - 10);
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}
}
