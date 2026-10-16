package com.starclient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Marks lit nether portals, end portals, end portal frames and end gateways in loaded chunks by scanning chunk
 * sections. Unlit nether portal frames are plain obsidian and cannot be told apart from other obsidian.
 */
public final class PortalFinder {
	public record Hit(BlockPos pos, float r, float g, float b) { }

	private static final int INTERVAL_TICKS = 20;
	private static final int CHUNKS_PER_PASS = 6;
	private static final int RESCAN_TICKS = 20 * 30;

	private static final Map<Long, List<Hit>> HITS = new HashMap<>();
	private static final Map<Long, Integer> SCANNED = new HashMap<>();
	private static int ticks;
	private static int cursor;

	private PortalFinder() { }

	public static void clear() {
		HITS.clear();
		SCANNED.clear();
		ticks = 0;
		cursor = 0;
	}

	public static List<Hit> hits() {
		List<Hit> out = new ArrayList<>();
		for (List<Hit> chunk : HITS.values()) out.addAll(chunk);
		return out;
	}

	public static void tick(Minecraft mc) {
		if (++ticks % INTERVAL_TICKS != 0) return;

		ChunkPos center = mc.player.chunkPosition();
		int radius = Math.min(mc.options.getEffectiveRenderDistance(), Settings.getI("portalfinder.radius"));
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
			ChunkAccess chunk = mc.level.getChunk(cx, cz, ChunkStatus.FULL, false);
			if (chunk == null) continue;

			SCANNED.put(key, ticks);
			done++;
			List<Hit> found = scan(chunk, cx, cz);
			if (found.isEmpty()) HITS.remove(key); else HITS.put(key, found);
		}
	}

	private static boolean outOfRange(long key, ChunkPos center, int radius) {
		return Math.abs(ChunkPos.getX(key) - center.x()) > radius || Math.abs(ChunkPos.getZ(key) - center.z()) > radius;
	}

	private static List<Hit> scan(ChunkAccess chunk, int cx, int cz) {
		List<Hit> found = new ArrayList<>();
		boolean nether = Settings.on("portalfinder.nether");
		boolean end = Settings.on("portalfinder.end");
		LevelChunkSection[] sections = chunk.getSections();
		for (int i = 0; i < sections.length; i++) {
			LevelChunkSection section = sections[i];
			if (section.hasOnlyAir() || !section.maybeHas(s -> colour(s, nether, end) != 0)) continue;

			int baseY = (chunk.getMinSectionY() + i) << 4;
			for (int y = 0; y < 16; y++) {
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						int rgb = colour(section.getBlockState(x, y, z), nether, end);
						if (rgb == 0) continue;
						found.add(new Hit(new BlockPos((cx << 4) + x, baseY + y, (cz << 4) + z),
								((rgb >> 16) & 0xFF) / 255f, ((rgb >> 8) & 0xFF) / 255f, (rgb & 0xFF) / 255f));
					}
				}
			}
		}
		return found;
	}

	/** RGB for a portal block, or 0 when the block is not one that is enabled. */
	private static int colour(BlockState state, boolean nether, boolean end) {
		var block = state.getBlock();
		if (nether && block == Blocks.NETHER_PORTAL) return 0xB04DFF;
		if (end && (block == Blocks.END_PORTAL_FRAME || block == Blocks.END_PORTAL)) return 0x66FF99;
		if (end && block == Blocks.END_GATEWAY) return 0xFFFFFF;
		return 0;
	}
}
