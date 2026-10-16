package com.starclient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Flags loaded chunks that contain many blocks which almost never generate
 * naturally. Villages, strongholds, mansions, bastions etc. also contain some of
 * these blocks, so expect false positives near generated structures.
 */
public final class BaseFinder {
	public record Hit(BlockPos pos, int count) { }

	private static final int SCAN_INTERVAL_TICKS = 20;
	private static final int CHUNKS_PER_PASS = 6;
	private static final int RESCAN_TICKS = 20 * 60;

	private static final Set<Block> MARKERS = Set.of(
			Blocks.CHEST, Blocks.TRAPPED_CHEST, Blocks.ENDER_CHEST, Blocks.BARREL,
			Blocks.FURNACE, Blocks.BLAST_FURNACE, Blocks.SMOKER,
			Blocks.CRAFTING_TABLE, Blocks.ENCHANTING_TABLE, Blocks.ANVIL, Blocks.BEACON,
			Blocks.HOPPER, Blocks.DISPENSER, Blocks.DROPPER, Blocks.OBSERVER,
			Blocks.PISTON, Blocks.STICKY_PISTON, Blocks.REDSTONE_LAMP, Blocks.REPEATER, Blocks.COMPARATOR,
			Blocks.GLASS, Blocks.GLASS_PANE, Blocks.SEA_LANTERN, Blocks.LANTERN,
			Blocks.IRON_BLOCK, Blocks.GOLD_BLOCK, Blocks.DIAMOND_BLOCK, Blocks.EMERALD_BLOCK, Blocks.NETHERITE_BLOCK,
			// Amethyst farms
			Blocks.BUDDING_AMETHYST, Blocks.AMETHYST_CLUSTER, Blocks.LARGE_AMETHYST_BUD,
			Blocks.MEDIUM_AMETHYST_BUD, Blocks.SMALL_AMETHYST_BUD,
			// Farm and redstone materials
			Blocks.CRAFTER, Blocks.BREWING_STAND, Blocks.NOTE_BLOCK, Blocks.TNT, Blocks.TARGET,
			Blocks.SLIME_BLOCK, Blocks.HONEY_BLOCK, Blocks.REDSTONE_BLOCK, Blocks.DAYLIGHT_DETECTOR,
			Blocks.LEVER, Blocks.TRIPWIRE_HOOK, Blocks.POWERED_RAIL, Blocks.DETECTOR_RAIL, Blocks.ACTIVATOR_RAIL,
			Blocks.SCAFFOLDING, Blocks.COMPOSTER, Blocks.LECTERN, Blocks.CARTOGRAPHY_TABLE,
			Blocks.SMITHING_TABLE, Blocks.GRINDSTONE, Blocks.STONECUTTER);

	private static final Map<Long, Hit> HITS = new HashMap<>();
	private static final Map<Long, Integer> SCANNED = new HashMap<>();
	private static int ticks;
	private static int cursor;

	private BaseFinder() { }

	public static void clear() {
		HITS.clear();
		SCANNED.clear();
		ticks = 0;
		cursor = 0;
	}

	public static List<Hit> hits() {
		List<Hit> out = new ArrayList<>();
		for (Map.Entry<Long, Hit> e : HITS.entrySet()) {
			if (!AntiBait.hidesChunk(e.getKey())) out.add(e.getValue());
		}
		return out;
	}

	public static void tick(Minecraft mc) {
		ticks++;
		if (ticks % SCAN_INTERVAL_TICKS != 0) return;

		ClientLevel level = mc.level;
		ChunkPos center = mc.player.chunkPosition();
		int radius = mc.options.getEffectiveRenderDistance();

		// Drop results that fell out of range.
		HITS.keySet().removeIf(k -> outOfRange(k, center, radius));
		SCANNED.keySet().removeIf(k -> outOfRange(k, center, radius));

		int side = radius * 2 + 1;
		int total = side * side;
		int done = 0;

		for (int i = 0; i < total && done < CHUNKS_PER_PASS; i++) {
			cursor = (cursor + 1) % total;
			int cx = center.x() - radius + cursor % side;
			int cz = center.z() - radius + cursor / side;
			long key = ChunkPos.pack(cx, cz);

			Integer last = SCANNED.get(key);
			if (last != null && ticks - last < RESCAN_TICKS) continue;

			ChunkAccess chunk = level.getChunk(cx, cz, ChunkStatus.FULL, false);
			if (chunk == null) continue;

			SCANNED.put(key, ticks);
			done++;

			Hit hit = scan(chunk, cx, cz);
			if (hit != null) HITS.put(key, hit); else HITS.remove(key);
		}
	}

	private static boolean outOfRange(long key, ChunkPos center, int radius) {
		return Math.abs(ChunkPos.getX(key) - center.x()) > radius || Math.abs(ChunkPos.getZ(key) - center.z()) > radius;
	}

	private static Hit scan(ChunkAccess chunk, int cx, int cz) {
		LevelChunkSection[] sections = chunk.getSections();
		int count = 0;
		long sx = 0, sy = 0, sz = 0;

		for (int i = 0; i < sections.length; i++) {
			LevelChunkSection section = sections[i];
			if (section.hasOnlyAir() || !section.maybeHas(BaseFinder::isMarker)) continue;

			int baseY = (chunk.getMinSectionY() + i) << 4;
			for (int y = 0; y < 16; y++) {
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						if (isMarker(section.getBlockState(x, y, z))) {
							count++;
							sx += (cx << 4) + x;
							sy += baseY + y;
							sz += (cz << 4) + z;
						}
					}
				}
			}
		}

		if (count < Settings.getI("basefinder.min")) return null;
		return new Hit(new BlockPos((int) (sx / count), (int) (sy / count), (int) (sz / count)), count);
	}

	static boolean isMarker(BlockState state) {
		return MARKERS.contains(state.getBlock());
	}
}
