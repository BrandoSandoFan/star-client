package com.starclient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.VoxelShape;

/**
 * Block rules shared by {@link PathSearch} (on the search thread) and the {@link Pathing} executor (on the client
 * thread), so a path is executed with the same notion of "walkable" it was planned with. One instance per thread:
 * it caches chunks and a mutable position. Unloaded chunks read as {@code null} and are never walkable.
 */
public final class PathWorld {
	public static final double INF = 1e9;
	/** Break estimates above this many ticks are treated as unbreakable. */
	private static final double MAX_BREAK_TICKS = 400;
	private static final double BREAK_EXTRA = 2;

	private final ClientLevel level;
	private final int minY;
	private final int maxY;
	public final boolean allowBreak;
	public final boolean allowPlace;
	public final int maxFall;
	public final boolean sprint;
	/** Hotbar copies, for break-time estimates. */
	private final List<ItemStack> tools;
	private final int protect;
	/** Cells the executor failed to break or reach; never broken again during this task. */
	private final Set<Long> avoid;

	private final BlockPos.MutableBlockPos mpos = new BlockPos.MutableBlockPos();
	private final Map<Long, ChunkAccess> chunks = new HashMap<>();
	private long lastKey = Long.MIN_VALUE;
	private ChunkAccess lastChunk;
	private final Map<BlockState, Double> breakTicks = new HashMap<>();

	public PathWorld(ClientLevel level, boolean allowBreak, boolean allowPlace, int maxFall, boolean sprint, List<ItemStack> tools, int protect, Set<Long> avoid) {
		this.level = level;
		this.minY = level.getMinY();
		this.maxY = level.getMinY() + level.getHeight();
		this.allowBreak = allowBreak;
		this.allowPlace = allowPlace;
		this.maxFall = maxFall;
		this.sprint = sprint;
		this.tools = tools;
		this.protect = protect;
		this.avoid = avoid;
	}

	/** Block at (x, y, z), air above the build limit, {@code null} below it or in an unloaded chunk. */
	public BlockState get(int x, int y, int z) {
		if (y < minY) return null;
		if (y >= maxY) return Blocks.AIR.defaultBlockState();
		int cx = x >> 4;
		int cz = z >> 4;
		long key = ChunkPos.pack(cx, cz);
		ChunkAccess chunk;
		if (key == lastKey) {
			chunk = lastChunk;
		} else {
			chunk = chunks.get(key);
			if (chunk == null && !chunks.containsKey(key)) {
				chunk = level.getChunk(cx, cz, ChunkStatus.FULL, false);
				chunks.put(key, chunk);
			}
			lastKey = key;
			lastChunk = chunk;
		}
		if (chunk == null) return null;
		try {
			return chunk.getBlockState(mpos.set(x, y, z));
		} catch (RuntimeException e) {
			// The client thread may be rewriting this section; treat the block as unknown.
			return null;
		}
	}

	private VoxelShape shape(BlockState s, int x, int y, int z) {
		return s.getCollisionShape(level, mpos.set(x, y, z));
	}

	/** Blocks that hurt or trap you when you stand in them. */
	private static boolean harmful(BlockState s) {
		return s.is(Blocks.FIRE) || s.is(Blocks.SOUL_FIRE) || s.is(Blocks.COBWEB) || s.is(Blocks.SWEET_BERRY_BUSH) || s.is(Blocks.POWDER_SNOW)
				|| s.is(Blocks.WITHER_ROSE) || s.is(Blocks.NETHER_PORTAL) || s.is(Blocks.END_PORTAL) || s.is(Blocks.END_GATEWAY);
	}

	/** Blocks that hurt you when you stand on them. */
	private static boolean harmfulFloor(BlockState s) {
		return s.is(Blocks.MAGMA_BLOCK) || s.is(Blocks.CAMPFIRE) || s.is(Blocks.SOUL_CAMPFIRE) || s.is(Blocks.CACTUS) || s.is(Blocks.POINTED_DRIPSTONE);
	}

	/** Air-like: no collision, no fluid, nothing harmful. */
	public boolean space(int x, int y, int z) {
		BlockState s = get(x, y, z);
		return s != null && s.getFluidState().isEmpty() && !harmful(s) && shape(s, x, y, z).isEmpty();
	}

	/** Water you can swim or wade in (no collision). */
	public boolean water(int x, int y, int z) {
		BlockState s = get(x, y, z);
		return s != null && s.getFluidState().is(FluidTags.WATER) && shape(s, x, y, z).isEmpty();
	}

	/** A feet cell may be water; a head cell may not. */
	public boolean feetSpace(int x, int y, int z) {
		return space(x, y, z) || water(x, y, z);
	}

