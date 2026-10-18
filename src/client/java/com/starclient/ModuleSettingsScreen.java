package com.starclient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.block.Block;

/** Generic settings page for one module, built from {@link Settings#defs}. Chest ESP adds block presets and a block picker. */
public final class ModuleSettingsScreen extends PixelScreen {
	private final Screen parent;
	private final int module;

	public ModuleSettingsScreen(Screen parent, int module) {
		super(Component.literal(Module.ALL.get(module).name() + " Settings"));
		this.parent = parent;
		this.module = module;
	}

	@Override
	protected void init() {
		int cx = width / 2;
		int left = cx - 150;
		int y = 36;

		Component heading = Component.literal("" + Module.ALL.get(module).name() + " Settings")
				.withStyle(Module.ALL.get(module).category().colour, ChatFormatting.BOLD);
		heading(heading, 16);

		for (Settings.Def d : Settings.defs(module)) {
			if (d.toggle()) {
				addRenderableWidget(PixelButton.builder(toggleLabel(d), b -> {
					Settings.set(d.key(), Settings.on(d.key()) ? 0 : 1);
					b.setMessage(toggleLabel(d));
				}).bounds(left, y, 300, 20).build());
			} else {
				addRenderableWidget(new ValueSlider(left, y, 300, 20, (Settings.get(d.key()) - d.min()) / (d.max() - d.min()),
						v -> Component.literal(d.label() + ": " + d.format(d.snap(v))),
						v -> Settings.set(d.key(), d.snap(v))));
			}
			y += 24;
		}

		for (Settings.TextDef t : Settings.texts(module)) {
			EditBox box = new EditBox(font, left, y + 10, 300, 20, Component.literal(t.label()));
			box.setMaxLength(t.maxLength());
			box.setValue(Settings.text(t.key()));
			box.setResponder(v -> Settings.setText(t.key(), v));
			addRenderableWidget(new StringWidget(left, y, 300, 9, Component.literal(t.label()), font));
			addRenderableWidget(box);
			y += 36;
		}

		if (module == Module.CHEST_ESP) y = addChestWidgets(left, y);
		if (module == Module.WAYPOINTS) {
			addRenderableWidget(PixelButton.builder(Component.literal("Manage waypoints... (" + Config.get().waypoints.size() + ")"),
					b -> minecraft.gui.setScreen(new WaypointsScreen(this))).bounds(left, y, 300, 20).build());
			y += 24;
		}
		y = addPresets(left, y);

		y += 6;
		addRenderableWidget(PixelButton.builder(Component.literal("Reset to defaults"), b -> {
			Settings.reset(module);
			rebuildWidgets();
		}).bounds(cx - 100, y, 200, 20).build());
		y += 24;
		addRenderableWidget(PixelButton.builder(Component.literal("Done"), b -> onClose()).gold().bounds(cx - 100, y, 200, 20).build());
		panel(left - 10, 10, 320, y + 28 - 10);
	}

	/** Dropdown over this module's {@link Settings#PRESETS}. */
	private int addPresets(int left, int y) {
		Map<String, Map<String, Double>> presets = Settings.PRESETS.get(module);
		if (presets == null) return y;
		List<String> names = new ArrayList<>(presets.keySet());
		addRenderableWidget(new PixelDropdown(left, y, 300, 20, "Preset", names, -1, i -> {
			Settings.applyPreset(module, presets.get(names.get(i)));
			rebuildWidgets();
		}));
		return y + 24;
	}

	private Component toggleLabel(Settings.Def d) {
		boolean on = Settings.on(d.key());
		return Component.literal(d.label() + ": ").append(Component.literal(on ? "On" : "Off").withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED));
	}

	private int addChestWidgets(int left, int y) {
		Config cfg = Config.get();
		List<String> names = new ArrayList<>(Presets.BLOCKS.keySet());
		addRenderableWidget(new PixelDropdown(left, y, 300, 20, "Block preset", names, -1, i -> {
			cfg.setChestBlocks(Presets.BLOCKS.get(names.get(i)));
			rebuildWidgets();
		}));
		y += 24;

		addRenderableWidget(PixelButton.builder(Component.literal("Choose blocks... (" + cfg.chestBlocks.size() + " selected)"), b -> {
			List<PickerScreen.Item> items = new ArrayList<>();
			for (Block block : BuiltInRegistries.BLOCK) {
				if (block.defaultBlockState().isAir()) continue;
				items.add(new PickerScreen.Item(BuiltInRegistries.BLOCK.getKey(block).toString(), block.getName()));
			}
			items.sort((a, c) -> a.name().getString().compareToIgnoreCase(c.name().getString()));
			minecraft.gui.setScreen(new PickerScreen(this, "Chest ESP Blocks", items, cfg::hasChestBlock, cfg::toggleChestBlock,
					() -> cfg.setChestBlocks(List.of())));
		}).bounds(left, y, 300, 20).build());
		return y + 24;
	}

	/** Mob filter page for ESP: mode switch, presets and a mob picker. */
	public static final class MobFilter extends PixelScreen {
		private final Screen parent;

		public MobFilter(Screen parent) {
			super(Component.literal("ESP Mob Filter"));
			this.parent = parent;
		}

		@Override
		protected void init() {
			Config cfg = Config.get();
			int cx = width / 2;
			int left = cx - 150;
			int y = 36;

			Component heading = Component.literal("ESP Mob Filter").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD);
			heading(heading, 16);

			addRenderableWidget(new PixelDropdown(left, y, 300, 20, "Mode", List.of(Config.ESP_MOB_MODES), cfg.espMobMode, i -> cfg.espMobMode = i));
			y += 24;
			List<String> names = new ArrayList<>(Presets.MOBS.keySet());
			addRenderableWidget(new PixelDropdown(left, y, 300, 20, "Preset", names, -1, i -> {
				List<String> ids = Presets.MOBS.get(names.get(i));
				cfg.setMobs(ids);
				if (!ids.isEmpty()) cfg.espMobMode = 1;
				rebuildWidgets();
			}));
			y += 32;
			addRenderableWidget(PixelButton.builder(Component.literal("Choose mobs... (" + cfg.espMobs.size() + " selected)"), b -> {
				List<PickerScreen.Item> items = new ArrayList<>();
				for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
					if (type.getCategory() == MobCategory.MISC) continue;
					items.add(new PickerScreen.Item(BuiltInRegistries.ENTITY_TYPE.getKey(type).toString(), type.getDescription()));
				}
				items.sort((a, c) -> a.name().getString().compareToIgnoreCase(c.name().getString()));
				minecraft.gui.setScreen(new PickerScreen(this, "ESP Mobs", items, cfg::hasMob, cfg::toggleMob, () -> cfg.setMobs(List.of())));
			}).bounds(left, y, 300, 20).build());
			y += 30;

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

	@Override
	public void removed() {
		Config.save();
	}

	@Override
	public void onClose() {
		minecraft.gui.setScreen(parent);
	}
}
