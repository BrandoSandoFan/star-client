package com.starclient;

import java.util.function.Consumer;
import java.util.function.DoubleFunction;

import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

/** Slider over 0..1 with a caller-supplied label and change callback. */
public final class ValueSlider extends AbstractSliderButton {
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
}
