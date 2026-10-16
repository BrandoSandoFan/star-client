package com.starclient;

import java.util.function.Consumer;
import java.util.function.DoubleFunction;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

/** Pixel-style slider over 0..1 with a caller-supplied label and change callback. */
public final class ValueSlider extends AbstractSliderButton {
	private static final int HANDLE = 6;

	private final DoubleFunction<Component> label;
	private final Consumer<Double> onChange;

	public ValueSlider(int x, int y, int width, int height, double value, DoubleFunction<Component> label, Consumer<Double> onChange) {
		super(x, y, width, height, label.apply(value), value);
		this.label = label;
		this.onChange = onChange;
	}

	@Override
	protected void updateMessage() {
		if (label != null) setMessage(label.apply(value));
	}

	@Override
	protected void applyValue() {
		if (onChange != null) onChange.accept(value);
	}

	@Override
	public void extractWidgetRenderState(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		int x = getX();
		int y = getY();
		int w = getWidth();
		int h = getHeight();
		Theme.well(g, x, y, w, h);
		int hx = x + 2 + (int) Math.round(value * (w - 4 - HANDLE));
		boolean hot = isHoveredOrFocused();
		Theme.bevel(g, hx, y + 1, HANDLE, h - 2, hot ? Theme.FACE_HOT : Theme.FACE, Theme.HI, Theme.LO);
		var font = Minecraft.getInstance().font;
		Component msg = getMessage();
		g.text(font, msg, x + Math.max(3, (w - font.width(msg)) / 2), y + (h - 8) / 2, 0xFFFFFFFF, true);
	}
}
