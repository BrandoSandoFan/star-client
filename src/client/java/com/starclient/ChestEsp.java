package com.starclient;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BarrelBlockEntity;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.entity.EnderChestBlockEntity;
import net.minecraft.world.level.block.entity.ShulkerBoxBlockEntity;
import net.minecraft.world.level.block.entity.TrappedChestBlockEntity;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/** Finds storage block entities in nearby loaded chunks. */
public final class ChestEsp {
	public record Hit(BlockPos pos, float r, float g, float b) { }

	private static final int INTERVAL_TICKS = 10;
	private static final int MAX_RADIUS_CHUNKS = 8;

	private static volatile List<Hit> hits = List.of();
	private static int ticks;

	private ChestEsp() { }

	public static List<Hit> hits() {
		return hits;
	}

	public static void clear() {
		hits = List.of();
		ticks = 0;
	}

	public static void tick(Minecraft mc) {
		if (++ticks % INTERVAL_TICKS != 0) return;

		ChunkPos center = mc.player.chunkPosition();
		int radius = Math.min(mc.options.getEffectiveRenderDistance(), MAX_RADIUS_CHUNKS);
		List<Hit> out = new ArrayList<>();

		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				ChunkAccess chunk = mc.level.getChunk(center.x() + dx, center.z() + dz, ChunkStatus.FULL, false);
				if (chunk == null) continue;
				for (BlockEntity be : chunk.getBlockEntities().values()) {
					Hit hit = classify(be);
					if (hit != null) out.add(hit);
				}
			}
		}
		hits = out;
	}

	private static Hit classify(BlockEntity be) {
		BlockPos p = be.getBlockPos();
		if (be instanceof TrappedChestBlockEntity) return new Hit(p, 1.0f, 0.2f, 0.2f);
		if (be instanceof ChestBlockEntity) return new Hit(p, 1.0f, 0.65f, 0.1f);
		if (be instanceof EnderChestBlockEntity) return new Hit(p, 0.6f, 0.2f, 1.0f);
		if (be instanceof BarrelBlockEntity) return new Hit(p, 0.75f, 0.5f, 0.25f);
		if (be instanceof ShulkerBoxBlockEntity) return new Hit(p, 1.0f, 0.4f, 0.9f);
		return null;
	}
}
