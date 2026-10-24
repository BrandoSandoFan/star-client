package com.starclient;

import java.util.List;

import net.minecraft.core.BlockPos;

/** Where the {@link PathSearch} should end. Positions are feet blocks. Heuristics are in ticks, matching the search's move costs. */
public interface PathGoal {
	boolean isGoal(int x, int y, int z);

	double heuristic(int x, int y, int z);

	/** Short text for the status line. */
	String describe();

	/** Octile horizontal distance at walking cost plus a per-block climb/drop cost. */
	static double estimate(int dx, int dy, int dz) {
		int ax = Math.abs(dx);
		int az = Math.abs(dz);
		double flat = Math.max(ax, az) + (Math.sqrt(2) - 1) * Math.min(ax, az);
		double vertical = dy > 0 ? dy * PathSearch.JUMP_ESTIMATE : -dy * PathSearch.DROP_ESTIMATE;
		return flat * PathSearch.WALK + vertical;
	}

	/** Stand on exactly this block. */
	record Block(int x, int y, int z) implements PathGoal {
		public Block(BlockPos p) {
			this(p.getX(), p.getY(), p.getZ());
		}

		@Override
		public boolean isGoal(int px, int py, int pz) {
			return px == x && py == y && pz == z;
		}

		@Override
		public double heuristic(int px, int py, int pz) {
			return estimate(x - px, y - py, z - pz);
		}

		@Override
		public String describe() {
			return x + " " + y + " " + z;
		}
	}

	/** Any height at this column. */
	record XZ(int x, int z) implements PathGoal {
		@Override
		public boolean isGoal(int px, int py, int pz) {
			return px == x && pz == z;
		}

		@Override
		public double heuristic(int px, int py, int pz) {
			return estimate(x - px, 0, z - pz);
		}

		@Override
		public String describe() {
			return x + " " + z;
		}
	}

	/** Within {@code range} blocks horizontally and 1 vertically of a point (Follow, item pickup). */
	record Near(int x, int y, int z, int range) implements PathGoal {
		@Override
		public boolean isGoal(int px, int py, int pz) {
			return Math.abs(px - x) <= range && Math.abs(pz - z) <= range && Math.abs(py - y) <= 1;
		}

		@Override
		public double heuristic(int px, int py, int pz) {
			int dx = Math.max(0, Math.abs(x - px) - range);
			int dz = Math.max(0, Math.abs(z - pz) - range);
			int dy = Math.abs(y - py) <= 1 ? 0 : y - py;
			return estimate(dx, dy, dz);
		}

		@Override
		public String describe() {
			return "near " + x + " " + y + " " + z;
		}
	}

	/**
	 * A feet position from which {@code target} can be broken: beside it at feet or head height, or right under it
	 * with one block of headroom. Standing on it is left out, since breaking it could drop you into a cave.
	 */
	record Reach(BlockPos target) implements PathGoal {
		@Override
		public boolean isGoal(int px, int py, int pz) {
			int dx = px - target.getX();
			int dy = py - target.getY();
			int dz = pz - target.getZ();
			if (Math.abs(dx) + Math.abs(dz) == 1) return dy == 0 || dy == -1;
			return dx == 0 && dz == 0 && dy == -2;
		}

		@Override
		public double heuristic(int px, int py, int pz) {
			int dx = target.getX() - px;
			int dz = target.getZ() - pz;
			int dy = target.getY() - py;
			// one block short in the larger horizontal axis, since standing beside the target is enough
			if (Math.abs(dx) >= Math.abs(dz)) dx -= Integer.signum(dx); else dz -= Integer.signum(dz);
			return estimate(dx, dy > 0 ? Math.max(0, dy - 1) : dy, dz);
		}

		@Override
		public String describe() {
			return target.getX() + " " + target.getY() + " " + target.getZ();
		}
	}

	/** Somewhere {@code target} is within {@code range} of the eyes, without standing in it (Builder). */
	record Within(BlockPos target, double range) implements PathGoal {
		@Override
		public boolean isGoal(int px, int py, int pz) {
			int dx = px - target.getX();
			int dz = pz - target.getZ();
			int dyFeet = target.getY() - py;
			if (dx == 0 && dz == 0 && (dyFeet == 0 || dyFeet == 1)) return false;
			double dy = py + 1.62 - (target.getY() + 0.5);
			return dx * dx + dy * dy + dz * dz <= range * range;
		}

		@Override
		public double heuristic(int px, int py, int pz) {
			return Math.max(0, estimate(target.getX() - px, target.getY() - py, target.getZ() - pz) - range * PathSearch.WALK);
		}

		@Override
		public String describe() {
			return "near " + target.getX() + " " + target.getY() + " " + target.getZ();
		}
	}

	/** Reached when any of the goals is (Mine: next to any of the nearest targets). */
	record Any(List<PathGoal> goals, String label) implements PathGoal {
		@Override
		public boolean isGoal(int x, int y, int z) {
			for (PathGoal g : goals) if (g.isGoal(x, y, z)) return true;
			return false;
		}

		@Override
		public double heuristic(int x, int y, int z) {
			double best = Double.MAX_VALUE;
			for (PathGoal g : goals) best = Math.min(best, g.heuristic(x, y, z));
			return best;
		}

		@Override
		public String describe() {
			return label;
		}
	}
}
