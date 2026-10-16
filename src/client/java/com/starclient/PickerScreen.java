package com.starclient;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.function.Predicate;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Searchable, paged multi-select list used to choose ESP mobs and Chest ESP blocks. */
public final class PickerScreen extends PixelScreen {
	public record Item(String id, Component name) { }

	private static final int COLUMNS = 2;
	private static final int CELL_WIDTH = 148;
	private static final int ROW_HEIGHT = 22;

	private final Screen parent;
	private final String heading;
	private final List<Item> items;
	private final Predicate<String> selected;
	private final Consumer<String> toggle;
	private final Runnable clear;

	private final List<AbstractWidget> cells = new ArrayList<>();
	private String query = "";
	private boolean selectedOnly;
	private int page;
	private int pageSize = 16;
	private PixelButton pageLabel;
	private PixelButton prev;
	private PixelButton next;

	public PickerScreen(Screen parent, String heading, List<Item> items, Predicate<String> selected, Consumer<String> toggle, Runnable clear) {
		super(Component.literal(heading));
		this.parent = parent;
		this.heading = heading;
		this.items = items;
		this.selected = selected;
		this.toggle = toggle;
		this.clear = clear;
	}

	@Override
	protected void init() {
		cells.clear();
		int cx = width / 2;
		int left = cx - PANEL() / 2;

		Component title = Component.literal("" + heading).withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD);
		heading(title, 12);

		EditBox search = new EditBox(font, left, 26, PANEL(), 18, Component.literal("Search"));
		search.setHint(Component.literal("Search..."));
		search.setValue(query);
		search.setResponder(text -> {
			query = text.toLowerCase(Locale.ROOT);
			page = 0;
			populate();
		});
		addRenderableWidget(search);
		setInitialFocus(search);

		int top = 50;
		int footer = height - 52;
		int rows = Math.max(3, (footer - top) / ROW_HEIGHT);
		pageSize = rows * COLUMNS;

		prev = PixelButton.builder(Component.literal("<"), b -> {
			page--;
			populate();
		}).bounds(left, footer, 40, 20).build();
		next = PixelButton.builder(Component.literal(">"), b -> {
			page++;
			populate();
		}).bounds(left + PANEL() - 40, footer, 40, 20).build();
		pageLabel = PixelButton.builder(Component.empty(), b -> { }).bounds(left + 44, footer, PANEL() - 88, 20).build();
		pageLabel.active = false;
		addRenderableWidget(prev);
		addRenderableWidget(next);
		addRenderableWidget(pageLabel);

		int bottom = height - 28;
		addRenderableWidget(PixelButton.builder(selectedOnlyLabel(), b -> {
			selectedOnly = !selectedOnly;
			page = 0;
			b.setMessage(selectedOnlyLabel());
			populate();
		}).bounds(left, bottom, 100, 20).build());
		addRenderableWidget(PixelButton.builder(Component.literal("Clear all"), b -> {
			clear.run();
			populate();
		}).bounds(left + 104, bottom, 90, 20).build());
		addRenderableWidget(PixelButton.builder(Component.literal("Done"), b -> onClose()).gold().bounds(left + 198, bottom, PANEL() - 198, 20).build());
		panel(left - 8, 4, PANEL() + 16, height - 8);

		populate();
	}

	private static int PANEL() {
		return CELL_WIDTH * COLUMNS + 4;
	}

	private Component selectedOnlyLabel() {
		return Component.literal(selectedOnly ? "Selected only: On" : "Selected only: Off");
	}

	private List<Item> filtered() {
		List<Item> out = new ArrayList<>();
		for (Item item : items) {
			if (selectedOnly && !selected.test(item.id())) continue;
			if (!query.isEmpty() && !item.id().contains(query) && !item.name().getString().toLowerCase(Locale.ROOT).contains(query)) continue;
			out.add(item);
		}
		return out;
	}

	private void populate() {
		for (AbstractWidget w : cells) removeWidget(w);
		cells.clear();

		List<Item> list = filtered();
		int pages = Math.max(1, (list.size() + pageSize - 1) / pageSize);
		page = Math.max(0, Math.min(page, pages - 1));
		prev.active = page > 0;
		next.active = page < pages - 1;
		pageLabel.setMessage(Component.literal((page + 1) + " / " + pages + "  (" + list.size() + " shown)"));

		int left = width / 2 - PANEL() / 2;
		int start = page * pageSize;
		for (int i = start; i < Math.min(list.size(), start + pageSize); i++) {
			Item item = list.get(i);
			int slot = i - start;
			int x = left + (slot % COLUMNS) * (CELL_WIDTH + 4);
			int y = 50 + (slot / COLUMNS) * ROW_HEIGHT;
			PixelButton cell = PixelButton.builder(cellLabel(item), b -> {
				toggle.accept(item.id());
				b.setMessage(cellLabel(item));
			}).bounds(x, y, CELL_WIDTH, 20).build();
			cells.add(cell);
			addRenderableWidget(cell);
		}
	}

	private Component cellLabel(Item item) {
		boolean on = selected.test(item.id());
		return Component.literal(on ? "● " : "○ ").withStyle(on ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY)
				.append(item.name().copy().withStyle(on ? ChatFormatting.WHITE : ChatFormatting.GRAY));
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
