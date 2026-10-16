package com.starclient;

import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** Screen base with the pixel backdrop and an optional window panel behind the widgets. */
public abstract class PixelScreen extends Screen {
	private int panelX;
	private int panelY;
	private int panelW;
	private int panelH;

	protected PixelScreen(Component title) {
		super(title);
	}

	/** Sets the window panel drawn behind the widgets; call at the end of {@code init}. */
	protected final void panel(int x, int y, int w, int h) {
		panelX = x;
		panelY = y;
		panelW = w;
		panelH = h;
	}

	/** Centred screen heading with the pixel star to its left. */
	protected final void heading(Component text, int y) {
		int x = (width - (Theme.STAR_SIZE + 4 + font.width(text))) / 2;
		Renderable r = (graphics, mouseX, mouseY, partialTick) -> {
			Theme.star(graphics, x, y, Theme.GOLD);
			graphics.text(font, text, x + Theme.STAR_SIZE + 4, y + 1, 0xFFFFFFFF, true);
		};
		addRenderableOnly(r);
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
		Theme.backdrop(graphics, width, height);
		if (panelW > 0) Theme.panel(graphics, panelX, panelY, panelW, panelH);
	}
}
