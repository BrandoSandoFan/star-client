package com.starclient;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.BlockTags;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * X-ray: {@code BlockMixin} drops every face of stone-type blocks (or of everything but ores with
 * {@code xray.hideall}) and keeps all faces of ores, so ores show through the rock. Ore boxes are an optional
 * extra, found by scanning chunk sections like {@link PortalFinder}.
 */
public final class Xray {
	public record Hit(BlockPos pos, float r, float g, float b) { }

	private static final String[] REBUILD_NAMES = {"allChanged", "needsUpdate", "reload"};

	private static final int INTERVAL_TICKS = 20;
	private static final int CHUNKS_PER_PASS = 6;
	private static final int RESCAN_TICKS = 20 * 10;

	// Read from the chunk meshing threads, so concurrent maps.
	private static final Map<Block, Integer> ORE = new ConcurrentHashMap<>();
	private static final Map<Block, Boolean> STONE = new ConcurrentHashMap<>();

	private static final Map<Long, List<Hit>> HITS = new HashMap<>();
	private static final Map<Long, Integer> SCANNED = new HashMap<>();
	private static int ticks;
	private static int cursor;
	private static List<Hit> cached;
	private static boolean hideAllApplied;

	private Xray() { }

	/** For {@code BlockMixin}: true to always draw the face, false to never draw it, null to leave vanilla culling. */
	public static Boolean faceOverride(BlockState state) {
		if (!StarClient.xrayOn) return null;
		if (oreColour(state) != 0) return Boolean.TRUE;
		if (Settings.on("xray.hideall") || isStone(state)) return Boolean.FALSE;
		return null;
	}

	/** Rebuilds every chunk mesh so the new culling takes effect. */
	public static void reload(Minecraft mc) {
		hideAllApplied = Settings.on("xray.hideall");
		if (mc.level == null) return;
		// The "rebuild every chunk" method was renamed across versions and cannot be checked at build time here.
		for (String name : REBUILD_NAMES) {
			try {
				mc.levelRenderer.getClass().getMethod(name).invoke(mc.levelRenderer);
				return;
			} catch (ReflectiveOperationException | RuntimeException e) {
				// Try the next name.
			}
		}
		StarClient.LOGGER.warn("X-Ray: no chunk rebuild method found; chunks update as they reload");
	}

	public static void clear() {
		cached = null;
		HITS.clear();
		SCANNED.clear();
		ticks = 0;
		cursor = 0;
	}

	public static List<Hit> hits() {
		if (cached == null) {
			List<Hit> out = new ArrayList<>();
			for (List<Hit> chunk : HITS.values()) out.addAll(chunk);
			cached = out;
		}
		return cached;
	}

	public static void tick(Minecraft mc) {
		if (Settings.on("xray.hideall") != hideAllApplied) reload(mc);
		if (!Settings.on("xray.highlight") || ++ticks % INTERVAL_TICKS != 0) return;

		ChunkPos center = mc.player.chunkPosition();
		int radius = Math.min(mc.options.getEffectiveRenderDistance(), Settings.getI("xray.radius"));
		cached = null;
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
		LevelChunkSection[] sections = chunk.getSections();
		for (int i = 0; i < sections.length; i++) {
			LevelChunkSection section = sections[i];
			if (section.hasOnlyAir() || !section.maybeHas(s -> oreColour(s) != 0)) continue;

			int baseY = (chunk.getMinSectionY() + i) << 4;
			for (int y = 0; y < 16; y++) {
				for (int z = 0; z < 16; z++) {
					for (int x = 0; x < 16; x++) {
						int rgb = oreColour(section.getBlockState(x, y, z));
						if (rgb == 0) continue;
						found.add(new Hit(new BlockPos((cx << 4) + x, baseY + y, (cz << 4) + z),
								((rgb >> 16) & 0xFF) / 255f, ((rgb >> 8) & 0xFF) / 255f, (rgb & 0xFF) / 255f));
					}
				}
			}
		}
		return found;
	}

	/** True for stone, deepslate, granite, diorite, andesite, tuff, netherrack, basalt and blackstone. */
	private static boolean isStone(BlockState state) {
		return STONE.computeIfAbsent(state.getBlock(), b -> state.is(BlockTags.BASE_STONE_OVERWORLD) || state.is(BlockTags.BASE_STONE_NETHER));
	}

	/** RGB for an ore block, or 0 when the block is not an ore. */
	public static int oreColour(BlockState state) {
		return ORE.computeIfAbsent(state.getBlock(), Xray::classify);
	}

	private static int classify(Block block) {
		if (block == Blocks.ANCIENT_DEBRIS) return 0x8B5A3C;
		String path = BuiltInRegistries.BLOCK.getKey(block).getPath();
		if (!path.endsWith("_ore")) return 0;
		if (path.contains("diamond")) return 0x33E6FF;
		if (path.contains("emerald")) return 0x17DD62;
		if (path.contains("lapis")) return 0x2D5BE0;
		if (path.contains("redstone")) return 0xFF2626;
		if (path.contains("gold")) return 0xFFD700;
		if (path.contains("copper")) return 0xE07B39;
		if (path.contains("iron")) return 0xD8AF93;
		if (path.contains("coal")) return 0x555555;
		return 0xFFFFFF;
	}
}
