package com.starclient;

import net.minecraft.ChatFormatting;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.components.Tooltip;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.network.chat.Component;

import com.mojang.blaze3d.platform.InputConstants;

/** Module menu: category tabs, per-module toggle and key rebind, ESP settings. */
public final class ModulesScreen extends PixelScreen {
	private static final int PANEL_WIDTH = 320;
	private static final int ROW_HEIGHT = 22;
	private static final int SECTION_HEIGHT = 12;

	/** -1 shows every module, otherwise an index into {@link Module.Category#values()}. */
	private static int tab = -1;

	private KeyMapping listening;

	public ModulesScreen() {
		super(Component.literal("Star Client"));
	}

	@Override
	protected void init() {
		int cx = width / 2;
		int left = cx - PANEL_WIDTH / 2;
		int y = 18;

		Component heading = Component.literal("★ Star Client").withStyle(ChatFormatting.GOLD, ChatFormatting.BOLD);
		addCentred(heading, cx, y);
		y += 12;

		int enabled = 0;
		for (int i = 0; i < Module.ALL.size(); i++) if (StarClient.isOn(i)) enabled++;
		addCentred(Component.literal(enabled + " of " + Module.ALL.size() + " modules enabled").withStyle(ChatFormatting.GRAY), cx, y);
		y += 18;

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
				rebuildWidgets();
			}).outline(selected ? Theme.GOLD : 0).bounds(left + t * (tabWidth + gap), y, tabWidth, 20).build();
			addRenderableWidget(b);
		}
		y += 30;

		// Module rows
		// In the All view, modules are grouped under a heading per category.
		for (Module.Category cat : cats) {
			if (tab >= 0 && cat != cats[tab]) continue;
			boolean header = tab < 0;
			for (int i = 0; i < Module.ALL.size(); i++) {
				Module m = Module.ALL.get(i);
				if (m.category() != cat) continue;
				if (header) {
					Component title = Component.literal(cat.label).withStyle(cat.colour, ChatFormatting.BOLD);
					addRenderableWidget(new StringWidget(left, y, font.width(title), 9, title, font));
					y += SECTION_HEIGHT;
					header = false;
				}
				addRow(left, y, i, m);
				y += ROW_HEIGHT;
			}
		}

		y += 8;
		KeyMapping menuKey = StarClient.menuKey();
		Component menuKeyLabel = listening == menuKey ? listeningLabel() : menuKey.getTranslatedKeyMessage();
		addRenderableWidget(PixelButton.builder(Component.literal("Menu key: ").withStyle(ChatFormatting.GRAY).append(menuKeyLabel), b -> {
			listening = menuKey;
			rebuildWidgets();
		}).bounds(left, y, PANEL_WIDTH, 20).build());
		y += 24;

		addRenderableWidget(PixelButton.builder(Component.literal("Profiles..."), b -> minecraft.setScreen(new ProfilesScreen(this)))
				.bounds(left, y, PANEL_WIDTH, 20).build());
		y += 26;

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
		}).outline(StarClient.isOn(idx) ? Theme.GOLD : 0).stripe(m.category().colour.getColor()).bounds(left, y, toggleWidth, 20).build();
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
			PixelButton gear = PixelButton.builder(Component.literal("⚙"), b -> minecraft.setScreen(idx == Module.ESP
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
	public boolean keyPressed(KeyEvent event) {
		if (listening != null) {
			if (event.key() != InputConstants.KEY_ESCAPE) {
				listening.setKey(InputConstants.Type.KEYSYM.getOrCreate(event.key()));
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
		minecraft.setScreen(null);
	}
}
