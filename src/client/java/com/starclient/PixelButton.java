package com.starclient;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractButton;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.InputWithModifiers;
import net.minecraft.network.chat.Component;

/** Bevelled pixel-style button. Same builder shape as vanilla {@code Button}. */
public final class PixelButton extends AbstractButton {
	public interface OnPress {
		void press(PixelButton button);
	}

	public static final class Builder {
		private final Component message;
		private final OnPress onPress;
		private int x;
		private int y;
		private int width = 150;
		private int height = 20;
		private int outline;
		private int stripe;
		private boolean gold;
		private boolean danger;

		private Builder(Component message, OnPress onPress) {
			this.message = message;
			this.onPress = onPress;
		}

		public Builder bounds(int x, int y, int width, int height) {
			this.x = x;
			this.y = y;
			this.width = width;
			this.height = height;
			return this;
		}

		/** Primary action: gold face, dark text. */
		public Builder gold() {
			gold = true;
			return this;
		}

		public Builder danger() {
			danger = true;
			return this;
		}

		/** ARGB frame drawn inside the border; 0 for none. */
		public Builder outline(int argb) {
			outline = argb;
			return this;
		}

		/** RGB category bar on the left edge; 0 for none. */
		public Builder stripe(int rgb) {
			stripe = rgb;
			return this;
		}

		public PixelButton build() {
			PixelButton b = new PixelButton(x, y, width, height, message, onPress);
			b.outline = outline;
			b.stripe = stripe;
			b.gold = gold;
			b.danger = danger;
			return b;
		}
	}

	public static Builder builder(Component message, OnPress onPress) {
		return new Builder(message, onPress);
	}

	private final OnPress onPress;
	private int outline;
	private int stripe;
	private boolean gold;
	private boolean danger;

	private PixelButton(int x, int y, int width, int height, Component message, OnPress onPress) {
		super(x, y, width, height, message);
		this.onPress = onPress;
	}

	@Override
	public void onPress(InputWithModifiers input) {
		onPress.press(this);
	}

	@Override
	protected void extractContents(GuiGraphicsExtractor g, int mouseX, int mouseY, float partialTick) {
		int x = getX();
		int y = getY();
		int w = getWidth();
		int h = getHeight();
		boolean hot = active && isHoveredOrFocused();
		if (!active) Theme.bevel(g, x, y, w, h, Theme.FACE_DIM, Theme.LO, Theme.PANEL_LO);
		else if (gold) Theme.bevel(g, x, y, w, h, hot ? Theme.GOLD_HOT : Theme.GOLD, Theme.GOLD_HI, Theme.GOLD_LO);
		else if (danger) Theme.bevel(g, x, y, w, h, Theme.DANGER, Theme.DANGER_HI, Theme.DANGER_LO);
		else Theme.bevel(g, x, y, w, h, hot ? Theme.FACE_HOT : Theme.FACE, Theme.HI, Theme.LO);
		if (outline != 0) Theme.frame(g, x, y, w, h, outline);
		if (stripe != 0) g.fill(x + 3, y + 3, x + 5, y + h - 3, 0xFF000000 | stripe);

		var font = Minecraft.getInstance().font;
		Component msg = getMessage();
		int tw = font.width(msg);
		int tx = x + Math.max(3, (w - tw) / 2);
		int ty = y + (h - 8) / 2;
		g.text(font, msg, tx, ty, !active ? 0xFFA0A0A0 : gold ? 0xFF2A1F00 : 0xFFFFFFFF, !gold);
	}

	@Override
	protected void updateWidgetNarration(NarrationElementOutput output) {
		defaultButtonNarrationText(output);
	}
}
