package com.starclient;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraft.core.BlockPos;

/**
 * A* over feet positions, costs in game ticks (the Baritone cost model, much reduced). Moves can break blocks
 * in the way, bridge over gaps and pillar up when {@link PathWorld} allows it. When the goal is out of the
 * loaded area or the search runs out of time, the node closest to the goal is returned as a partial path and
 * the executor plans the next segment from its end.
 */
public final class PathSearch {
	public enum Move { START, WALK, DIAGONAL, SWIM, ASCEND, DESCEND, FALL, DOWN, PILLAR, BRIDGE }

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
		double g = Double.MAX_VALUE;
		double f;
		Node parent;
		Move move;
		boolean closed;

		Node(int x, int y, int z, double h) {
			this.x = x;
			this.y = y;
			this.z = z;
			this.h = h;
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
			n = new Node(x, y, z, goal.heuristic(x, y, z) * HEURISTIC_WEIGHT);
			nodes.put(key, n);
		}
		return n;
	}

	private void offer(Node from, int x, int y, int z, Move move, double cost) {
		if (cost >= PathWorld.INF) return;
		Node n = node(x, y, z);
		if (n.closed) return;
		double g = from.g + cost;
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
		boolean swimming = !w.standable(x, y - 1, z);
		for (int[] d : DIRS) {
			int nx = x + d[0];
			int nz = z + d[1];
			if (d[0] != 0 && d[1] != 0) diagonal(n, nx, nz);
			else cardinal(n, nx, nz, swimming);
		}

		// Mine straight down onto the block below that.
		if (!swimming) {
			double cost = w.breakCost(x, y - 1, z);
			if (cost > 0 && cost < PathWorld.INF && w.standable(x, y - 2, z)) offer(n, x, y - 1, z, Move.DOWN, cost + FALL_TICKS[1]);
		}
		// Jump and place a block under yourself.
		if (!swimming && w.canPlace(x, y, z)) {
			double head = w.breakCost(x, y + 2, z);
			if (head < PathWorld.INF) offer(n, x, y + 1, z, Move.PILLAR, PILLAR + PLACE + head);
		}
	}

	private void diagonal(Node n, int nx, int nz) {
		int x = n.x;
		int y = n.y;
		int z = n.z;
		// No breaking on diagonals, and both corners must be open so the hitbox does not clip them.
		if (!w.feetSpace(nx, y, nz) || !w.space(nx, y + 1, nz)) return;
		if (!w.feetSpace(nx, y, z) || !w.space(nx, y + 1, z) || !w.feetSpace(x, y, nz) || !w.space(x, y + 1, nz)) return;
		double cost = walk * Math.sqrt(2);
		if (w.standable(nx, y - 1, nz)) offer(n, nx, y, nz, Move.DIAGONAL, w.water(nx, y, nz) ? cost * SWIM_FACTOR : cost);
		else if (w.water(nx, y, nz)) offer(n, nx, y, nz, Move.SWIM, cost * SWIM_FACTOR);
	}

	private void cardinal(Node n, int nx, int nz, boolean swimming) {
		int x = n.x;
		int y = n.y;
		int z = n.z;

		// Same level: walk, wade, swim, bridge, or walk off the edge and fall.
		double clear = w.breakCost(nx, y + 1, nz) + w.feetBreakCost(nx, y, nz);
		if (clear < PathWorld.INF) {
			boolean wet = w.water(nx, y, nz);
			if (w.standable(nx, y - 1, nz)) {
				offer(n, nx, y, nz, Move.WALK, (wet ? walk * SWIM_FACTOR : walk) + clear);
			} else if (wet) {
				offer(n, nx, y, nz, Move.SWIM, walk * SWIM_FACTOR + clear);
			} else {
				if (clear == 0) fall(n, nx, nz);
				if (!swimming && w.canPlace(nx, y - 1, nz)) offer(n, nx, y, nz, Move.BRIDGE, walk + clear + PLACE);
			}

			// One block down where the cells on the way need breaking (open drops are covered by fall()).
			double below = w.breakCost(nx, y - 1, nz);
			if (below > 0 && below < PathWorld.INF && w.standable(nx, y - 2, nz)) {
				offer(n, nx, y - 1, nz, Move.DESCEND, walk + clear + below + FALL_TICKS[1]);
			}
		}

		// One block up onto (nx, y, nz).
		if (w.standable(nx, y, nz)) {
			double up = w.breakCost(x, y + 2, z) + w.breakCost(nx, y + 2, nz) + w.feetBreakCost(nx, y + 1, nz);
			if (up < PathWorld.INF) offer(n, nx, y + 1, nz, Move.ASCEND, walk + JUMP + up + (swimming ? JUMP : 0));
		}
	}

	/** Walks off the edge into (nx, nz) and drops to the first floor (up to the fall limit) or into water (up to 20 blocks). */
	private void fall(Node n, int nx, int nz) {
		int y = n.y;
		int limit = Math.max(w.maxFall, 20);
		for (int k = 1; k <= limit; k++) {
			int cy = y - k;
			if (w.water(nx, cy, nz)) {
				offer(n, nx, cy, nz, k == 1 ? Move.DESCEND : Move.FALL, walk + FALL_TICKS[k]);
				return;
			}
			if (!w.space(nx, cy, nz)) return;
			if (w.standable(nx, cy - 1, nz)) {
				if (k <= w.maxFall) offer(n, nx, cy, nz, k == 1 ? Move.DESCEND : Move.FALL, walk + FALL_TICKS[k]);
				return;
			}
		}
	}
}
