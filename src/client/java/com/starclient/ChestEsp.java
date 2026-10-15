package com.starclient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.AbstractFurnaceBlockEntity;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BeaconBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BrewingStandBlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.CrafterBlockEntity;
import net.minecraft.world.level.block.entity.DispenserBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.entity.HopperBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.entity.TrappedChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Finds storage and farm blocks in nearby loaded chunks: block entities (chests,
 * hoppers, droppers, furnaces...) plus plain blocks (growing amethyst).
 */
public final class ChestEsp {
	public record Hit(BlockPos pos, float r, float g, float b) { }

	private static final int INTERVAL_TICKS = 10;
	private static final int MAX_RADIUS_CHUNKS = 8;
	private static final int BLOCK_CHUNKS_PER_PASS = 8;
	private static final int BLOCK_RESCAN_TICKS = 100;

	private static volatile List<Hit> hits = List.of();
	private static final Map<Long, List<Hit>> BLOCK_HITS = new HashMap<>();
	private static final Map<Long, Integer> BLOCK_SCANNED = new HashMap<>();
	private static int ticks;

	private ChestEsp() { }

	public static List<Hit> hits() {
		return hits;
	}

	public static void clear() {
		hits = List.of();
		BLOCK_HITS.clear();
		BLOCK_SCANNED.clear();
		ticks = 0;
	}

	public static void tick(Minecraft mc) {
		if (++ticks % INTERVAL_TICKS != 0) return;

		ChunkPos center = mc.player.chunkPosition();
		int radius = Math.min(mc.options.getEffectiveRenderDistance(), MAX_RADIUS_CHUNKS);
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
				for (BlockPos pos : chunk.getBlockEntitiesPos()) {
					BlockEntity be = chunk.getBlockEntity(pos);
					if (be == null) continue;
					Hit hit = classify(be);
					if (hit != null) out.add(hit);
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

	private static boolean isBlockTarget(BlockState state) {
		Block b = state.getBlock();
		return b == Blocks.BUDDING_AMETHYST || b == Blocks.AMETHYST_CLUSTER || b == Blocks.LARGE_AMETHYST_BUD
				|| b == Blocks.MEDIUM_AMETHYST_BUD || b == Blocks.SMALL_AMETHYST_BUD;
	}

	private static float[] blockColor(Block b) {
		if (b == Blocks.BUDDING_AMETHYST) return new float[] {1.0f, 0.2f, 1.0f};
		return new float[] {0.75f, 0.55f, 1.0f};
	}

	private static Hit classify(BlockEntity be) {
		BlockPos p = be.getBlockPos();
		if (be instanceof TrappedChestBlockEntity) return new Hit(p, 1.0f, 0.2f, 0.2f);
		if (be instanceof ChestBlockEntity) return new Hit(p, 1.0f, 0.65f, 0.1f);
		if (be instanceof EnderChestBlockEntity) return new Hit(p, 0.6f, 0.2f, 1.0f);
		if (be instanceof BarrelBlockEntity) return new Hit(p, 0.75f, 0.5f, 0.25f);
		if (be instanceof ShulkerBoxBlockEntity) return new Hit(p, 1.0f, 0.4f, 0.9f);
		if (be instanceof HopperBlockEntity) return new Hit(p, 0.85f, 0.85f, 0.9f);
		if (be instanceof DispenserBlockEntity) return new Hit(p, 0.55f, 0.55f, 0.6f); // includes droppers
		if (be instanceof CrafterBlockEntity) return new Hit(p, 0.9f, 0.9f, 0.3f);
		if (be instanceof AbstractFurnaceBlockEntity) return new Hit(p, 1.0f, 0.5f, 0.3f);
		if (be instanceof BrewingStandBlockEntity) return new Hit(p, 0.4f, 0.9f, 0.7f);
		if (be instanceof BeaconBlockEntity) return new Hit(p, 0.3f, 1.0f, 1.0f);
		return null;
	}
}
