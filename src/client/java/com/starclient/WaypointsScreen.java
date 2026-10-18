package com.starclient;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Waypoint list: add one at your position, delete others. Opened from the Waypoints settings page. */
public final class WaypointsScreen extends PixelScreen {
	private static final int PAGE_SIZE = 8;

	private final Screen parent;
	private int page;
	private String draft = "";

	public WaypointsScreen(Screen parent) {
		super(Component.literal("Waypoints"));
		this.parent = parent;
	}

	@Override
	protected void init() {
		int cx = width / 2;
		int left = cx - 150;
		int y = 36;

		heading(Component.literal("Waypoints").withStyle(Module.ALL.get(Module.WAYPOINTS).category().colour, ChatFormatting.BOLD), 16);

		EditBox name = new EditBox(font, left, y, 190, 20, Component.literal("Name"));
		name.setHint(Component.literal("Name (optional)"));
		name.setValue(draft);
		name.setResponder(t -> draft = t);
		addRenderableWidget(name);
		addRenderableWidget(PixelButton.builder(Component.literal("Add here"), b -> {
			if (minecraft.player == null || minecraft.level == null) return;
			Waypoints.addHere(minecraft, draft);
			draft = "";
			rebuildWidgets();
		}).gold().bounds(left + 196, y, 104, 20).build());
		y += 28;

		List<Config.Waypoint> all = Config.get().waypoints;
		int pages = Math.max(1, (all.size() + PAGE_SIZE - 1) / PAGE_SIZE);
		page = Math.min(page, pages - 1);
		if (all.isEmpty()) {
			Component none = Component.literal("No waypoints yet").withStyle(ChatFormatting.GRAY);
			addRenderableWidget(new StringWidget(cx - font.width(none) / 2, y + 6, font.width(none), 9, none, font));
		}
		for (int i = page * PAGE_SIZE; i < Math.min(all.size(), (page + 1) * PAGE_SIZE); i++) {
			Config.Waypoint w = all.get(i);
			Component label = Component.literal(w.name + "  " + w.x + " " + w.y + " " + w.z).withStyle(w.death ? ChatFormatting.RED : ChatFormatting.WHITE);
			addRenderableWidget(new StringWidget(left + 4, y + 6, 262, 9, label, font));
			addRenderableWidget(PixelButton.builder(Component.literal("✕"), b -> {
				all.remove(w);
				Config.save();
				rebuildWidgets();
			}).danger().bounds(left + 280, y, 20, 20).build());
			y += 22;
		}
		y = Math.max(y, 36 + 28 + PAGE_SIZE * 22) + 4;

		addRenderableWidget(PixelButton.builder(Component.literal("<"), b -> {
			page = Math.max(0, page - 1);
			rebuildWidgets();
		}).bounds(left, y, 20, 20).build());
		Component pageText = Component.literal((page + 1) + " / " + pages).withStyle(ChatFormatting.GRAY);
		addRenderableWidget(new StringWidget(cx - font.width(pageText) / 2, y + 6, font.width(pageText), 9, pageText, font));
		addRenderableWidget(PixelButton.builder(Component.literal(">"), b -> {
			page = Math.min(pages - 1, page + 1);
			rebuildWidgets();
		}).bounds(left + 280, y, 20, 20).build());
		y += 28;

		addRenderableWidget(PixelButton.builder(Component.literal("Done"), b -> onClose()).gold().bounds(cx - 100, y, 200, 20).build());
		panel(left - 10, 10, 320, y + 28 - 10);
	}

	@Override
	public void removed() {
		Config.save();
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
