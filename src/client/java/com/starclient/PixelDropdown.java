package com.starclient;

import java.util.List;
import java.util.function.IntConsumer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

/**
 * Pixel dropdown: a button showing "label: choice" that opens a list of options below it.
 * Add it before any widget the open list covers so it wins their clicks. Options wrap into columns of {@link #MAX_ROWS}.
 */
public final class PixelDropdown extends AbstractWidget {
	private static final int MAX_ROWS = 6;
	private static final int MIN_COLUMN_WIDTH = 90;

	private final String label;
	private final List<String> options;
	private final IntConsumer onSelect;
	private int selected;
	private boolean open;

	/** {@code selected} of -1 shows just the label. */
	public PixelDropdown(int x, int y, int width, int height, String label, List<String> options, int selected, IntConsumer onSelect) {
		super(x, y, width, height, Component.literal(label));
		this.label = label;
		this.options = options;
		this.selected = selected;
		this.onSelect = onSelect;
	}

	private int columns() {
		return (options.size() + MAX_ROWS - 1) / MAX_ROWS;
	}

	private int columnWidth() {
		return Math.max(width / columns(), MIN_COLUMN_WIDTH);
	}

	/** Option index under the point, or -1. Only meaningful while open. */
	private int optionAt(double mx, double my) {
		int rel = (int) Math.floor(my - (getY() + height));
		int col = (int) Math.floor((mx - getX()) / columnWidth());
		if (rel < 0 || col < 0 || col >= columns() || mx < getX()) return -1;
		int row = rel / height;
		if (row >= MAX_ROWS) return -1;
		int i = col * MAX_ROWS + row;
		return i < options.size() ? i : -1;
	}

	@Override
	public boolean isMouseOver(double mx, double my) {
		return super.isMouseOver(mx, my) || (open && optionAt(mx, my) >= 0);
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		if (!active || !visible) return false;
		if (open) {
			int i = optionAt(event.x(), event.y());
			open = false;
			if (i >= 0) {
				selected = i;
				onSelect.accept(i);
				return true;
			}
			return super.isMouseOver(event.x(), event.y());
		}
		if (super.isMouseOver(event.x(), event.y())) {
			open = true;
			return true;
		}
		return false;
	}

	@Override
	public void setFocused(boolean focused) {
		super.setFocused(focused);
		if (!focused) open = false;
	}

	@Override
	protected void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		var font = Minecraft.getInstance().font;
		int x = getX();
		int y = getY();
		boolean hot = active && (isHoveredOrFocused() || open);
		Theme.bevel(g, x, y, width, height, hot ? Theme.FACE_HOT : Theme.FACE, Theme.HI, Theme.LO);
		if (open) Theme.frame(g, x, y, width, height, Theme.GOLD);
		String text = selected >= 0 ? label + ": " + options.get(selected) : label + "...";
		g.text(font, text, x + 6, y + (height - 8) / 2, 0xFFFFFFFF, true);
		// Down arrow, three rows of pixels
		int ax = x + width - 12;
		int ay = y + height / 2 - 1;
		g.fill(ax, ay, ax + 7, ay + 1, 0xFFFFFFFF);
		g.fill(ax + 1, ay + 1, ax + 6, ay + 2, 0xFFFFFFFF);
		g.fill(ax + 2, ay + 2, ax + 5, ay + 3, 0xFFFFFFFF);
		g.fill(ax + 3, ay + 3, ax + 4, ay + 4, 0xFFFFFFFF);

		if (!open) return;
		g.nextStratum();
		int colW = columnWidth();
		for (int i = 0; i < options.size(); i++) {
			int ox = x + (i / MAX_ROWS) * colW;
			int oy = y + height + (i % MAX_ROWS) * height;
			boolean over = optionAt(mouseX, mouseY) == i;
			boolean current = i == selected;
			Theme.bevel(g, ox, oy, colW, height, over ? Theme.FACE_HOT : Theme.PANEL, over ? Theme.HI : Theme.PANEL_HI, over ? Theme.LO : Theme.PANEL_LO);
			g.text(font, options.get(i), ox + 6, oy + (height - 8) / 2, current ? Theme.GOLD : 0xFFFFFFFF, true);
		}
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		defaultButtonNarrationText(output);
	}
}
