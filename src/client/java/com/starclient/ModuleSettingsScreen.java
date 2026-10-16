package com.starclient;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.StringWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.MobCategory;
import net.minecraft.world.level.block.Block;

/** Generic settings page for one module, built from {@link Settings#defs}. Chest ESP adds block presets and a block picker. */
public final class ModuleSettingsScreen extends Screen {
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

		Component heading = Component.literal("◆ " + Module.ALL.get(module).name() + " Settings")
				.withStyle(Module.ALL.get(module).category().colour, ChatFormatting.BOLD);
		addRenderableWidget(new StringWidget(cx - font.width(heading) / 2, 16, font.width(heading), 9, heading, font));

		for (Settings.Def d : Settings.defs(module)) {
			if (d.toggle()) {
				addRenderableWidget(Button.builder(toggleLabel(d), b -> {
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

		if (module == Module.CHEST_ESP) y = addChestWidgets(left, y);
		y = addPresets(left, y);

		y += 6;
		addRenderableWidget(Button.builder(Component.literal("Reset to defaults"), b -> {
			Settings.reset(module);
			rebuildWidgets();
		}).bounds(cx - 100, y, 200, 20).build());
		y += 24;
		addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(cx - 100, y, 200, 20).build());
	}

	/** One button per {@link Settings#PRESETS} entry for this module, three per row. */
	private int addPresets(int left, int y) {
		Map<String, Map<String, Double>> presets = Settings.PRESETS.get(module);
		if (presets == null) return y;
		y += 4;
		Component label = Component.literal("Presets").withStyle(ChatFormatting.GRAY);
		addRenderableWidget(new StringWidget(left, y, font.width(label), 9, label, font));
		y += 12;
		int col = 0;
		for (Map.Entry<String, Map<String, Double>> preset : presets.entrySet()) {
			Map<String, Double> values = preset.getValue();
			addRenderableWidget(Button.builder(Component.literal(preset.getKey()), b -> {
				Settings.applyPreset(module, values);
				rebuildWidgets();
			}).bounds(left + col * 152, y, 148, 20).build());
			if (++col == 2) {
				col = 0;
				y += 22;
			}
		}
		return col != 0 ? y + 22 : y;
	}

	private Component toggleLabel(Settings.Def d) {
		boolean on = Settings.on(d.key());
		return Component.literal(d.label() + ": ").append(Component.literal(on ? "On" : "Off").withStyle(on ? ChatFormatting.GREEN : ChatFormatting.RED));
	}

	private int addChestWidgets(int left, int y) {
		Config cfg = Config.get();
		y += 4;
		Component label = Component.literal("Presets").withStyle(ChatFormatting.GRAY);
		addRenderableWidget(new StringWidget(left, y, font.width(label), 9, label, font));
		y += 12;

		int col = 0;
		for (Map.Entry<String, List<String>> preset : Presets.BLOCKS.entrySet()) {
			List<String> ids = preset.getValue();
			addRenderableWidget(Button.builder(Component.literal(preset.getKey()), b -> {
				cfg.setChestBlocks(ids);
				rebuildWidgets();
			}).bounds(left + col * 102, y, 96, 20).build());
			if (++col == 3) {
				col = 0;
				y += 22;
			}
		}
		if (col != 0) y += 22;

		addRenderableWidget(Button.builder(Component.literal("Choose blocks... (" + cfg.chestBlocks.size() + " selected)"), b -> {
			List<PickerScreen.Item> items = new ArrayList<>();
			for (Block block : BuiltInRegistries.BLOCK) {
				if (block.defaultBlockState().isAir()) continue;
				items.add(new PickerScreen.Item(BuiltInRegistries.BLOCK.getKey(block).toString(), block.getName()));
			}
			items.sort((a, c) -> a.name().getString().compareToIgnoreCase(c.name().getString()));
			minecraft.setScreen(new PickerScreen(this, "Chest ESP Blocks", items, cfg::hasChestBlock, cfg::toggleChestBlock,
					() -> cfg.setChestBlocks(List.of())));
		}).bounds(left, y, 300, 20).build());
		return y + 24;
	}

	/** Mob filter page for ESP: mode switch, presets and a mob picker. */
	public static final class MobFilter extends Screen {
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

			Component heading = Component.literal("◆ ESP Mob Filter").withStyle(ChatFormatting.AQUA, ChatFormatting.BOLD);
			addRenderableWidget(new StringWidget(cx - font.width(heading) / 2, 16, font.width(heading), 9, heading, font));

			addRenderableWidget(Button.builder(Component.literal("Mode: " + Config.ESP_MOB_MODES[cfg.espMobMode]), b -> {
				cfg.espMobMode = (cfg.espMobMode + 1) % Config.ESP_MOB_MODES.length;
				b.setMessage(Component.literal("Mode: " + Config.ESP_MOB_MODES[cfg.espMobMode]));
			}).bounds(left, y, 300, 20).build());
			y += 28;

			Component label = Component.literal("Presets (switch to Only selected)").withStyle(ChatFormatting.GRAY);
			addRenderableWidget(new StringWidget(left, y, font.width(label), 9, label, font));
			y += 12;

			int col = 0;
			for (Map.Entry<String, List<String>> preset : Presets.MOBS.entrySet()) {
				List<String> ids = preset.getValue();
				addRenderableWidget(Button.builder(Component.literal(preset.getKey()), b -> {
					cfg.setMobs(ids);
					if (!ids.isEmpty()) cfg.espMobMode = 1;
					rebuildWidgets();
				}).bounds(left + col * 102, y, 96, 20).build());
				if (++col == 3) {
					col = 0;
					y += 22;
				}
			}
			if (col != 0) y += 22;

			y += 4;
			addRenderableWidget(Button.builder(Component.literal("Choose mobs... (" + cfg.espMobs.size() + " selected)"), b -> {
				List<PickerScreen.Item> items = new ArrayList<>();
				for (EntityType<?> type : BuiltInRegistries.ENTITY_TYPE) {
					if (type.getCategory() == MobCategory.MISC) continue;
					items.add(new PickerScreen.Item(BuiltInRegistries.ENTITY_TYPE.getKey(type).toString(), type.getDescription()));
				}
				items.sort((a, c) -> a.name().getString().compareToIgnoreCase(c.name().getString()));
				minecraft.setScreen(new PickerScreen(this, "ESP Mobs", items, cfg::hasMob, cfg::toggleMob, () -> cfg.setMobs(List.of())));
			}).bounds(left, y, 300, 20).build());
			y += 30;

			addRenderableWidget(Button.builder(Component.literal("Done"), b -> onClose()).bounds(cx - 100, y, 200, 20).build());
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

	@Override
	public void removed() {
		Config.save();
	}

	@Override
	public void onClose() {
		minecraft.setScreen(parent);
	}
}
