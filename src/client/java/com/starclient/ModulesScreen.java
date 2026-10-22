package com.starclient;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.components.Renderable;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

import com.mojang.blaze3d.platform.InputConstants;

/** Module menu: category tabs, per-module toggle and key rebind, ESP settings. */
public final class ModulesScreen extends PixelScreen {
	private static final int PANEL_WIDTH = 320;
	private static final int ROW_HEIGHT = 22;
	private static final int SECTION_HEIGHT = 12;
	/** Space below the module list: gap, menu key and profiles buttons (one row), Done, panel padding and screen margin. */
	private static final int FOOTER_HEIGHT = 8 + 24 + 28 + 6;

	/** -1 shows every module, otherwise an index into {@link Module.Category#values()}. */
	private static int tab = -1;

	private KeyMapping listening;
	private int scroll;
	private int maxScroll;

	public ModulesScreen() {
		super(Component.literal("Star Client"));
	}

	@Override
	protected void init() {
		int cx = width / 2;
		int left = cx - PANEL_WIDTH / 2;
		int y = 12;

		Component heading = Component.literal("Star Client").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
		heading(heading, y);
		y += 12;

		int enabled = 0;
		for (int i = 0; i < Module.ALL.size(); i++) if (StarClient.isOn(i)) enabled++;
		addCentred(Component.literal(enabled + " of " + Module.ALL.size() + " modules enabled").withStyle(ChatFormatting.GRAY), cx, y);
		y += 16;

		// Tabs
		Module.Category[] cats = Module.Category.values();
		int tabCount = cats.length + 1;
		int gap = 4;
		int tabWidth = (PANEL_WIDTH - gap * (tabCount - 1)) / tabCount;
		for (int t = 0; t < tabCount; t++) {
			final int target = t - 1;
			boolean selected = tab == target;
			String label = t == 0 ? "All" : cats[t - 1].label;
			ChatFormatting colour = t == 0 ? ChatFormatting.WHITE : cats[t - 1].colour;
			Component text = selected
					? Component.literal(label).withStyle(colour, ChatFormatting.BOLD, ChatFormatting.UNDERLINE)
					: Component.literal(label).withStyle(ChatFormatting.GRAY);
			PixelButton b = PixelButton.builder(text, btn -> {
				tab = target;
				scroll = 0;
				rebuildWidgets();
			}).outline(selected ? Theme.GOLD : 0).bounds(left + t * (tabWidth + gap), y, tabWidth, 20).build();
			addRenderableWidget(b);
		}
		y += 26;

		// Module rows, in a viewport that scrolls when they do not fit above the footer.
		// In the All view, modules are grouped under a heading per category.
		List<int[]> items = new ArrayList<>(); // {relative y, module index or -1 - category}
		int contentHeight = 0;
		for (Module.Category cat : cats) {
			if (tab >= 0 && cat != cats[tab]) continue;
			boolean header = tab < 0;
			for (int i = 0; i < Module.ALL.size(); i++) {
				if (Module.ALL.get(i).category() != cat) continue;
				if (header) {
					items.add(new int[] {contentHeight, -1 - cat.ordinal()});
					contentHeight += SECTION_HEIGHT;
					header = false;
				}
				items.add(new int[] {contentHeight, i});
				contentHeight += ROW_HEIGHT;
			}
		}
		int listTop = y;
		int viewHeight = Math.max(ROW_HEIGHT, Math.min(contentHeight, height - FOOTER_HEIGHT - listTop));
		maxScroll = Math.max(0, contentHeight - viewHeight);
		scroll = Math.clamp(scroll, 0, maxScroll);
		for (int[] item : items) {
			int rowY = listTop + item[0] - scroll;
			boolean isHeader = item[1] < 0;
			if (rowY < listTop || rowY + (isHeader ? 9 : 20) > listTop + viewHeight) continue;
			if (isHeader) {
				Module.Category cat = cats[-1 - item[1]];
				Component title = Component.literal(cat.label).withStyle(cat.colour, ChatFormatting.BOLD);
				addRenderableWidget(new StringWidget(left, rowY, font.width(title), 9, title, font));
			} else {
				addRow(left, rowY, item[1], Module.ALL.get(item[1]));
			}
		}
		if (maxScroll > 0) {
			int trackX = left + PANEL_WIDTH + 4;
			int thumbHeight = Math.max(10, viewHeight * viewHeight / contentHeight);
			int thumbY = listTop + (viewHeight - thumbHeight) * scroll / maxScroll;
			int trackBottom = listTop + viewHeight;
			Renderable bar = (graphics, mouseX, mouseY, partialTick) -> {
				graphics.fill(trackX, listTop, trackX + 3, trackBottom, Theme.WELL);
				graphics.fill(trackX, thumbY, trackX + 3, thumbY + thumbHeight, Theme.GOLD);
			};
			addRenderableOnly(bar);
		}
		y = listTop + viewHeight;

		y += 8;
		KeyMapping menuKey = StarClient.menuKey();
		Component menuKeyLabel = listening == menuKey ? listeningLabel() : menuKey.getTranslatedKeyMessage();
		int half = (PANEL_WIDTH - 4) / 2;
		addRenderableWidget(PixelButton.builder(Component.literal("Menu key: ").withStyle(ChatFormatting.GRAY).append(menuKeyLabel), b -> {
			listening = menuKey;
			rebuildWidgets();
		}).bounds(left, y, half, 20).build());
		addRenderableWidget(PixelButton.builder(Component.literal("Profiles..."), b -> minecraft.gui.setScreen(new ProfilesScreen(this)))
				.bounds(left + half + 4, y, half, 20).build());
		y += 24;

		addRenderableWidget(PixelButton.builder(Component.literal("Done"), b -> onClose()).gold()
				.bounds(cx - 100, y, 200, 20).build());
		panel(left - 10, 6, PANEL_WIDTH + 20, y + 28 - 6);
	}

