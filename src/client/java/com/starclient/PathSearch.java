package com.starclient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;

/**
 * A* over feet positions, costs in game ticks (the Baritone cost model, much reduced). Moves can break blocks
 * in the way (sand and gravel columns included), open doors and gates, climb ladders, step over slabs, swim (under
 * water when allowed), bridge over gaps and pillar up when {@link PathWorld} allows it. Cells beside lava cost extra. When the goal is out of the
 * loaded area or the search runs out of time, the node closest to the goal is returned as a partial path and
 * the executor plans the next segment from its end.
 */
public final class PathSearch {
	public enum Move { START, WALK, DIAGONAL, SWIM, SWIM_UP, SWIM_DOWN, ASCEND, DESCEND, FALL, DOWN, PILLAR, BRIDGE, CLIMB_UP, CLIMB_DOWN }

	public record Step(BlockPos pos, Move move) { }

	public record Result(List<Step> steps, boolean complete, int nodes) { }

	/** Ticks per block: walking 4.317 b/s, sprinting 5.612 b/s. */
	public static final double WALK = 20 / 4.317;
	public static final double SPRINT = 20 / 5.612;
	static final double JUMP_ESTIMATE = 3;
	static final double DROP_ESTIMATE = 1;
	private static final double JUMP = 2;
	private static final double PLACE = 8;
	private static final double PILLAR = 10;
	private static final double SWIM_FACTOR = 2.5;
	/** Extra factor when the head is under water, so routes along the surface win. */
	private static final double SUBMERGED = 2;
	/** Ladder climbing runs at 0.2 b/t up and 0.15 b/t sliding down. */
	private static final double CLIMB_UP = 6;
	private static final double CLIMB_DOWN = 8;
	/** Extra cost for standing with lava beside your feet or head. */
	private static final double LAVA_PENALTY = 60;
	private static final double HEURISTIC_WEIGHT = 1.25;
	private static final int MAX_NODES = 400_000;
	/** A partial path must get at least this many blocks away from the start. */
	private static final int MIN_PARTIAL = 4;

	/** Ticks to fall n blocks from rest (vanilla gravity 0.08 and drag 0.98). */
	private static final double[] FALL_TICKS = new double[257];

	static {
		double y = 0;
		double v = 0;
		int ticks = 0;
		for (int n = 1; n < FALL_TICKS.length; n++) {
			while (-y < n) {
				y += v;
				v = (v - 0.08) * 0.98;
				ticks++;
			}
			FALL_TICKS[n] = ticks;
		}
	}

	private static final class Node {
		final int x;
		final int y;
		final int z;
		final double h;
		final double penalty;
		double g = Double.MAX_VALUE;
		double f;
		Node parent;
		Move move;
		boolean closed;

		Node(int x, int y, int z, double h, double penalty) {
			this.x = x;
			this.y = y;
			this.z = z;
			this.h = h;
			this.penalty = penalty;
		}
	}

