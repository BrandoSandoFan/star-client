package com.starclient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SpawnerBlockEntity;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;

/**
 * Hides storage that appears in bulk, in one tick, inside chunks that were already
 * loaded: the signature of admin bait (a pasted stash). Chunks seen for the first
 * time are only a baseline, so walking into a real base still shows it. Players
 * place one block at a time, so a burst of several storage blocks in the same area
 * is treated as bait and kept out of Chest ESP and Base Finder. Spawners cannot be
 * placed in survival, so a new one is bait on its own.
 */
public final class AntiBait {
	/** Storage/marker block entities added in one tick within {@link #BLOCK_AREA} of each other. */
	private static final int BLOCK_MIN = 3;
	private static final double BLOCK_AREA = 16;
	/** Keep an unloaded chunk's contents this long, so an unload + resend still diffs against them. */
	private static final int FORGET_TICKS = 100;

	private static final class Tracked {
		final Set<BlockPos> known = new HashSet<>();
		final Set<BlockPos> bait = new HashSet<>();
		int lastSeen;
	}

	private static final Map<Long, Tracked> CHUNKS = new HashMap<>();
	private static ClientLevel level;
	private static int ticks;

	private AntiBait() { }

	public static void clear() {
		CHUNKS.clear();
		level = null;
	}

	public static boolean hidesBlock(BlockPos pos) {
		if (!StarClient.antiBaitOn) return false;
		Tracked t = CHUNKS.get(ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4));
		return t != null && t.bait.contains(pos);
	}

	public static boolean hidesChunk(long key) {
		if (!StarClient.antiBaitOn) return false;
		Tracked t = CHUNKS.get(key);
		return t != null && !t.bait.isEmpty();
	}

	public static void tick(Minecraft mc) {
		ticks++;
		if (mc.level != level) {
			clear();
			level = mc.level;
		}

		ChunkPos center = mc.player.chunkPosition();
		int radius = mc.options.getEffectiveRenderDistance() + 2;
		List<BlockPos> added = new ArrayList<>();
		List<BlockPos> spawners = new ArrayList<>();

		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				int cx = center.x() + dx;
				int cz = center.z() + dz;
				ChunkAccess access = mc.level.getChunk(cx, cz, ChunkStatus.FULL, false);
				if (!(access instanceof LevelChunk chunk)) continue;

				long key = ChunkPos.pack(cx, cz);
				Map<BlockPos, BlockEntity> now = chunk.getBlockEntities();
				Tracked t = CHUNKS.get(key);
				if (t == null) {
					// First sight of this chunk: everything in it is baseline.
					t = new Tracked();
					t.known.addAll(now.keySet());
					CHUNKS.put(key, t);
				} else {
					t.known.retainAll(now.keySet());
					t.bait.retainAll(now.keySet());
					for (Map.Entry<BlockPos, BlockEntity> e : now.entrySet()) {
						if (!t.known.add(e.getKey())) continue;
						if (e.getValue() instanceof SpawnerBlockEntity) spawners.add(e.getKey());
						else if (counts(e.getValue())) added.add(e.getKey());
					}
				}
				t.lastSeen = ticks;
			}
		}
		CHUNKS.values().removeIf(t -> ticks - t.lastSeen > FORGET_TICKS);

		List<BlockPos> bait = new ArrayList<>(spawners);
		if (added.size() >= BLOCK_MIN) {
			List<Vec3> points = new ArrayList<>();
			for (BlockPos p : added) points.add(Vec3.atCenterOf(p));
			for (int i : crowded(points, BLOCK_AREA, BLOCK_MIN)) bait.add(added.get(i));
		}
		if (bait.isEmpty()) return;
		for (BlockPos p : bait) CHUNKS.get(ChunkPos.pack(p.getX() >> 4, p.getZ() >> 4)).bait.add(p);
		BlockPos at = bait.get(0);
		mc.gui.setOverlayMessage(Component.literal("Anti Bait: hid " + bait.size() + " new block" + (bait.size() == 1 ? "" : "s")
				+ " near " + at.getX() + " " + at.getY() + " " + at.getZ()).withStyle(ChatFormatting.GOLD), false);
	}

	/** Storage the Chest ESP draws, or a block Base Finder counts. */
	private static boolean counts(BlockEntity be) {
		return ChestEsp.classify(be) != null || BaseFinder.isMarker(be.getBlockState());
	}

	/** Indices of points with at least {@code min} points (itself included) within {@code area} blocks. */
	private static List<Integer> crowded(List<Vec3> points, double area, int min) {
		double max = area * area;
		List<Integer> out = new ArrayList<>();
		for (int i = 0; i < points.size(); i++) {
			int n = 0;
			for (Vec3 q : points) if (points.get(i).distanceToSqr(q) <= max) n++;
			if (n >= min) out.add(i);
		}
		return out;
	}
}
