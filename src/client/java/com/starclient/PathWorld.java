package com.starclient;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.BlockTags;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.DoorBlock;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.FenceGateBlock;
import net.minecraft.world.level.block.TrapDoorBlock;
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
	/** Ticks for a block of sand or gravel to fall into the gap before it can be broken again. */
	private static final double FALLING_WAIT = 6;
	/** Taller sand or gravel columns than this are not dug through. */
	private static final int MAX_FALLING = 12;
	private static final double OPEN_COST = 8;
	/** A low floor (slab, carpet, snow layer) is at most this tall, so it is stepped onto without jumping. */
	public static final double LOW_FLOOR = 0.6;

	private final ClientLevel level;
	private final int minY;
	private final int maxY;
	public final boolean allowBreak;
	public final boolean allowPlace;
	public final boolean underwater;
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

	public PathWorld(ClientLevel level, boolean allowBreak, boolean allowPlace, boolean underwater, int maxFall, boolean sprint, List<ItemStack> tools, int protect,
			Set<Long> avoid) {
		this.level = level;
		this.minY = level.getMinY();
		this.maxY = level.getMinY() + level.getHeight();
		this.allowBreak = allowBreak;
		this.allowPlace = allowPlace;
		this.underwater = underwater;
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

	/** Doors and fence gates, which are opened rather than broken. */
	public static boolean isDoor(BlockState s) {
		Block b = s.getBlock();
		return b instanceof DoorBlock || b instanceof FenceGateBlock;
	}

	/** Air-like: no collision (ladders and vines count, you climb them), no fluid, nothing harmful. */
	public boolean space(int x, int y, int z) {
		BlockState s = get(x, y, z);
		return s != null && s.getFluidState().isEmpty() && !harmful(s) && (s.is(BlockTags.CLIMBABLE) || shape(s, x, y, z).isEmpty());
	}

	/** Ladders, vines and the like, without fluid. */
	public boolean climbable(int x, int y, int z) {
		BlockState s = get(x, y, z);
		return s != null && s.is(BlockTags.CLIMBABLE) && s.getFluidState().isEmpty();
	}

	/** Water you can swim or wade in (no collision). */
	public boolean water(int x, int y, int z) {
		BlockState s = get(x, y, z);
		return s != null && s.getFluidState().is(FluidTags.WATER) && (s.is(BlockTags.CLIMBABLE) || shape(s, x, y, z).isEmpty());
	}

	/** A feet cell may be water; a head cell only when swimming underwater is allowed (see {@link #headOpen}). */
	public boolean feetSpace(int x, int y, int z) {
		return space(x, y, z) || water(x, y, z);
	}

	/** Open for the head: air, or water when swimming underwater is allowed. */
	public boolean headOpen(int x, int y, int z) {
		return space(x, y, z) || (underwater && water(x, y, z));
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

	/**
	 * Height of a low floor in (x, y, z) (bottom slab, carpet, snow layers, closed bottom trapdoor), which you stand
	 * on inside that cell; -1 when the cell is not one.
	 */
	public double lowFloor(int x, int y, int z) {
		BlockState s = get(x, y, z);
		if (s == null || harmfulFloor(s) || s.is(BlockTags.CLIMBABLE) || isDoor(s)) return -1;
		VoxelShape shape = shape(s, x, y, z);
		if (shape.isEmpty()) return -1;
		AABB b = shape.bounds();
		if (b.minY > 0.01 || b.maxY > LOW_FLOOR || b.maxX - b.minX < 0.5 || b.maxZ - b.minZ < 0.5) return -1;
		return b.maxY;
	}

	/**
	 * Cost of passing a door or fence gate in (x, y, z) along {@code axis}: 0 when open, the time to open it when
	 * closed and openable by hand, {@link #INF} across its axis or for iron doors; -1 when the cell holds neither.
	 */
	public double doorCost(int x, int y, int z, Direction.Axis axis) {
		BlockState s = get(x, y, z);
		if (s == null) return -1;
		Block b = s.getBlock();
		// A closed door or gate blocks movement along its facing axis; opened, it lets you through along that axis.
		if (b instanceof DoorBlock door) {
			if (s.getValue(DoorBlock.FACING).getAxis() != axis) return INF;
			if (s.getValue(DoorBlock.OPEN)) return 0;
			return door.type().canOpenByHand() ? OPEN_COST : INF;
		}
		if (b instanceof FenceGateBlock) {
			if (s.getValue(FenceGateBlock.OPEN)) return 0;
			return s.getValue(FenceGateBlock.FACING).getAxis() == axis ? OPEN_COST : INF;
		}
		return -1;
	}

	/** True when lava touches the feet or head cell of a player standing at (x, y, z). */
	public boolean nearLava(int x, int y, int z) {
		for (int dy = 0; dy <= 1; dy++) {
			if (lava(x + 1, y + dy, z) || lava(x - 1, y + dy, z) || lava(x, y + dy, z + 1) || lava(x, y + dy, z - 1)) return true;
		}
		return lava(x, y + 2, z);
	}

	private boolean lava(int x, int y, int z) {
		BlockState s = get(x, y, z);
		return s != null && s.getFluidState().is(FluidTags.LAVA);
	}

	/** Ticks to clear (x, y, z): 0 when it is already {@link #space}, {@link #INF} when it must not or cannot be broken. */
	public double breakCost(int x, int y, int z) {
		BlockState s = get(x, y, z);
		if (s == null) return INF;
		if (s.getFluidState().isEmpty() && !harmful(s) && (s.is(BlockTags.CLIMBABLE) || shape(s, x, y, z).isEmpty())) return 0;
		if (!allowBreak || !s.getFluidState().isEmpty() || s.hasBlockEntity() || isDoor(s) || s.getBlock() instanceof TrapDoorBlock
				|| avoid.contains(BlockPos.asLong(x, y, z))) return INF;
		double ticks = breakTicks(s, x, y, z);
		if (ticks >= INF || fluidBeside(x, y, z)) return INF;
		// Sand or gravel above falls into the gap one block at a time; each has to be broken again, and the cells
		// it leaves must not let a fluid in.
		for (int k = 1; ; k++) {
			BlockState above = get(x, y + k, z);
			if (above == null) return INF;
			if (!(above.getBlock() instanceof FallingBlock)) break;
			double t = breakTicks(above, x, y + k, z);
			if (k > MAX_FALLING || t >= INF || fluidBeside(x, y + k, z)) return INF;
			ticks += t + FALLING_WAIT;
		}
		return ticks + BREAK_EXTRA;
	}

	private boolean fluidBeside(int x, int y, int z) {
		for (Direction d : Direction.values()) {
			BlockState n = get(x + d.getStepX(), y + d.getStepY(), z + d.getStepZ());
			if (n == null || !n.getFluidState().isEmpty()) return true;
		}
		return false;
	}

	/** {@link #breakCost}, except that water needs no clearing. */
	public double feetBreakCost(int x, int y, int z) {
		return water(x, y, z) ? 0 : breakCost(x, y, z);
	}

	/** {@link #breakCost} for a head cell: water is free when swimming underwater is allowed. */
	public double headCost(int x, int y, int z) {
		return underwater && water(x, y, z) ? 0 : breakCost(x, y, z);
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

	/** Has a collision shape and no fluid, so a block can be placed against it. */
	public boolean solid(int x, int y, int z) {
		BlockState s = get(x, y, z);
		return s != null && s.getFluidState().isEmpty() && !shape(s, x, y, z).isEmpty();
	}
}
