package com.starclient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.util.Mth;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Finds the blocks chosen in {@link Config#chestBlocks} in nearby loaded chunks: block entities
 * (chests, hoppers, furnaces...) are read directly, other blocks (ores, amethyst...) by scanning chunk sections.
 */
public final class ChestEsp {
	public record Hit(BlockPos pos, float r, float g, float b) { }

	private static final int INTERVAL_TICKS = 10;
	private static final int BLOCK_CHUNKS_PER_PASS = 8;
	private static final int BLOCK_RESCAN_TICKS = 100;

	private static volatile List<Hit> hits = List.of();
	private static final Map<Long, List<Hit>> BLOCK_HITS = new HashMap<>();
	private static final Map<Long, Integer> BLOCK_SCANNED = new HashMap<>();
	private static final Map<Block, float[]> COLORS = new HashMap<>();
	private static int ticks;
	private static Set<Block> targets = Set.of();
	private static int targetsVersion = -1;

	private ChestEsp() { }

	public static List<Hit> hits() {
		if (!StarClient.antiBaitOn) return hits;
		List<Hit> out = new ArrayList<>(hits.size());
		for (Hit h : hits) if (!AntiBait.hidesBlock(h.pos())) out.add(h);
		return out;
	}

	public static void clear() {
		hits = List.of();
		BLOCK_HITS.clear();
		BLOCK_SCANNED.clear();
		ticks = 0;
		targetsVersion = -1;
	}

	/** Whether {@code b} is one of the blocks chosen for Chest ESP. */
	static boolean isTarget(Block b) {
		refreshTargets();
		return targets.contains(b);
	}

	/** Re-resolves the chosen blocks (they may have changed) and drops cached chunk scans. */
	private static void refreshTargets() {
		Config cfg = Config.get();
		if (targetsVersion == cfg.chestVersion) return;
		targetsVersion = cfg.chestVersion;
		Set<Block> set = new HashSet<>();
		for (String id : cfg.chestBlocks) {
			Identifier key = Identifier.tryParse(id);
			if (key != null && BuiltInRegistries.BLOCK.containsKey(key)) set.add(BuiltInRegistries.BLOCK.getValue(key));
		}
		targets = set;
		BLOCK_HITS.clear();
		BLOCK_SCANNED.clear();
	}

	public static void tick(Minecraft mc) {
		if (++ticks % INTERVAL_TICKS != 0) return;

		refreshTargets();
		ChunkPos center = mc.player.chunkPosition();
		int radius = Math.min(mc.options.getEffectiveRenderDistance(), Settings.getI("chestesp.radius"));
		List<Hit> out = new ArrayList<>();
		int scanned = 0;

		BLOCK_HITS.keySet().removeIf(k -> outOfRange(k, center, radius));
		BLOCK_SCANNED.keySet().removeIf(k -> outOfRange(k, center, radius));

		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				int cx = center.x() + dx;
				int cz = center.z() + dz;
				ChunkAccess chunk = mc.level.getChunk(cx, cz, ChunkStatus.FULL, false);
				if (chunk == null) continue;
				if (chunk instanceof LevelChunk lc) {
					// Live map: no copy and no per-position lookup.
					for (BlockEntity be : lc.getBlockEntities().values()) {
						Block block = be.getBlockState().getBlock();
						if (targets.contains(block)) {
							float[] c = blockColor(block);
							out.add(new Hit(be.getBlockPos(), c[0], c[1], c[2]));
						}
					}
				}

				long key = ChunkPos.pack(cx, cz);
				Integer last = BLOCK_SCANNED.get(key);
				if (scanned < BLOCK_CHUNKS_PER_PASS && (last == null || ticks - last >= BLOCK_RESCAN_TICKS)) {
					BLOCK_SCANNED.put(key, ticks);
					BLOCK_HITS.put(key, scanBlocks(chunk, cx, cz));
					scanned++;
				}
				List<Hit> cached = BLOCK_HITS.get(key);
				if (cached != null) out.addAll(cached);
			}
		}
		hits = out;
	}

	private static boolean outOfRange(long key, ChunkPos center, int radius) {
		return Math.abs(ChunkPos.getX(key) - center.x()) > radius || Math.abs(ChunkPos.getZ(key) - center.z()) > radius;
	}

	private static List<Hit> scanBlocks(ChunkAccess chunk, int cx, int cz) {
		List<Hit> found = new ArrayList<>();
		LevelChunkSection[] sections = chunk.getSections();
		for (int i = 0; i < sections.length; i++) {
			LevelChunkSection section = sections[i];
			if (section.hasOnlyAir() || !section.maybeHas(ChestEsp::isBlockTarget)) continue;

			int baseY = (chunk.getMinSectionY() + i) << 4;
			for (int y = 0; y < 16; y++) {
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						BlockState state = section.getBlockState(x, y, z);
						if (!isBlockTarget(state)) continue;
						float[] c = blockColor(state.getBlock());
						found.add(new Hit(new BlockPos((cx << 4) + x, baseY + y, (cz << 4) + z), c[0], c[1], c[2]));
					}
				}
			}
		}
		return found;
	}

	/** Block entities are picked up every pass, so the section scan only covers plain blocks. */
	private static boolean isBlockTarget(BlockState state) {
		return !state.hasBlockEntity() && targets.contains(state.getBlock());
	}

	private static float[] blockColor(Block b) {
		return COLORS.computeIfAbsent(b, ChestEsp::computeColor);
	}

	private static float[] computeColor(Block b) {
		String path = BuiltInRegistries.BLOCK.getKey(b).getPath();
		int rgb = switch (path) {
			case "trapped_chest" -> 0xFF3333;
			case "chest" -> 0xFFA61A;
			case "ender_chest" -> 0x9933FF;
			case "barrel" -> 0xBF8040;
			case "hopper" -> 0xD9D9E6;
			case "dispenser", "dropper" -> 0x8C8C99;
			case "crafter" -> 0xE6E64D;
			case "furnace", "blast_furnace", "smoker" -> 0xFF804D;
			case "brewing_stand" -> 0x66E6B3;
			case "beacon" -> 0x4DFFFF;
			case "budding_amethyst" -> 0xFF33FF;
			case "amethyst_cluster", "large_amethyst_bud", "medium_amethyst_bud", "small_amethyst_bud" -> 0xBF8CFF;
			case "spawner", "trial_spawner" -> 0x3366FF;
			case "diamond_ore", "deepslate_diamond_ore" -> 0x33E6FF;
			case "emerald_ore", "deepslate_emerald_ore" -> 0x33FF66;
			case "gold_ore", "deepslate_gold_ore", "nether_gold_ore" -> 0xFFD633;
			case "iron_ore", "deepslate_iron_ore" -> 0xE6BFA6;
			case "ancient_debris" -> 0x8C5A3C;
			default -> path.endsWith("shulker_box") ? 0xFF66E6 : Mth.hsvToRgb((path.hashCode() & 0x7FFFFFFF) % 360 / 360f, 0.7f, 1.0f);
		};
		return new float[] {((rgb >> 16) & 0xFF) / 255f, ((rgb >> 8) & 0xFF) / 255f, (rgb & 0xFF) / 255f};
	}
}
