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
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.Vec3;

/**
 * Hides things that appear in bulk, in one tick, inside chunks that were already
 * loaded: the signature of admin bait (a pasted stash, a batch of summoned players).
 * Chunks seen for the first time are only a baseline, so walking into a real base
 * still shows it. Players place one block at a time, so a burst of several storage
 * blocks in the same area is treated as bait and kept out of Chest ESP and Base Finder.
 */
public final class AntiBait {
	/** Storage/marker block entities added in one tick within {@link #BLOCK_AREA} of each other. */
	private static final int BLOCK_MIN = 3;
	private static final double BLOCK_AREA = 16;
	/** Above a spawner's 4 per tick. */
	private static final int ENTITY_MIN = 5;
	private static final double ENTITY_AREA = 8;
	/** Natural spawns need 24+ blocks from a player and tracking ranges are wider, so anything closer was put there. */
	private static final double ENTITY_NEAR = 24;
	/** Keep an unloaded chunk's contents this long, so an unload + resend still diffs against them. */
	private static final int FORGET_TICKS = 100;
	/** After joining, changing dimension or teleporting, entities arrive in bulk legitimately. */
	private static final int GRACE_TICKS = 60;
	private static final double TELEPORT_DIST = 16;

	private static final class Tracked {
		final Set<BlockPos> known = new HashSet<>();
		final Set<BlockPos> bait = new HashSet<>();
		int lastSeen;
	}

	private static final Map<Long, Tracked> CHUNKS = new HashMap<>();
	private static final Set<Integer> KNOWN_ENTITIES = new HashSet<>();
	private static final Set<Integer> BAIT_ENTITIES = new HashSet<>();
	private static ClientLevel level;
	private static Vec3 lastPos;
	private static int ticks;
	private static int graceUntil;

	private AntiBait() { }

	public static void clear() {
		CHUNKS.clear();
		KNOWN_ENTITIES.clear();
		BAIT_ENTITIES.clear();
		level = null;
		lastPos = null;
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

	public static boolean hidesEntity(Entity e) {
		return StarClient.antiBaitOn && BAIT_ENTITIES.contains(e.getId());
	}

	public static void tick(Minecraft mc) {
		ticks++;
		Vec3 pos = mc.player.position();
		if (mc.level != level) {
			clear();
			level = mc.level;
			graceUntil = ticks + GRACE_TICKS;
		} else if (lastPos != null && pos.distanceToSqr(lastPos) > TELEPORT_DIST * TELEPORT_DIST) {
			graceUntil = ticks + GRACE_TICKS;
		}
		lastPos = pos;

		tickBlocks(mc);
		tickEntities(mc);
	}

	private static void tickBlocks(Minecraft mc) {
		ChunkPos center = mc.player.chunkPosition();
		int radius = mc.options.getEffectiveRenderDistance() + 2;
		List<BlockPos> added = new ArrayList<>();

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
						if (t.known.add(e.getKey()) && counts(e.getValue())) added.add(e.getKey());
					}
				}
				t.lastSeen = ticks;
			}
		}
		CHUNKS.values().removeIf(t -> ticks - t.lastSeen > FORGET_TICKS);

		if (added.size() < BLOCK_MIN) return;
		List<Vec3> points = new ArrayList<>();
		for (BlockPos p : added) points.add(Vec3.atCenterOf(p));
		List<Integer> crowded = crowded(points, BLOCK_AREA, BLOCK_MIN);
		for (int i : crowded) {
			BlockPos p = added.get(i);
			CHUNKS.get(ChunkPos.pack(p.getX() >> 4, p.getZ() >> 4)).bait.add(p);
		}
		if (!crowded.isEmpty()) report(mc, crowded.size() + " blocks", points.get(crowded.get(0)));
	}

	private static void tickEntities(Minecraft mc) {
		Set<Integer> now = new HashSet<>();
		List<Entity> added = new ArrayList<>();
		boolean grace = ticks < graceUntil;
		for (Entity e : mc.level.entitiesForRendering()) {
			now.add(e.getId());
			if (KNOWN_ENTITIES.add(e.getId()) && !grace && counts(mc, e)) added.add(e);
		}
		KNOWN_ENTITIES.retainAll(now);
		BAIT_ENTITIES.retainAll(now);

		if (added.size() < ENTITY_MIN) return;
		List<Vec3> points = new ArrayList<>();
		for (Entity e : added) points.add(e.position());
		List<Integer> crowded = crowded(points, ENTITY_AREA, ENTITY_MIN);
		for (int i : crowded) BAIT_ENTITIES.add(added.get(i).getId());
		if (!crowded.isEmpty()) report(mc, crowded.size() + " entities", points.get(crowded.get(0)));
	}

	/** Storage the Chest ESP draws, or a block Base Finder counts. */
	private static boolean counts(BlockEntity be) {
		return ChestEsp.classify(be) != null || BaseFinder.isMarker(be.getBlockState());
	}

	/** Entities ESP draws, except items: a broken chest or a death drops dozens at once. */
	private static boolean counts(Minecraft mc, Entity e) {
		return e != mc.player && e != Freecam.camera() && !(e instanceof ItemEntity)
				&& StarRenderer.categoryOf(e) >= 0 && e.distanceToSqr(mc.player) <= ENTITY_NEAR * ENTITY_NEAR;
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

	private static void report(Minecraft mc, String what, Vec3 at) {
		mc.gui.setOverlayMessage(Component.literal("Anti Bait: hid " + what + " that appeared at once near "
				+ (int) at.x + " " + (int) at.y + " " + (int) at.z).withStyle(ChatFormatting.GOLD), false);
	}
}
