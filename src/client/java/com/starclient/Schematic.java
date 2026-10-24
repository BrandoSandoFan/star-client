package com.starclient;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BedPart;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.DoubleBlockHalf;
import net.minecraft.world.level.block.state.properties.Property;

/**
 * A Litematica schematic ({@code .litematic}): every region merged into one box of block states relative to the
 * schematic origin. Cells no region covers are {@code null} ("don't care"); region air is {@code AIR} ("must be
 * empty"). Immutable once loaded, so the pathfinder thread may read it.
 */
public final class Schematic {
	private static final int MAX_VOLUME = 16 * 1024 * 1024;

	/** Properties fixed at placement time; neighbour-driven ones (stair shape, fence sides, power) are not compared. */
	private static final List<Property<?>> PLACED = List.of(BlockStateProperties.FACING, BlockStateProperties.HORIZONTAL_FACING,
			BlockStateProperties.FACING_HOPPER, BlockStateProperties.AXIS, BlockStateProperties.HORIZONTAL_AXIS, BlockStateProperties.HALF,
			BlockStateProperties.SLAB_TYPE, BlockStateProperties.ATTACH_FACE, BlockStateProperties.ROTATION_16, BlockStateProperties.ORIENTATION,
			BlockStateProperties.VERTICAL_DIRECTION, BlockStateProperties.HANGING);

	public final String name;
	public final int minX;
	public final int minY;
	public final int minZ;
	public final int sizeX;
	public final int sizeY;
	public final int sizeZ;
	private final BlockState[] states;
	/** Cells that must hold a block (not air, not "don't care"). */
	public final int blocks;

	private Schematic(String name, int minX, int minY, int minZ, int sizeX, int sizeY, int sizeZ, BlockState[] states) {
		this.name = name;
		this.minX = minX;
		this.minY = minY;
		this.minZ = minZ;
		this.sizeX = sizeX;
		this.sizeY = sizeY;
		this.sizeZ = sizeZ;
		this.states = states;
		int n = 0;
		for (BlockState s : states) if (s != null && !s.isAir()) n++;
		this.blocks = n;
	}

	public int volume() {
		return states.length;
	}

	/** State at dense index {@code i} (see {@link #relPos}), or null for "don't care". */
	public BlockState at(int i) {
		return states[i];
	}

	/** Position relative to the schematic origin of dense index {@code i}. */
	public BlockPos relPos(int i) {
		int x = i % sizeX;
		int z = (i / sizeX) % sizeZ;
		int y = i / (sizeX * sizeZ);
		return new BlockPos(minX + x, minY + y, minZ + z);
	}

	/** State at (x, y, z) relative to the schematic origin, null outside or where no region covers it. */
	public BlockState rel(int x, int y, int z) {
		int lx = x - minX;
		int ly = y - minY;
		int lz = z - minZ;
		if (lx < 0 || ly < 0 || lz < 0 || lx >= sizeX || ly >= sizeY || lz >= sizeZ) return null;
		return states[(ly * sizeZ + lz) * sizeX + lx];
	}

	/** A schematic placed with its origin at (ox, oy, oz). */
	public record Placed(Schematic schematic, int ox, int oy, int oz) {
		public BlockState desired(int x, int y, int z) {
			return schematic.rel(x - ox, y - oy, z - oz);
		}

		public BlockState desired(BlockPos p) {
			return desired(p.getX(), p.getY(), p.getZ());
		}

		public BlockPos pos(int i) {
			return schematic.relPos(i).offset(ox, oy, oz);
		}

		public boolean contains(int x, int y, int z) {
			return desired(x, y, z) != null;
		}

		/** True when (x, y, z) already holds the block the schematic wants there, so the pathfinder must not break it. */
		public boolean keeps(int x, int y, int z, BlockState current) {
			BlockState d = desired(x, y, z);
			return d != null && !d.isAir() && matches(current, d);
		}
	}

	/** Same block, and the same value for every placement-time property the wanted state has. */
	public static boolean matches(BlockState current, BlockState desired) {
		if (current.getBlock() != desired.getBlock()) return false;
		for (Property<?> p : PLACED) {
			if (desired.hasProperty(p) && !current.getValue(p).equals(desired.getValue(p))) return false;
		}
		return true;
	}

	/**
	 * States that appear by themselves or cannot be placed as items: upper door/plant halves, bed heads, piston
	 * heads, fluids, and blocks without an item (fire, portals).
	 */
	public static boolean automatic(BlockState d) {
		if (d.isAir()) return false;
		if (d.getBlock() instanceof LiquidBlock || d.is(Blocks.PISTON_HEAD) || d.is(Blocks.MOVING_PISTON)) return true;
		if (d.hasProperty(BlockStateProperties.DOUBLE_BLOCK_HALF) && d.getValue(BlockStateProperties.DOUBLE_BLOCK_HALF) == DoubleBlockHalf.UPPER) return true;
		if (d.hasProperty(BlockStateProperties.BED_PART) && d.getValue(BlockStateProperties.BED_PART) == BedPart.HEAD) return true;
		return d.getBlock().asItem() == Items.AIR;
	}