	/** A top surface at least 0.8 high (paths, farmland, soul sand count) and at least half a block wide, not harmful. */
	public boolean standable(int x, int y, int z) {
		BlockState s = get(x, y, z);
		if (s == null || harmfulFloor(s)) return false;
		VoxelShape shape = shape(s, x, y, z);
		if (shape.isEmpty()) return false;
		AABB b = shape.bounds();
		return b.maxY >= 0.8 && b.maxY <= 1.0 && b.maxX - b.minX >= 0.5 && b.maxZ - b.minZ >= 0.5;
	}

	/** Ticks to clear (x, y, z): 0 when it is already {@link #space}, {@link #INF} when it must not or cannot be broken. */
	public double breakCost(int x, int y, int z) {
		BlockState s = get(x, y, z);
		if (s == null) return INF;
		if (s.getFluidState().isEmpty() && !harmful(s) && shape(s, x, y, z).isEmpty()) return 0;
		if (!allowBreak || !s.getFluidState().isEmpty() || s.hasBlockEntity() || avoid.contains(BlockPos.asLong(x, y, z))) return INF;
		double ticks = breakTicks(s, x, y, z);
		if (ticks >= INF) return INF;
		// Sand or gravel above would fall into the gap, and a fluid next to it would flow in.
		BlockState above = get(x, y + 1, z);
		if (above == null || above.getBlock() instanceof FallingBlock) return INF;
		for (Direction d : Direction.values()) {
			BlockState n = get(x + d.getStepX(), y + d.getStepY(), z + d.getStepZ());
			if (n == null || !n.getFluidState().isEmpty()) return INF;
		}
		return ticks + BREAK_EXTRA;
	}

	/** {@link #breakCost}, except that water needs no clearing. */
	public double feetBreakCost(int x, int y, int z) {
		return water(x, y, z) ? 0 : breakCost(x, y, z);
	}

	/** Estimated ticks to break (x, y, z) with the best hotbar tool, ignoring the safety rules of {@link #breakCost}. */
	public double ticksToBreak(int x, int y, int z) {
		BlockState s = get(x, y, z);
		if (s == null) return 100;
		double t = breakTicks(s, x, y, z);
		return t >= INF ? 600 : t;
	}

	private double breakTicks(BlockState s, int x, int y, int z) {
		Double cached = breakTicks.get(s);
		if (cached != null) return cached;
		double hardness = s.getDestroySpeed(level, mpos.set(x, y, z));
		double ticks;
		if (hardness < 0) {
			ticks = INF;
		} else if (hardness == 0) {
			ticks = 1;
		} else {
			double best = 0;
			for (ItemStack st : tools) best = Math.max(best, progress(st, s, hardness));
			best = Math.max(best, progress(ItemStack.EMPTY, s, hardness));
			ticks = Math.ceil(1.0 / best);
			if (ticks > MAX_BREAK_TICKS) ticks = INF;
		}
		breakTicks.put(s, ticks);
		return ticks;
	}

	/** Vanilla per-tick break progress for {@code stack} (efficiency and haste ignored). */
	private double progress(ItemStack stack, BlockState s, double hardness) {
		if (!stack.isEmpty() && stack.isDamageableItem() && stack.getMaxDamage() - stack.getDamageValue() <= protect) return 0;
		float speed = stack.isEmpty() ? 1.0f : stack.getDestroySpeed(s);
		boolean harvest = !s.requiresCorrectToolForDrops() || (!stack.isEmpty() && stack.isCorrectToolForDrops(s));
		return speed / hardness / (harvest ? 30.0 : 100.0);
	}

	/** True when a block can be put at (x, y, z): the cell is air, water or a plant and a neighbour has a face to click. */
	public boolean canPlace(int x, int y, int z) {
		if (!allowPlace) return false;
		BlockState s = get(x, y, z);
		if (s == null || !s.canBeReplaced() || s.getFluidState().is(FluidTags.LAVA)) return false;
		return support(x, y, z, null) != null;
	}

	/** A neighbour of (x, y, z) to place against, preferring {@code prefer}; never the cell above (that is where you stand). */
	public Direction support(int x, int y, int z, Direction prefer) {
		if (prefer != null && solid(x + prefer.getStepX(), y + prefer.getStepY(), z + prefer.getStepZ())) return prefer;
		for (Direction d : Direction.values()) {
			if (d == Direction.UP) continue;
			if (solid(x + d.getStepX(), y + d.getStepY(), z + d.getStepZ())) return d;
		}
		return null;
	}

	private boolean solid(int x, int y, int z) {
		BlockState s = get(x, y, z);
		return s != null && s.getFluidState().isEmpty() && !shape(s, x, y, z).isEmpty();
	}
}