	private final PathWorld w;
	private final PathGoal goal;
	private final double walk;
	private final Map<Long, Node> nodes = new HashMap<>(1 << 16);
	private final PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));

	private PathSearch(PathWorld w, PathGoal goal) {
		this.w = w;
		this.goal = goal;
		this.walk = w.sprint ? SPRINT : WALK;
	}

	/** Runs a search; returns null when cancelled or when no step toward the goal exists. */
	public static Result run(PathWorld w, BlockPos start, PathGoal goal, long timeoutMs, AtomicBoolean cancel) {
		return new PathSearch(w, goal).search(start, timeoutMs, cancel);
	}

	private Result search(BlockPos start, long timeoutMs, AtomicBoolean cancel) {
		long deadline = System.nanoTime() + timeoutMs * 1_000_000L;
		Node first = node(start.getX(), start.getY(), start.getZ());
		first.g = 0;
		first.f = first.h;
		first.move = Move.START;
		open.add(first);
		Node best = first;
		int expanded = 0;
		while (!open.isEmpty()) {
			if ((expanded & 255) == 0 && (cancel.get() || System.nanoTime() > deadline)) break;
			Node n = open.poll();
			if (n.closed) continue;
			n.closed = true;
			expanded++;
			if (goal.isGoal(n.x, n.y, n.z)) return new Result(trace(n), true, expanded);
			if (n.h < best.h && far(n, first)) best = n;
			expand(n);
			if (nodes.size() > MAX_NODES) break;
		}
		if (cancel.get() || best == first) return null;
		return new Result(trace(best), false, expanded);
	}

	private static boolean far(Node n, Node start) {
		int dx = n.x - start.x;
		int dy = n.y - start.y;
		int dz = n.z - start.z;
		return dx * dx + dy * dy + dz * dz >= MIN_PARTIAL * MIN_PARTIAL;
	}

	private Node node(int x, int y, int z) {
		long key = BlockPos.asLong(x, y, z);
		Node n = nodes.get(key);
		if (n == null) {
			n = new Node(x, y, z, goal.heuristic(x, y, z) * HEURISTIC_WEIGHT, w.nearLava(x, y, z) ? LAVA_PENALTY : 0);
			nodes.put(key, n);
		}
		return n;
	}

	private void offer(Node from, int x, int y, int z, Move move, double cost) {
		if (cost >= PathWorld.INF) return;
		Node n = node(x, y, z);
		if (n.closed) return;
		double g = from.g + cost + n.penalty;
		if (g >= n.g - 0.01) return;
		n.g = g;
		n.f = g + n.h;
		n.parent = from;
		n.move = move;
		open.add(n);
	}

	private static List<Step> trace(Node end) {
		List<Step> out = new ArrayList<>();
		for (Node n = end; n != null; n = n.parent) out.add(new Step(new BlockPos(n.x, n.y, n.z), n.move));
		Collections.reverse(out);
		return out;
	}

	private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

	private void expand(Node n) {
		int x = n.x;
		int y = n.y;
		int z = n.z;
		double low = w.lowFloor(x, y, z);
		boolean fullFloor = low < 0 && w.standable(x, y - 1, z);
		// Not on a floor: swimming, or holding on to a ladder.
		boolean afloat = !fullFloor && low < 0;
		for (int[] d : DIRS) {
			int nx = x + d[0];
			int nz = z + d[1];
			if (d[0] != 0 && d[1] != 0) diagonal(n, nx, nz, low);
			else cardinal(n, nx, nz, afloat, low);
		}

		if (fullFloor) {
			// Mine straight down onto the block below that.
			double cost = w.breakCost(x, y - 1, z);
			if (cost > 0 && cost < PathWorld.INF && w.standable(x, y - 2, z)) offer(n, x, y - 1, z, Move.DOWN, cost + FALL_TICKS[1]);
			// Jump and place a block under yourself.
			if (w.canPlace(x, y, z)) {
				double head = w.breakCost(x, y + 2, z);
				if (head < PathWorld.INF) offer(n, x, y + 1, z, Move.PILLAR, PILLAR + PLACE + head);
			}
		}

		// Ladders and vines.
		if (w.climbable(x, y, z) && w.climbable(x, y + 1, z)) {
			double head = w.breakCost(x, y + 2, z);
			if (head < PathWorld.INF) offer(n, x, y + 1, z, Move.CLIMB_UP, CLIMB_UP + head);
		}
		if (w.climbable(x, y - 1, z)) offer(n, x, y - 1, z, Move.CLIMB_DOWN, CLIMB_DOWN);

		// Straight up or down in water.
		if (w.water(x, y, z)) {
			if (w.water(x, y + 1, z) && w.headOpen(x, y + 2, z)) {
				offer(n, x, y + 1, z, Move.SWIM_UP, walk * SWIM_FACTOR * (w.water(x, y + 2, z) ? SUBMERGED : 1));
			}
			if (w.underwater && w.water(x, y - 1, z)) offer(n, x, y - 1, z, Move.SWIM_DOWN, walk * SWIM_FACTOR * SUBMERGED);
		}
	}

	private void diagonal(Node n, int nx, int nz, double srcLow) {
		int x = n.x;
		int y = n.y;
		int z = n.z;
		if (srcLow > 0.2) return;
		// No breaking on diagonals, and both corners must be open so the hitbox does not clip them.
		double low = w.lowFloor(nx, y, nz);
		if (low > 0.2 || (low < 0 && !w.feetSpace(nx, y, nz)) || !w.headOpen(nx, y + 1, nz)) return;
		if (!w.feetSpace(nx, y, z) || !w.headOpen(nx, y + 1, z) || !w.feetSpace(x, y, nz) || !w.headOpen(x, y + 1, nz)) return;
		boolean wet = w.water(nx, y, nz);
		double cost = walk * Math.sqrt(2) * (wet ? SWIM_FACTOR : 1) * (w.water(nx, y + 1, nz) ? SUBMERGED : 1);
		if (low >= 0 || w.standable(nx, y - 1, nz)) offer(n, nx, y, nz, Move.DIAGONAL, cost);
		else if (wet) offer(n, nx, y, nz, Move.SWIM, cost);
	}

	/** {@link PathWorld#doorCost} for door and gate cells, else the break cost of a feet or head cell. */
	private double cell(int x, int y, int z, Direction.Axis axis, boolean feet) {
		double door = w.doorCost(x, y, z, axis);
		if (door >= 0) return door;
		return feet ? w.feetBreakCost(x, y, z) : w.headCost(x, y, z);
	}

	private void cardinal(Node n, int nx, int nz, boolean afloat, double srcLow) {
		int x = n.x;
		int y = n.y;
		int z = n.z;
		Direction.Axis axis = nx != x ? Direction.Axis.X : Direction.Axis.Z;
		// Standing in a doorway: only out along the door's axis.
		if (w.doorCost(x, y, z, axis) >= PathWorld.INF) return;
		// On a slab your head reaches into the third cell up, so that cell must be open wherever you walk.
		boolean tall = srcLow > 0.2;

		// Same level: walk (through doors too), wade, swim, bridge, or walk off the edge and fall.
		double low = w.lowFloor(nx, y, nz);
		double clear = cell(nx, y + 1, nz, axis, false) + (low >= 0 ? 0 : cell(nx, y, nz, axis, true))
				+ (tall || low > 0.2 ? w.breakCost(nx, y + 2, nz) : 0);
		boolean doorway = w.doorCost(nx, y, nz, axis) >= 0 || w.doorCost(nx, y + 1, nz, axis) >= 0;
		if (clear < PathWorld.INF) {
			boolean wet = w.water(nx, y, nz);
			double sub = w.water(nx, y + 1, nz) ? SUBMERGED : 1;
			if (low >= 0 || w.standable(nx, y - 1, nz)) {
				offer(n, nx, y, nz, Move.WALK, (wet ? walk * SWIM_FACTOR : walk) * sub + clear);
			} else if (wet) {
				offer(n, nx, y, nz, Move.SWIM, walk * SWIM_FACTOR * sub + clear);
			} else if (!doorway) {
				if (clear == 0) fall(n, nx, nz);
				if (!afloat && !tall && w.canPlace(nx, y - 1, nz)) offer(n, nx, y, nz, Move.BRIDGE, walk + clear + PLACE);
			}

			// One block down where the cells on the way need breaking (open drops are covered by fall()).
			double below = low >= 0 || doorway ? PathWorld.INF : w.breakCost(nx, y - 1, nz);
			if (below > 0 && below < PathWorld.INF && w.standable(nx, y - 2, nz)) {
				offer(n, nx, y - 1, nz, Move.DESCEND, walk + clear + below + FALL_TICKS[1]);
			}
		}

		// One block up onto (nx, y, nz); from a slab that is a half-block step and needs no jump.
		if (w.standable(nx, y, nz)) {
			double up = w.breakCost(x, y + 2, z) + w.breakCost(nx, y + 2, nz) + w.feetBreakCost(nx, y + 1, nz);
			if (up < PathWorld.INF) offer(n, nx, y + 1, nz, Move.ASCEND, walk + (tall ? 0 : JUMP) + up + (afloat ? JUMP : 0));
		} else if (!tall) {
			// Up onto a carpet or thin snow on top of the next block.
			double lowUp = w.lowFloor(nx, y + 1, nz);
			if (lowUp >= 0 && lowUp <= 0.25) {
				double up = w.breakCost(x, y + 2, z) + w.breakCost(nx, y + 2, nz);
				if (up < PathWorld.INF) offer(n, nx, y + 1, nz, Move.ASCEND, walk + JUMP + up + (afloat ? JUMP : 0));
			}
		}
	}

	/**
	 * Walks off the edge into (nx, nz) and drops to the first floor or slab (up to the fall limit), onto a ladder, or
	 * into water (up to 20 blocks).
	 */
	private void fall(Node n, int nx, int nz) {
		int y = n.y;
		int limit = Math.max(w.maxFall, 20);
		for (int k = 1; k <= limit; k++) {
			int cy = y - k;
			Move move = k == 1 ? Move.DESCEND : Move.FALL;
			if (w.water(nx, cy, nz) || w.climbable(nx, cy, nz)) {
				offer(n, nx, cy, nz, move, walk + FALL_TICKS[k]);
				return;
			}
			if (w.lowFloor(nx, cy, nz) >= 0) {
				if (k - 1 <= w.maxFall) offer(n, nx, cy, nz, move, walk + FALL_TICKS[k]);
				return;
			}
			if (!w.space(nx, cy, nz)) return;
			if (w.standable(nx, cy - 1, nz)) {
				if (k <= w.maxFall) offer(n, nx, cy, nz, move, walk + FALL_TICKS[k]);
				return;
			}
		}
	}
}