	private void addRow(int left, int y, int idx, Module m) {
		boolean hasGear = m.hasSettings();
		int toggleWidth = 150;
		int gearWidth = 20;

		PixelButton toggle = PixelButton.builder(moduleLabel(idx, m), b -> {
			StarClient.toggle(minecraft, idx);
			rebuildWidgets();
		}).outline(StarClient.isOn(idx) ? Theme.GOLD : 0).stripe(m.category().rgb).bounds(left, y, toggleWidth, 20).build();
		toggle.setTooltip(Tooltip.create(Component.literal(m.description())));
		addRenderableWidget(toggle);

		KeyMapping key = StarClient.keys()[idx];
		Component keyLabel = listening == key ? listeningLabel() : key.getTranslatedKeyMessage();
		int keyWidth = PANEL_WIDTH - toggleWidth - 4 - (hasGear ? gearWidth + 4 : 0);
		PixelButton keyButton = PixelButton.builder(keyLabel, b -> {
			listening = key;
			rebuildWidgets();
		}).bounds(left + toggleWidth + 4, y, keyWidth, 20).build();
		keyButton.setTooltip(Tooltip.create(Component.literal("Click, then press a key to rebind")));
		addRenderableWidget(keyButton);

		if (hasGear) {
			PixelButton gear = PixelButton.builder(Component.literal("⚙"), b -> minecraft.gui.setScreen(idx == Module.ESP
					? new EspScreen(this) : new ModuleSettingsScreen(this, idx)))
					.bounds(left + PANEL_WIDTH - gearWidth, y, gearWidth, 20).build();
			gear.setTooltip(Tooltip.create(Component.literal("Module settings")));
			addRenderableWidget(gear);
		}
	}

	private Component moduleLabel(int idx, Module m) {
		boolean on = StarClient.isOn(idx);
		Component dot = Component.literal(on ? "● " : "○ ").withStyle(on ? ChatFormatting.GREEN : ChatFormatting.DARK_GRAY);
		Component name = Component.literal(m.name()).withStyle(on ? ChatFormatting.WHITE : ChatFormatting.GRAY);
		return Component.empty().append(dot).append(name);
	}

	private static Component listeningLabel() {
		return Component.literal("> press a key <").withStyle(ChatFormatting.YELLOW);
	}

	private void addCentred(Component text, int cx, int y) {
		int w = font.width(text);
		addRenderableWidget(new StringWidget(cx - w / 2, y, w, 9, text, font));
	}

	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (maxScroll > 0 && scrollY != 0) {
			int next = Math.clamp(scroll - (int) Math.signum(scrollY) * ROW_HEIGHT, 0, maxScroll);
			if (next != scroll) {
				scroll = next;
				rebuildWidgets();
			}
			return true;
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	@Override
	public boolean keyPressed(KeyEvent event) {
		if (listening != null) {
			if (event.key() != InputConstants.KEY_ESCAPE) {
				listening.setKey(InputConstants.Type.KEYBOARD.getOrCreate(event.key()));
				KeyMapping.resetMapping();
				minecraft.options.save();
			}
			listening = null;
			rebuildWidgets();
			return true;
		}
		return super.keyPressed(event);
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(null);
	}
}
