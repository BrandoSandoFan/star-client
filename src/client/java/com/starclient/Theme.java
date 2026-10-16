package com.starclient;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Pixel look: flat slate fills with 1px bevels and hard shadows. Colours are ARGB. */
public final class Theme {
	public static final int INK = 0xFF0B0C0F;
	public static final int FACE = 0xFF5A606D;
	public static final int FACE_HOT = 0xFF6C7383;
	public static final int FACE_DIM = 0xFF3D424E;
	public static final int HI = 0xFF8D94A4;
	public static final int LO = 0xFF373B46;
	public static final int GOLD = 0xFFF2C14E;
	public static final int GOLD_HOT = 0xFFFFD06A;
	public static final int GOLD_HI = 0xFFFFE7A3;
	public static final int GOLD_LO = 0xFFB88A1F;
	public static final int DANGER = 0xFF8A3030;
	public static final int DANGER_HI = 0xFFC75A5A;
	public static final int DANGER_LO = 0xFF4D1818;
	public static final int PANEL = 0xFF2C3038;
	public static final int PANEL_HI = 0xFF4A505C;
	public static final int PANEL_LO = 0xFF16181D;
	public static final int WELL = 0xFF1B1E25;
	public static final int BACK_A = 0xFF1D222B;
	public static final int BACK_B = 0xFF242A35;
	private static final int TILE = 12;

	private Theme() { }

	/** Checkered backdrop over the whole screen. */
	public static void backdrop(GuiGraphicsExtractor g, int width, int height) {
		g.fill(0, 0, width, height, BACK_A);
		for (int ty = 0; ty * TILE < height; ty++) {
			for (int tx = 0; tx * TILE < width; tx++) {
				if (((tx + ty) & 1) != 0) continue;
				g.fill(tx * TILE, ty * TILE, Math.min(width, (tx + 1) * TILE), Math.min(height, (ty + 1) * TILE), BACK_B);
			}
		}
	}

	/** Raised bevelled box with a black border. */
	public static void bevel(GuiGraphicsExtractor g, int x, int y, int w, int h, int face, int hi, int lo) {
		g.fill(x, y, x + w, y + h, INK);
		g.fill(x + 1, y + 1, x + w - 1, y + h - 1, face);
		g.fill(x + 1, y + 1, x + w - 1, y + 2, hi);
		g.fill(x + 1, y + 1, x + 2, y + h - 1, hi);
		g.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, lo);
		g.fill(x + w - 2, y + 1, x + w - 1, y + h - 1, lo);
	}

	/** Sunken box used for slider tracks and inputs. */
	public static void well(GuiGraphicsExtractor g, int x, int y, int w, int h) {
		g.fill(x, y, x + w, y + h, INK);
		g.fill(x + 1, y + 1, x + w - 1, y + h - 1, WELL);
		g.fill(x + 1, y + h - 2, x + w - 1, y + h - 1, LO);
		g.fill(x + w - 2, y + 1, x + w - 1, y + h - 1, LO);
	}

	/** Large window panel with a drop shadow. */
	public static void panel(GuiGraphicsExtractor g, int x, int y, int w, int h) {
		g.fill(x + 3, y + 3, x + w + 3, y + h + 3, 0x66000000);
		bevel(g, x, y, w, h, PANEL, PANEL_HI, PANEL_LO);
	}

	/** One-unit coloured frame just inside the border. */
	public static void frame(GuiGraphicsExtractor g, int x, int y, int w, int h, int colour) {
		g.outline(x + 1, y + 1, w - 2, h - 2, colour);
	}
}