	/** Loads a {@code .litematic} file. */
	public static Schematic load(Path file) throws IOException {
		Map<String, Object> root = Nbt.readCompressed(file);
		Map<String, Object> regions = Nbt.map(root, "Regions");
		if (regions.isEmpty()) throw new IOException("No regions in " + file.getFileName());

		int x0 = Integer.MAX_VALUE;
		int y0 = Integer.MAX_VALUE;
		int z0 = Integer.MAX_VALUE;
		int x1 = Integer.MIN_VALUE;
		int y1 = Integer.MIN_VALUE;
		int z1 = Integer.MIN_VALUE;
		for (Object o : regions.values()) {
			int[] box = box(asMap(o));
			x0 = Math.min(x0, box[0]);
			y0 = Math.min(y0, box[1]);
			z0 = Math.min(z0, box[2]);
			x1 = Math.max(x1, box[0] + box[3] - 1);
			y1 = Math.max(y1, box[1] + box[4] - 1);
			z1 = Math.max(z1, box[2] + box[5] - 1);
		}
		int sx = x1 - x0 + 1;
		int sy = y1 - y0 + 1;
		int sz = z1 - z0 + 1;
		if ((long) sx * sy * sz > MAX_VOLUME) throw new IOException("Schematic too large (" + sx + "x" + sy + "x" + sz + ")");
		BlockState[] states = new BlockState[sx * sy * sz];

		for (Object o : regions.values()) {
			Map<String, Object> region = asMap(o);
			int[] box = box(region);
			List<Object> paletteTags = Nbt.list(region, "BlockStatePalette");
			BlockState[] palette = new BlockState[paletteTags.size()];
			for (int i = 0; i < palette.length; i++) palette[i] = state(asMap(paletteTags.get(i)));
			if (!(region.get("BlockStates") instanceof long[] longs) || palette.length == 0) continue;

			// Litematica packs indices with at least 2 bits each, and an index may span two longs.
			int bits = Math.max(2, 32 - Integer.numberOfLeadingZeros(palette.length - 1));
			long mask = (1L << bits) - 1;
			int rx = box[3];
			int ry = box[4];
			int rz = box[5];
			long volume = (long) rx * ry * rz;
			if (volume * bits > (long) longs.length * 64) throw new IOException("Truncated block data in region");
			for (int i = 0; i < volume; i++) {
				long bit = (long) i * bits;
				int start = (int) (bit >> 6);
				int end = (int) ((bit + bits - 1) >> 6);
				int offset = (int) (bit & 63);
				long value = start == end ? longs[start] >>> offset : (longs[start] >>> offset) | (longs[end] << (64 - offset));
				int id = (int) (value & mask);
				if (id >= palette.length) continue;
				int lx = i % rx;
				int lz = (i / rx) % rz;
				int ly = i / (rx * rz);
				int wx = box[0] + lx - x0;
				int wy = box[1] + ly - y0;
				int wz = box[2] + lz - z0;
				states[(wy * sz + wz) * sx + wx] = palette[id];
			}
		}

		String name = Nbt.string(Nbt.map(root, "Metadata"), "Name");
		if (name.isBlank()) name = file.getFileName().toString().replaceFirst("\\.litematic$", "");
		return new Schematic(name, x0, y0, z0, sx, sy, sz, states);
	}

	@SuppressWarnings("unchecked")
	private static Map<String, Object> asMap(Object o) {
		return o instanceof Map ? (Map<String, Object>) o : Map.of();
	}

	/** Region min corner (relative to the schematic origin) and absolute size; Litematica sizes may be negative. */
	private static int[] box(Map<String, Object> region) {
		Map<String, Object> pos = Nbt.map(region, "Position");
		Map<String, Object> size = Nbt.map(region, "Size");
		int[] out = new int[6];
		String[] axes = {"x", "y", "z"};
		for (int a = 0; a < 3; a++) {
			int p = Nbt.integer(pos, axes[a]);
			int s = Nbt.integer(size, axes[a]);
			out[a] = s < 0 ? p + s + 1 : p;
			out[a + 3] = Math.max(1, Math.abs(s));
		}
		return out;
	}

	private static BlockState state(Map<String, Object> tag) {
		Identifier id = Identifier.tryParse(Nbt.string(tag, "Name"));
		if (id == null || !BuiltInRegistries.BLOCK.containsKey(id)) return Blocks.AIR.defaultBlockState();
		Block block = BuiltInRegistries.BLOCK.getValue(id);
		BlockState s = block.defaultBlockState();
		for (Map.Entry<String, Object> e : Nbt.map(tag, "Properties").entrySet()) {
			Property<?> p = block.getStateDefinition().getProperty(e.getKey());
			if (p != null && e.getValue() instanceof String v) s = with(s, p, v);
		}
		return s;
	}

	private static <T extends Comparable<T>> BlockState with(BlockState s, Property<T> p, String value) {
		return p.getValue(value).map(v -> s.setValue(p, v)).orElse(s);
	}
}
