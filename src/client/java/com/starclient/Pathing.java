package com.starclient;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraft.ChatFormatting;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.item.FallingBlockEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.FallingBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;

/**
 * Pathfinder: a small Baritone. Chat commands ({@code #goto}, {@code #mine}, {@code #follow}, {@code #thisway},
 * {@code #stop}) set a goal; {@link PathSearch} plans on a background thread and this class walks the path with
 * the movement keys, breaking and placing blocks where the plan says so. Turns go through {@link ProtocolSanitizer}.
 */
public final class Pathing {
	public enum Mode { IDLE, GOTO, MINE, FOLLOW }

	/** A block highlighted by {@link StarRenderer}. */
	public record Mark(BlockPos pos, int rgb) { }

	private static final Identifier HUD_ID = Identifier.fromNamespaceAndPath(StarClient.ID, "pathfinder");
	private static final ExecutorService THREAD = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "Star Client pathfinder");
		t.setDaemon(true);
		return t;
	});

	/** Start planning the next segment of a partial path this many steps before its end. */
	private static final int LOOKAHEAD = 10;
	private static final int STEP_TIMEOUT = 100;
	private static final int MAX_REPLANS = 6;
	private static final int MAX_TARGETS = 64;
	private static final int GOAL_TARGETS = 12;
	private static final float WALK_PITCH = 15.0f;
	private static final String PREFIX = "#";

	private static Mode mode = Mode.IDLE;
	private static PathGoal goal;
	private static String state = "";
	private static ClientLevel taskLevel;

	private static List<PathSearch.Step> path;
	private static int index;
	private static boolean partial;

	private static Future<PathSearch.Result> pending;
	private static AtomicBoolean cancel;
	private static boolean pendingAppend;

	private static boolean controlling;
	private static int stepTicks;
	private static int replans;
	private static int bridgeTicks;
	/** Something was broken during the current step, so sand or gravel may still be falling into it. */
	private static boolean dugThisStep;
	private static int fallWait;
	/** Ticks until the next door click, so a door is not toggled shut again before the client sees it open. */
	private static int useCooldown;
	/** Out of breath under water: rising until the air bar is full again. */
	private static boolean surfacing;
	private static boolean inLava;
	/** Ticks spent trying to block lava; gives up after a while if no face to place against is in view. */
	private static int plugTicks;
	private static BlockPos breaking;
	private static int breakTicks;
	private static int breakLimit;
	/** Cells that could not be broken; the planner leaves them alone for the rest of the task. */
	private static final Set<Long> avoid = new HashSet<>();

	private static Set<Block> mineBlocks = Set.of();
	private static String mineLabel = "";
	private static int mineLimit;
	private static int mined;
	private static List<BlockPos> targets = new ArrayList<>();
	private static List<BlockPos> goalTargets = List.of();
	private static final Set<Long> failed = new HashSet<>();
	private static int scanWait;
	private static BlockPos mineTarget;
	private static BlockPos collectFrom;
	private static int collectTicks;
	private static ItemEntity collectItem;
	private static final Set<Integer> ignoredItems = new HashSet<>();

	private static String followName;
	private static int followWait;

	private static String throwawayText;
	private static Set<Block> throwaway = Set.of();

	private Pathing() { }

	public static void init() {
		ClientSendMessageEvents.ALLOW_CHAT.register(message -> !command(message));
		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, HUD_ID, Pathing::renderHud);
	}

	// ---- commands ----

	/** Handles a chat line; true when it was a pathfinder command, which is then not sent to the server. */
	public static boolean command(String message) {
		String prefix = PREFIX;
		Minecraft mc = Minecraft.getInstance();
		if (!message.startsWith(prefix) || mc.player == null || mc.level == null) return false;
		String[] a = message.substring(prefix.length()).trim().split("\\s+");
		String cmd = a[0].toLowerCase(Locale.ROOT);
		String[] args = java.util.Arrays.copyOfRange(a, 1, a.length);
		try {
			switch (cmd) {
				case "goto" -> commandGoto(mc, args);
				case "mine" -> commandMine(mc, args);
				case "follow" -> {
					if (args.length != 1) throw new IllegalArgumentException(prefix + "follow <player>");
					begin(mc, Mode.FOLLOW, null);
					followName = args[0];
					say(mc, "Following " + followName, ChatFormatting.GREEN);
				}
				case "thisway" -> {
					if (args.length != 1) throw new IllegalArgumentException(prefix + "thisway <blocks>");
					int n = Integer.parseInt(args[0]);
					double yaw = Math.toRadians(mc.player.getYRot());
					int x = Mth.floor(mc.player.getX() - Math.sin(yaw) * n);
					int z = Mth.floor(mc.player.getZ() + Math.cos(yaw) * n);
					begin(mc, Mode.GOTO, new PathGoal.XZ(x, z));
					say(mc, "Going to " + x + " " + z, ChatFormatting.GREEN);
				}
				case "stop", "cancel" -> stop(mc, "Stopped");
				case "", "help" -> help(mc);
				default -> say(mc, "Unknown command '" + cmd + "'. " + prefix + "help lists them", ChatFormatting.RED);
			}
		} catch (NumberFormatException e) {
			say(mc, "Not a number: " + e.getMessage(), ChatFormatting.RED);
		} catch (IllegalArgumentException e) {
			say(mc, e.getMessage(), ChatFormatting.RED);
		}
		return true;
	}

	private static void help(Minecraft mc) {
		String p = PREFIX;
		say(mc, "Commands:", ChatFormatting.AQUA);
		say(mc, p + "goto <x> <y> <z>  |  " + p + "goto <x> <z>  |  " + p + "goto <waypoint>  (~ for relative)", ChatFormatting.WHITE);
		say(mc, p + "mine [count] <blocks>  e.g. " + p + "mine diamond, " + p + "mine 32 iron coal", ChatFormatting.WHITE);
		say(mc, p + "follow <player>  |  " + p + "thisway <blocks>  |  " + p + "stop", ChatFormatting.WHITE);
	}

	private static int coord(String s, int base) {
		if (s.startsWith("~")) return base + (s.length() > 1 ? Integer.parseInt(s.substring(1)) : 0);
		return Mth.floor(Double.parseDouble(s));
	}

	private static void commandGoto(Minecraft mc, String[] args) {
		BlockPos feet = feet(mc.player);
		PathGoal g;
		if (args.length == 3 && isCoord(args[0])) {
			g = new PathGoal.Block(coord(args[0], feet.getX()), coord(args[1], feet.getY()), coord(args[2], feet.getZ()));
		} else if (args.length == 2 && isCoord(args[0])) {
			g = new PathGoal.XZ(coord(args[0], feet.getX()), coord(args[1], feet.getZ()));
		} else if (args.length > 0) {
			String name = String.join(" ", args);
			String dim = Waypoints.dimension(mc);
			Config.Waypoint found = null;
			for (Config.Waypoint w : Config.get().waypoints) {
				if (w.dim.equals(dim) && w.name.equalsIgnoreCase(name)) found = w;
			}
			if (found == null) throw new IllegalArgumentException("No waypoint called '" + name + "' in this dimension");
			g = new PathGoal.Block(found.x, found.y, found.z);
		} else {
			throw new IllegalArgumentException(PREFIX + "goto <x> <y> <z>, <x> <z> or <waypoint>");
		}
		begin(mc, Mode.GOTO, g);
		say(mc, "Going to " + g.describe(), ChatFormatting.GREEN);
	}

	private static boolean isCoord(String s) {
		return s.startsWith("~") || s.matches("-?\\d+(\\.\\d+)?");
	}

	private static void commandMine(Minecraft mc, String[] args) {
		int limit = 0;
		int from = 0;
		if (args.length > 0 && args[0].matches("\\d+")) {
			limit = Integer.parseInt(args[0]);
			from = 1;
		}
		String[] names = java.util.Arrays.copyOfRange(args, from, args.length);
		if (names.length == 0) names = Settings.text("pathfinder.mine").trim().split("\\s+");
		startMine(mc, names, limit);
	}

	/** Starts mining; false (with a chat message) when no block name could be resolved. */
	private static boolean startMine(Minecraft mc, String[] names, int limit) {
		Set<Block> blocks = new LinkedHashSet<>();
		List<String> unknown = new ArrayList<>();
		for (String n : names) {
			if (n.isBlank()) continue;
			if (!resolve(n.toLowerCase(Locale.ROOT), blocks)) unknown.add(n);
		}
		if (!unknown.isEmpty() || blocks.isEmpty()) {
			say(mc, blocks.isEmpty() && unknown.isEmpty() ? "Name the blocks to mine, e.g. " + PREFIX + "mine diamond" : "Unknown block: " + String.join(", ", unknown),
					ChatFormatting.RED);
			return false;
		}
		begin(mc, Mode.MINE, null);
		mineBlocks = Set.copyOf(blocks);
		mineLimit = limit;
		List<String> ids = new ArrayList<>();
		for (Block b : blocks) ids.add(BuiltInRegistries.BLOCK.getKey(b).getPath());
		mineLabel = String.join(", ", ids);
		say(mc, "Mining " + (limit > 0 ? limit + " " : "") + mineLabel, ChatFormatting.GREEN);
		return true;
	}

	/** Adds the block named {@code name}; a bare ore name ("diamond") adds its stone, deepslate and nether ores. */
	private static boolean resolve(String name, Set<Block> out) {
		boolean namespaced = name.contains(":");
		Block exact = block(namespaced ? name : "minecraft:" + name);
		if (exact != null) {
			out.add(exact);
			return true;
		}
		if (namespaced) return false;
		boolean any = false;
		for (String v : new String[] {name + "_ore", "deepslate_" + name + "_ore", "nether_" + name + "_ore"}) {
			Block b = block("minecraft:" + v);
			if (b != null) {
				out.add(b);
				any = true;
			}
		}
		return any;
	}

	private static Block block(String id) {
		Identifier key = Identifier.tryParse(id);
		if (key == null || !BuiltInRegistries.BLOCK.containsKey(key)) return null;
		Block b = BuiltInRegistries.BLOCK.getValue(key);
		return b.defaultBlockState().isAir() ? null : b;
	}

	private static void say(Minecraft mc, String text, ChatFormatting colour) {
		if (mc.player == null) return;
		mc.player.sendSystemMessage(Component.literal("[Pathfinder] ").withStyle(ChatFormatting.AQUA).append(Component.literal(text).withStyle(colour)));
	}

	// ---- task lifecycle ----

	private static void begin(Minecraft mc, Mode m, PathGoal g) {
		clearTask(mc);
		mode = m;
		goal = g;
		taskLevel = mc.level;
		StarClient.pathfinderOn = true;
	}

	/** Ends the task and switches the module off. */
	public static void stop(Minecraft mc, String reason) {
		boolean was = StarClient.pathfinderOn;
		clearTask(mc);
		mode = Mode.IDLE;
		StarClient.pathfinderOn = false;
		if (was && reason != null) say(mc, reason, ChatFormatting.YELLOW);
	}

	/** Key/menu toggle: stops a running task, or starts mining the blocks in the module settings. */
	public static void toggle(Minecraft mc) {
		if (StarClient.pathfinderOn) {
			stop(mc, "Stopped");
		} else if (mc.player != null) {
			startMine(mc, Settings.text("pathfinder.mine").trim().split("\\s+"), 0);
		}
	}

	private static void clearTask(Minecraft mc) {
		cancelSearch();
		release(mc);
		path = null;
		index = 0;
		partial = false;
		goal = null;
		state = "";
		stepTicks = 0;
		replans = 0;
		bridgeTicks = 0;
		dugThisStep = false;
		fallWait = 0;
		useCooldown = 0;
		surfacing = false;
		inLava = false;
		plugTicks = 0;
		breaking = null;
		avoid.clear();
		mineBlocks = Set.of();
		mineLimit = 0;
		mined = 0;
		targets = new ArrayList<>();
		goalTargets = List.of();
		failed.clear();
		scanWait = 0;
		mineTarget = null;
		collectFrom = null;
		collectItem = null;
		ignoredItems.clear();
		followName = null;
		followWait = 0;
	}

	/** Called while disconnected. */
	public static void reset() {
		cancelSearch();
		controlling = false;
		breaking = null;
		path = null;
		mode = Mode.IDLE;
		StarClient.pathfinderOn = false;
	}

	private static void cancelSearch() {
		if (cancel != null) cancel.set(true);
		cancel = null;
		pending = null;
	}

	// ---- tick ----

	public static void tick(Minecraft mc) {
		if (!StarClient.pathfinderOn) return;
		LocalPlayer p = mc.player;
		if (mode == Mode.IDLE) {
			StarClient.pathfinderOn = false;
			return;
		}
		if (mc.level != taskLevel) {
			stop(mc, "World changed, stopped");
			return;
		}
		if (p.isDeadOrDying()) {
			stop(mc, "You died, stopped");
			return;
		}
		if (p.isPassenger() || p.getAbilities().flying) {
			stop(mc, "Riding or flying, stopped");
			return;
		}
		if (mc.gui.screen() != null || Freecam.isOn() || Utility.isEating() || mc.gameMode == null || p.isFallFlying()) {
			release(mc);
			state = "paused";
			return;
		}
		pollSearch(mc);
		if (useCooldown > 0) useCooldown--;
		if (emergency(mc)) return;
		switch (mode) {
			case GOTO -> {
				if (goal.isGoal(feet(p).getX(), feet(p).getY(), feet(p).getZ()) && settled(p)) {
					stop(mc, "Arrived at " + goal.describe());
					return;
				}
				drive(mc);
			}
			case MINE -> mineTick(mc);
			case FOLLOW -> followTick(mc);
			default -> { }
		}
	}

	private static boolean settled(LocalPlayer p) {
		return p.onGround() || p.isInWater() || p.onClimbable();
	}

	/** The block the player stands in; lifted slightly so standing on soul sand or a path block counts as above it. */
	public static BlockPos feet(LocalPlayer p) {
		return BlockPos.containing(p.getX(), p.getY() + 0.2, p.getZ());
	}

	private static PathWorld world(Minecraft mc) {
		var inv = mc.player.getInventory();
		List<ItemStack> tools = new ArrayList<>(9);
		for (int i = 0; i < 9; i++) tools.add(inv.getItem(i).copy());
		boolean place = Settings.on("pathfinder.place") && throwawaySlot(mc.player) >= 0;
		return new PathWorld(mc.level, Settings.on("pathfinder.break"), place, Settings.on("pathfinder.underwater"), Settings.getI("pathfinder.fall"),
				Settings.on("pathfinder.sprint"), tools, Settings.getI("autotool.protect"), Set.copyOf(avoid));
	}

	// ---- planning ----

	private static void startSearch(Minecraft mc, BlockPos from, PathGoal g, boolean append) {
		cancelSearch();
		PathWorld w = world(mc);
		AtomicBoolean c = new AtomicBoolean();
		long timeout = Math.round(Settings.get("pathfinder.timeout") * 1000);
		cancel = c;
		pendingAppend = append;
		pending = THREAD.submit(() -> PathSearch.run(w, from, g, timeout, c));
	}

	/** Plans from where the player stands toward the current goal. */
	private static void plan(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (!settled(p)) {
			state = "waiting to land";
			return;
		}
		PathGoal g = currentGoal(mc);
		if (g == null) return;
		BlockPos feet = feet(p);
		if (g.isGoal(feet.getX(), feet.getY(), feet.getZ())) return;
		state = "calculating";
		startSearch(mc, feet, g, false);
	}

	private static PathGoal currentGoal(Minecraft mc) {
		if (mode != Mode.MINE) return goal;
		if (collectItem != null) return goal;
		List<BlockPos> near = new ArrayList<>();
		List<PathGoal> goals = new ArrayList<>();
		for (BlockPos t : targets) {
			if (near.size() >= GOAL_TARGETS) break;
			near.add(t);
			goals.add(new PathGoal.Reach(t));
		}
		if (goals.isEmpty()) return null;
		goalTargets = near;
		goal = new PathGoal.Any(goals, mineLabel);
		return goal;
	}

	private static void pollSearch(Minecraft mc) {
		if (pending == null || !pending.isDone()) return;
		PathSearch.Result r;
		try {
			r = pending.get();
		} catch (Exception e) {
			StarClient.LOGGER.warn("Pathfinder search failed", e);
			r = null;
		}
		boolean append = pendingAppend;
		pending = null;
		cancel = null;

		if (r == null) {
			if (append) partial = false;
			else noPath(mc);
			return;
		}
		BlockPos start = r.steps().get(0).pos();
		if (append && path != null) {
			if (path.get(path.size() - 1).pos().equals(start)) {
				List<PathSearch.Step> joined = new ArrayList<>(path);
				joined.addAll(r.steps().subList(1, r.steps().size()));
				path = joined;
				partial = !r.complete();
			}
			return;
		}
		if (!start.equals(feet(mc.player))) return;
		path = r.steps();
		index = 0;
		partial = !r.complete();
		stepTicks = 0;
		state = "walking";
	}

	private static void noPath(Minecraft mc) {
		switch (mode) {
			case GOTO -> stop(mc, "No path to " + goal.describe());
			case FOLLOW -> {
				state = "no path";
				followWait = 40;
			}
			case MINE -> {
				if (collectItem != null) {
					ignoredItems.add(collectItem.getId());
					collectItem = null;
				} else {
					for (BlockPos t : goalTargets) failed.add(t.asLong());
					targets.removeAll(goalTargets);
					state = "no path to the nearest targets";
				}
			}
			default -> { }
		}
	}

	private static void replan(Minecraft mc, String why) {
		cancelSearch();
		release(mc);
		path = null;
		state = why;
		if (++replans > MAX_REPLANS) {
			replans = 0;
			switch (mode) {
				case GOTO -> stop(mc, "Stuck (" + why + "), stopped");
				case MINE -> {
					if (collectItem != null) {
						ignoredItems.add(collectItem.getId());
						collectItem = null;
					} else {
						for (BlockPos t : goalTargets) failed.add(t.asLong());
						targets.removeAll(goalTargets);
					}
				}
				default -> { }
			}
		}
	}

	// ---- path driving ----

	/** Follows the current path, planning a new one when there is none. */
	private static void drive(Minecraft mc) {
		if (path == null) {
			release(mc);
			if (pending == null) plan(mc);
			return;
		}
		if (index >= path.size() - 1) {
			release(mc);
			path = null;
			if (pending == null) plan(mc);
			return;
		}
		if (partial && pending == null && path.size() - 1 - index <= LOOKAHEAD) {
			startSearch(mc, path.get(path.size() - 1).pos(), goal, true);
		}
		execute(mc, world(mc));
	}

	private static boolean at(LocalPlayer p, BlockPos feet, BlockPos pos) {
		if (feet.equals(pos)) return true;
		// bobbing at the surface moves the feet between the water cell and the air above it
		return p.isInWater() && !p.isUnderWater() && feet.getX() == pos.getX() && feet.getZ() == pos.getZ() && Math.abs(feet.getY() - pos.getY()) <= 1;
	}

	private static void advance(int k) {
		index = k;
		stepTicks = 0;
		replans = 0;
		bridgeTicks = 0;
		dugThisStep = false;
		fallWait = 0;
	}

	private static void execute(Minecraft mc, PathWorld w) {
		LocalPlayer p = mc.player;
		BlockPos feet = feet(p);
		int last = path.size() - 1;
		if (settled(p)) {
			for (int k = Math.min(last, index + 4); k > index; k--) {
				if (at(p, feet, path.get(k).pos())) {
					advance(k);
					break;
				}
			}
		}
		if (index >= last) {
			release(mc);
			return;
		}
		PathSearch.Step from = path.get(index);
		PathSearch.Step to = path.get(index + 1);

		// Knocked off the path or fell somewhere unplanned.
		double off = segmentDistance(p.getX(), p.getZ(), from.pos(), to.pos());
		int lo = Math.min(from.pos().getY(), to.pos().getY());
		int hi = Math.max(from.pos().getY(), to.pos().getY());
		if (off > 2.0 || (settled(p) && (feet.getY() < lo - 1 || feet.getY() > hi + 1))) {
			replan(mc, "off path");
			return;
		}

		BlockPos dest = to.pos();
		Direction.Axis axis = dest.getX() != from.pos().getX() ? Direction.Axis.X : Direction.Axis.Z;
		List<BlockPos> cells = clearCells(w, from.pos(), to);
		for (BlockPos c : cells) {
			if (to.move() == PathSearch.Move.WALK) {
				double door = w.doorCost(c.getX(), c.getY(), c.getZ(), axis);
				if (door == 0) continue;
				if (door > 0) {
					if (door >= PathWorld.INF) {
						replan(mc, "door in the way");
					} else {
						state = "opening a door";
						openDoor(mc, c);
					}
					return;
				}
			}
			if (w.feetSpace(c.getX(), c.getY(), c.getZ())) continue;
			if (w.breakCost(c.getX(), c.getY(), c.getZ()) >= PathWorld.INF) {
				replan(mc, "path blocked");
				return;
			}
			state = "breaking";
			dugThisStep = true;
			if (!dig(mc, w, c)) {
				avoid.add(c.asLong());
				replan(mc, "could not break a block");
			}
			return;
		}
		if (breaking != null) {
			if (mc.gameMode.isDestroying()) mc.gameMode.stopDestroyBlock();
			breaking = null;
		}
		// Sand or gravel over a cell just dug out falls in a moment later; never walk in under it.
		if (dugThisStep && fallingAbove(mc, cells) && ++fallWait < 60) {
			keys(mc, false, false, false, false, false, false, false);
			state = "waiting for sand or gravel";
			return;
		}

		if (to.move() == PathSearch.Move.BRIDGE && !w.standable(dest.getX(), dest.getY() - 1, dest.getZ())) {
			state = "bridging";
			bridge(mc, w, from.pos(), dest);
			return;
		}

		state = "walking";
		if (++stepTicks > STEP_TIMEOUT) {
			replan(mc, "stuck");
			return;
		}
		switch (to.move()) {
			case PILLAR -> {
				state = "pillaring";
				pillar(mc, w, from.pos());
			}
			case DOWN -> {
				Vec3 c = Vec3.atBottomCenterOf(from.pos());
				if (flatDistance(p, c) > 0.2) steer(mc, c, false, false, false, 90.0f);
				else keys(mc, false, false, false, false, false, false, false);
			}
			case CLIMB_UP, CLIMB_DOWN -> {
				// Pushing into the ladder climbs it, so only steer when clearly off centre; jump climbs, nothing slides down.
				Vec3 c = Vec3.atBottomCenterOf(dest);
				boolean up = to.move() == PathSearch.Move.CLIMB_UP;
				if (flatDistance(p, c) > 0.3) steer(mc, c, false, up, false, WALK_PITCH);
				else keys(mc, false, false, false, false, up, false, false);
			}
			default -> {
				Vec3 c = Vec3.atBottomCenterOf(dest);
				PathSearch.Move m = to.move();
				boolean straight = m == PathSearch.Move.WALK || m == PathSearch.Move.DIAGONAL;
				boolean sprint = straight && Settings.on("pathfinder.sprint") && index + 1 < last && p.getFoodData().getFoodLevel() > 6 && !p.isInWater();
				boolean jump = m == PathSearch.Move.ASCEND && (p.onGround() || p.onClimbable());
				boolean sneak = false;
				if (p.isInWater()) {
					// Rise for SWIM_UP and climbing out, sink with sneak for SWIM_DOWN, otherwise hold the target height.
					sneak = m == PathSearch.Move.SWIM_DOWN;
					jump = !sneak && (m == PathSearch.Move.SWIM_UP || m == PathSearch.Move.ASCEND || p.getY() < dest.getY() + 0.1);
				}
				steer(mc, c, sprint, jump, sneak, WALK_PITCH);
			}
		}
	}

	/** Cells that must be open before the move, in breaking order (top first). A slab or carpet you step onto stays. */
	private static List<BlockPos> clearCells(PathWorld w, BlockPos from, PathSearch.Step to) {
		BlockPos d = to.pos();
		boolean tall = w.lowFloor(d.getX(), d.getY(), d.getZ()) > 0.2 || w.lowFloor(from.getX(), from.getY(), from.getZ()) > 0.2;
		List<BlockPos> cells = switch (to.move()) {
			case WALK, DIAGONAL, SWIM, BRIDGE -> tall ? List.of(d.above(2), d.above(), d) : List.of(d.above(), d);
			case ASCEND -> List.of(from.above(2), d.above(), d);
			case DESCEND -> List.of(d.above(2), d.above(), d);
			case DOWN, CLIMB_DOWN, SWIM_DOWN -> List.of(d);
			case PILLAR, CLIMB_UP, SWIM_UP -> List.of(d.above());
			default -> List.of();
		};
		if (w.lowFloor(d.getX(), d.getY(), d.getZ()) < 0) return cells;
		List<BlockPos> out = new ArrayList<>(cells);
		out.remove(d);
		return out;
	}

	/** True while sand or gravel sits on top of one of {@code cells} or is falling into them. */
	private static boolean fallingAbove(Minecraft mc, List<BlockPos> cells) {
		if (cells.isEmpty()) return false;
		AABB box = new AABB(cells.get(0));
		for (BlockPos c : cells) {
			box = box.minmax(new AABB(c));
			if (!cells.contains(c.above()) && mc.level.getBlockState(c.above()).getBlock() instanceof FallingBlock) return true;
		}
		return !mc.level.getEntitiesOfClass(FallingBlockEntity.class, box.expandTowards(0, 3, 0), e -> true).isEmpty();
	}

	private static double segmentDistance(double px, double pz, BlockPos a, BlockPos b) {
		double ax = a.getX() + 0.5;
		double az = a.getZ() + 0.5;
		double bx = b.getX() + 0.5 - ax;
		double bz = b.getZ() + 0.5 - az;
		double len = bx * bx + bz * bz;
		double t = len == 0 ? 0 : Mth.clamp(((px - ax) * bx + (pz - az) * bz) / len, 0, 1);
		double dx = px - (ax + bx * t);
		double dz = pz - (az + bz * t);
		return Math.sqrt(dx * dx + dz * dz);
	}

	private static double flatDistance(LocalPlayer p, Vec3 c) {
		double dx = c.x - p.getX();
		double dz = c.z - p.getZ();
		return Math.sqrt(dx * dx + dz * dz);
	}

	// ---- controls ----

	private static void keys(Minecraft mc, boolean fwd, boolean back, boolean left, boolean right, boolean jump, boolean sneak, boolean sprint) {
		var o = mc.options;
		o.keyUp.setDown(fwd);
		o.keyDown.setDown(back);
		o.keyLeft.setDown(left);
		o.keyRight.setDown(right);
		o.keyJump.setDown(jump);
		o.keyShift.setDown(sneak);
		o.keySprint.setDown(sprint);
		controlling = true;
	}

	/** Lets go of every key the executor holds and aborts a half-broken block. */
	private static void release(Minecraft mc) {
		if (controlling) {
			keys(mc, false, false, false, false, false, false, false);
			controlling = false;
		}
		if (breaking != null && mc.gameMode != null && mc.gameMode.isDestroying()) mc.gameMode.stopDestroyBlock();
		breaking = null;
	}

	/** Eases the view toward (yaw, pitch) within the sanitizer's turn limit; returns the remaining error in degrees. */
	private static float turn(LocalPlayer p, float yaw, float pitch) {
		float ey = Mth.wrapDegrees(yaw - p.getYRot());
		float ep = pitch - p.getXRot();
		float dy = ProtocolSanitizer.limitTurn(Math.abs(ey) > 4 ? ey * 0.6f : ey);
		float dp = ProtocolSanitizer.limitTurn(Math.abs(ep) > 4 ? ep * 0.6f : ep);
		p.setYRot(p.getYRot() + dy);
		p.setXRot(Mth.clamp(p.getXRot() + dp, -90.0f, 90.0f));
		return Math.max(Math.abs(Mth.wrapDegrees(yaw - p.getYRot())), Math.abs(pitch - p.getXRot()));
	}

	private static boolean lookAt(LocalPlayer p, Vec3 point, float tolerance) {
		Vec3 to = point.subtract(p.getEyePosition());
		double flat = Math.sqrt(to.x * to.x + to.z * to.z);
		float yaw = (float) (Mth.atan2(to.z, to.x) * Mth.RAD_TO_DEG) - 90.0f;
		float pitch = (float) -(Mth.atan2(to.y, flat) * Mth.RAD_TO_DEG);
		return turn(p, yaw, pitch) <= tolerance;
	}

	/** Turns toward {@code target} and presses whichever movement keys point at it from the current view. */
	private static void steer(Minecraft mc, Vec3 target, boolean sprint, boolean jump, boolean sneak, float pitch) {
		LocalPlayer p = mc.player;
		double dx = target.x - p.getX();
		double dz = target.z - p.getZ();
		double dist = Math.sqrt(dx * dx + dz * dz);
		float yaw = dist > 0.2 ? (float) (Mth.atan2(dz, dx) * Mth.RAD_TO_DEG) - 90.0f : p.getYRot();
		turn(p, yaw, pitch);
		boolean f = false;
		boolean b = false;
		boolean l = false;
		boolean r = false;
		if (dist > 0.08) {
			double rad = Math.toRadians(p.getYRot());
			double fd = (dx * -Math.sin(rad) + dz * Math.cos(rad)) / dist;
			double ld = (dx * Math.cos(rad) + dz * Math.sin(rad)) / dist;
			f = fd > 0.38;
			b = fd < -0.38;
			l = ld > 0.38;
			r = ld < -0.38;
		}
		keys(mc, f, b, l, r, jump, sneak, sprint && f && !l && !r && !b);
	}

	// ---- breaking and placing ----

	/** Breaks {@code pos} a tick at a time; false once it has taken far longer than expected. */
	private static boolean dig(Minecraft mc, PathWorld w, BlockPos pos) {
		LocalPlayer p = mc.player;
		if (!pos.equals(breaking)) {
			breaking = pos;
			breakTicks = 0;
			breakLimit = (int) Math.min(2400, w.ticksToBreak(pos.getX(), pos.getY(), pos.getZ()) * 3 + 60);
		}
		if (++breakTicks > breakLimit) {
			release(mc);
			return false;
		}
		BlockState s = mc.level.getBlockState(pos);
		selectTool(p, s);
		Direction face = faceToward(pos, p.getEyePosition());
		Vec3 aim = Vec3.atCenterOf(pos).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
		keys(mc, false, false, false, false, false, false, false);
		if (lookAt(p, aim, 30.0f)) {
			mc.gameMode.continueDestroyBlock(pos, face);
			p.swing(InteractionHand.MAIN_HAND, p.getMainHandItem().getAttackAnimation(), false);
		}
		return true;
	}

	private static Direction faceToward(BlockPos pos, Vec3 eye) {
		double dx = eye.x - (pos.getX() + 0.5);
		double dy = eye.y - (pos.getY() + 0.5);
		double dz = eye.z - (pos.getZ() + 0.5);
		double ax = Math.abs(dx);
		double ay = Math.abs(dy);
		double az = Math.abs(dz);
		if (ay >= ax && ay >= az) return dy > 0 ? Direction.UP : Direction.DOWN;
		if (ax >= az) return dx > 0 ? Direction.EAST : Direction.WEST;
		return dz > 0 ? Direction.SOUTH : Direction.NORTH;
	}

	/** Selects the hotbar item that breaks {@code s} fastest, preferring ones that get the drop. */
	private static void selectTool(LocalPlayer p, BlockState s) {
		var inv = p.getInventory();
		int best = inv.getSelectedSlot();
		double bestScore = toolScore(inv.getItem(best), s);
		for (int i = 0; i < 9; i++) {
			double score = toolScore(inv.getItem(i), s);
			if (score > bestScore + 0.01) {
				bestScore = score;
				best = i;
			}
		}
		inv.setSelectedSlot(best);
	}

	private static double toolScore(ItemStack stack, BlockState s) {
		if (!stack.isEmpty() && stack.isDamageableItem() && stack.getMaxDamage() - stack.getDamageValue() <= Settings.getI("autotool.protect")) return -1;
		float speed = stack.isEmpty() ? 1.0f : stack.getDestroySpeed(s);
		boolean harvest = !s.requiresCorrectToolForDrops() || (!stack.isEmpty() && stack.isCorrectToolForDrops(s));
		return (harvest ? 1000 : 0) + speed;
	}

	private static Set<Block> throwawayBlocks() {
		String text = Settings.text("pathfinder.throwaway");
		if (!text.equals(throwawayText)) {
			Set<Block> set = new HashSet<>();
			for (String n : text.trim().split("[\\s,]+")) {
				if (n.isBlank()) continue;
				Block b = block(n.contains(":") ? n.toLowerCase(Locale.ROOT) : "minecraft:" + n.toLowerCase(Locale.ROOT));
				if (b != null) set.add(b);
			}
			throwaway = set;
			throwawayText = text;
		}
		return throwaway;
	}

	/** Hotbar slot holding a block from the throwaway list (the selected one first), or -1. */
	private static int throwawaySlot(LocalPlayer p) {
		var inv = p.getInventory();
		Set<Block> set = throwawayBlocks();
		int sel = inv.getSelectedSlot();
		if (inv.getItem(sel).getItem() instanceof BlockItem bi && set.contains(bi.getBlock())) return sel;
		for (int i = 0; i < 9; i++) {
			if (inv.getItem(i).getItem() instanceof BlockItem bi && set.contains(bi.getBlock())) return i;
		}
		return -1;
	}

	/**
	 * What the crosshair is on with the rotation the server already has (set last tick): the same ray vanilla uses,
	 * so a click only happens when the face is in view. Null when it is not a block.
	 */
	private static BlockHitResult crosshair(LocalPlayer p) {
		HitResult h = p.pick(p.blockInteractionRange(), 1.0f, false);
		return h instanceof BlockHitResult b && b.getType() == HitResult.Type.BLOCK ? b : null;
	}

	/**
	 * Places a throwaway block at {@code target} against its neighbour in direction {@code support}. Clicks only when
	 * the crosshair ray hits that exact face; otherwise turns toward the face centre and tries next tick.
	 */
	private static boolean placeAgainst(Minecraft mc, BlockPos target, Direction support, int slot) {
		LocalPlayer p = mc.player;
		BlockPos against = target.relative(support);
		Direction face = support.getOpposite();
		BlockHitResult hit = crosshair(p);
		if (p.isShiftKeyDown() && hit != null && hit.getBlockPos().equals(against) && hit.getDirection() == face) {
			p.getInventory().setSelectedSlot(slot);
			var result = mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, hit);
			if (result.consumesAction()) p.swing(InteractionHand.MAIN_HAND, p.getMainHandItem().getAttackAnimation(), false);
			return true;
		}
		lookAt(p, Vec3.atCenterOf(target).add(support.getStepX() * 0.5, support.getStepY() * 0.5, support.getStepZ() * 0.5), 180.0f);
		return false;
	}

	/** Right-clicks the door or gate in {@code pos} (either half) once the crosshair is on it, without sneaking. */
	private static void openDoor(Minecraft mc, BlockPos pos) {
		LocalPlayer p = mc.player;
		keys(mc, false, false, false, false, false, false, false);
		BlockHitResult hit = crosshair(p);
		if (useCooldown == 0 && !p.isShiftKeyDown() && hit != null && hit.getBlockPos().getX() == pos.getX() && hit.getBlockPos().getZ() == pos.getZ()
				&& Math.abs(hit.getBlockPos().getY() - pos.getY()) <= 1 && PathWorld.isDoor(mc.level.getBlockState(hit.getBlockPos()))) {
			var result = mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, hit);
			if (result.consumesAction()) p.swing(InteractionHand.MAIN_HAND, p.getMainHandItem().getAttackAnimation(), false);
			useCooldown = 10;
			return;
		}
		lookAt(p, Vec3.atCenterOf(pos), 180.0f);
	}

	/** Sneaks to the edge facing {@code dest}, then places its floor against the block you stand on. */
	private static void bridge(Minecraft mc, PathWorld w, BlockPos from, BlockPos dest) {
		LocalPlayer p = mc.player;
		int slot = throwawaySlot(p);
		BlockPos floor = dest.below();
		int sx = dest.getX() - from.getX();
		int sz = dest.getZ() - from.getZ();
		Direction back = sx > 0 ? Direction.WEST : sx < 0 ? Direction.EAST : sz > 0 ? Direction.NORTH : Direction.SOUTH;
		Direction support = w.support(floor.getX(), floor.getY(), floor.getZ(), back);
		if (slot < 0 || support == null) {
			replan(mc, slot < 0 ? "out of blocks to place" : "nothing to place against");
			return;
		}
		Vec3 c = Vec3.atBottomCenterOf(from);
		double along = (p.getX() - c.x) * sx + (p.getZ() - c.z) * sz;
		// The side face of the block you stand on is only in view once your eyes are past its edge.
		if (along < 0.62 && bridgeTicks++ < 40) {
			steer(mc, Vec3.atBottomCenterOf(dest), false, false, true, 75.0f);
			return;
		}
		keys(mc, false, false, false, false, false, true, false);
		placeAgainst(mc, floor, support, slot);
		if (++bridgeTicks > 120) replan(mc, "could not place a block");
	}

	/** Centres, looks down, jumps and places a block where the feet were. */
	private static void pillar(Minecraft mc, PathWorld w, BlockPos from) {
		LocalPlayer p = mc.player;
		int slot = throwawaySlot(p);
		if (slot < 0) {
			replan(mc, "out of blocks to place");
			return;
		}
		Vec3 c = Vec3.atBottomCenterOf(from);
		boolean standing = p.onGround() && feet(p).equals(from);
		if (standing && flatDistance(p, c) > 0.2) {
			steer(mc, c, false, false, true, 90.0f);
			return;
		}
		turn(p, p.getYRot(), 90.0f);
		keys(mc, false, false, false, false, standing && p.getXRot() > 70.0f, true, false);
		if (!p.onGround() && p.getY() >= from.getY() + 1.0 && w.space(from.getX(), from.getY(), from.getZ())) placeAgainst(mc, from, Direction.DOWN, slot);
	}

	// ---- mine ----

	private static void mineTick(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (mineLimit > 0 && mined >= mineLimit) {
			stop(mc, "Mined " + mined + " " + mineLabel + ", done");
			return;
		}
		if (p.getInventory().getFreeSlot() < 0) {
			stop(mc, "Inventory full, stopped");
			return;
		}
		PathWorld w = world(mc);

		if (mineTarget != null) {
			if (!mineBlocks.contains(mc.level.getBlockState(mineTarget).getBlock())) {
				mined++;
				targets.remove(mineTarget);
				collectFrom = mineTarget;
				collectTicks = 0;
				mineTarget = null;
				release(mc);
				return;
			}
			if (canMineFrom(mc, w, mineTarget)) {
				state = "mining";
				if (!dig(mc, w, mineTarget)) {
					failed.add(mineTarget.asLong());
					targets.remove(mineTarget);
					mineTarget = null;
				}
				return;
			}
			mineTarget = null;
			release(mc);
		}

		if (collectFrom != null && collect(mc)) return;

		if (--scanWait <= 0) {
			scan(mc);
			scanWait = targets.isEmpty() ? 100 : 200;
		}
		targets.removeIf(t -> failed.contains(t.asLong()) || !mineBlocks.contains(mc.level.getBlockState(t).getBlock()) || fluidNear(mc, t));
		if (targets.isEmpty()) {
			release(mc);
			cancelSearch();
			path = null;
			state = "none in loaded chunks, waiting";
			return;
		}

		if (p.onGround()) {
			BlockPos feet = feet(p);
			for (int i = 0; i < targets.size() && i < GOAL_TARGETS; i++) {
				BlockPos t = targets.get(i);
				if (canMineFrom(mc, w, t)) {
					mineTarget = t;
					cancelSearch();
					path = null;
					release(mc);
					return;
				}
				// Standing where the plan said the target is reachable, but it is not safe to break: skip it.
				if (new PathGoal.Reach(t).isGoal(feet.getX(), feet.getY(), feet.getZ()) && (path == null || index >= path.size() - 1)) {
					failed.add(t.asLong());
				}
			}
		}
		drive(mc);
		if (pending != null) state = "calculating";
	}

	/**
	 * True when {@code t} is right next to you (a {@link PathGoal.Reach} position) or in reach with a clear line to it,
	 * and breaking it does not drop you or something onto you.
	 */
	private static boolean canMineFrom(Minecraft mc, PathWorld w, BlockPos t) {
		LocalPlayer p = mc.player;
		Vec3 eye = p.getEyePosition();
		Vec3 centre = Vec3.atCenterOf(t);
		if (eye.distanceTo(centre) > p.blockInteractionRange() - 0.5 || fluidNear(mc, t)) return false;
		BlockPos feet = feet(p);
		// under you: only when there is floor below it
		if (t.getY() < feet.getY() && Math.abs(t.getX() + 0.5 - p.getX()) < 0.8 && Math.abs(t.getZ() + 0.5 - p.getZ()) < 0.8
				&& !w.standable(t.getX(), t.getY() - 1, t.getZ())) return false;
		// over you: not with sand or gravel on top of it
		if (t.getY() > feet.getY() && mc.level.getBlockState(t.above()).getBlock() instanceof FallingBlock
				&& Math.abs(t.getX() + 0.5 - p.getX()) < 0.8 && Math.abs(t.getZ() + 0.5 - p.getZ()) < 0.8) return false;
		if (new PathGoal.Reach(t).isGoal(feet.getX(), feet.getY(), feet.getZ())) return true;
		BlockHitResult hit = mc.level.clip(new ClipContext(eye, centre, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
		return hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(t);
	}

	/** Walks over the drops near the last mined block; true while still busy with that. */
	private static boolean collect(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (!Settings.on("pathfinder.pickup") || ++collectTicks > 300) {
			collectDone();
			return false;
		}
		if (collectTicks < 8) {
			release(mc);
			state = "waiting for drops";
			return true;
		}
		if (collectItem == null || !collectItem.isAlive()) {
			if (collectItem != null) {
				collectItem = null;
				path = null;
				cancelSearch();
			}
			AABB area = new AABB(collectFrom).inflate(6);
			ItemEntity best = null;
			double bestDist = Double.MAX_VALUE;
			for (ItemEntity e : mc.level.getEntitiesOfClass(ItemEntity.class, area, e -> e.isAlive() && !ignoredItems.contains(e.getId()))) {
				double d = e.distanceToSqr(p);
				if (d < bestDist) {
					bestDist = d;
					best = e;
				}
			}
			if (best == null) {
				collectDone();
				return false;
			}
			collectItem = best;
			goal = new PathGoal.Block(BlockPos.containing(best.getX(), best.getY() + 0.2, best.getZ()));
			path = null;
			cancelSearch();
		}
		state = "picking up drops";
		BlockPos feet = feet(p);
		if (goal.isGoal(feet.getX(), feet.getY(), feet.getZ())) {
			// standing on it but it was not picked up (full stack, pickup delay): wait a little, then give up on it
			release(mc);
			if (collectTicks % 40 == 0) ignoredItems.add(collectItem.getId());
			return true;
		}
		drive(mc);
		return true;
	}

	private static void collectDone() {
		collectFrom = null;
		collectItem = null;
		goal = null;
		path = null;
		cancelSearch();
	}

	/** Finds the configured blocks in loaded chunks around the player, nearest first. */
	private static void scan(Minecraft mc) {
		LocalPlayer p = mc.player;
		ChunkPos centre = p.chunkPosition();
		int radius = Math.min(mc.options.getEffectiveRenderDistance(), Settings.getI("pathfinder.mineradius"));
		List<BlockPos> found = new ArrayList<>();
		for (int cx = centre.x() - radius; cx <= centre.x() + radius; cx++) {
			for (int cz = centre.z() - radius; cz <= centre.z() + radius; cz++) {
				ChunkAccess chunk = mc.level.getChunk(cx, cz, ChunkStatus.FULL, false);
				if (chunk == null) continue;
				LevelChunkSection[] sections = chunk.getSections();
				for (int i = 0; i < sections.length; i++) {
					LevelChunkSection section = sections[i];
					if (section.hasOnlyAir() || !section.maybeHas(s -> mineBlocks.contains(s.getBlock()))) continue;
					int baseY = (chunk.getMinSectionY() + i) << 4;
					for (int y = 0; y < 16; y++) {
						for (int z = 0; z < 16; z++) {
							for (int x = 0; x < 16; x++) {
								if (!mineBlocks.contains(section.getBlockState(x, y, z).getBlock())) continue;
								BlockPos pos = new BlockPos((cx << 4) + x, baseY + y, (cz << 4) + z);
								if (!failed.contains(pos.asLong()) && !fluidNear(mc, pos)) found.add(pos);
							}
						}
					}
				}
			}
		}
		Vec3 at = p.position();
		found.sort((a, b) -> Double.compare(a.distToCenterSqr(at), b.distToCenterSqr(at)));
		targets = new ArrayList<>(found.subList(0, Math.min(found.size(), MAX_TARGETS)));
	}

	/** True when water or lava touches {@code pos}, so breaking it would let the fluid in. */
	private static boolean fluidNear(Minecraft mc, BlockPos pos) {
		for (Direction d : Direction.values()) {
			if (!mc.level.getFluidState(pos.relative(d)).isEmpty()) return true;
		}
		return false;
	}

	// ---- safety ----

	/**
	 * Handles lava and air before anything else: climbs out of lava back the way it came, blocks lava that reaches
	 * your feet or head with a throwaway block, and swims up for air when the air bar is a third full. True while busy.
	 */
	private static boolean emergency(Minecraft mc) {
		LocalPlayer p = mc.player;
		if (p.isInLava()) {
			inLava = true;
			state = "escaping lava";
			if (breaking != null && mc.gameMode.isDestroying()) mc.gameMode.stopDestroyBlock();
			breaking = null;
			Vec3 back = path != null ? Vec3.atBottomCenterOf(path.get(Math.max(0, index - 1)).pos()) : p.position().subtract(p.getLookAngle().scale(2));
			steer(mc, back, false, true, false, WALK_PITCH);
			return true;
		}
		if (inLava) {
			inLava = false;
			cancelSearch();
			release(mc);
			path = null;
		}
		if (plugLava(mc)) return true;
		if (!surfacing && p.isUnderWater() && p.getAirSupply() < p.getMaxAirSupply() / 3) {
			surfacing = true;
			cancelSearch();
			release(mc);
			path = null;
		}
		if (surfacing) {
			if (p.getAirSupply() >= p.getMaxAirSupply() || !p.isInWater()) {
				surfacing = false;
			} else {
				keys(mc, false, false, false, false, true, false, false);
				state = "surfacing for air";
				return true;
			}
		}
		return false;
	}

	/** Places a throwaway block into lava touching your feet or head cell, if there is one and a face to place against. */
	private static boolean plugLava(Minecraft mc) {
		LocalPlayer p = mc.player;
		BlockPos f = feet(p);
		BlockPos head = f.above();
		BlockPos[] around = {f.north(), f.south(), f.east(), f.west(), head.north(), head.south(), head.east(), head.west(), head.above(), f.below()};
		for (BlockPos c : around) {
			if (!mc.level.getFluidState(c).is(FluidTags.LAVA)) continue;
			int slot = throwawaySlot(p);
			if (slot < 0 || ++plugTicks > 40) return false;
			for (Direction d : Direction.values()) {
				BlockPos n = c.relative(d);
				if (n.equals(f) || n.equals(head)) continue;
				BlockState s = mc.level.getBlockState(n);
				if (!s.getFluidState().isEmpty() || s.getCollisionShape(mc.level, n).isEmpty()) continue;
				if (breaking != null && mc.gameMode.isDestroying()) mc.gameMode.stopDestroyBlock();
				breaking = null;
				keys(mc, false, false, false, false, false, true, false);
				state = "blocking lava";
				placeAgainst(mc, c, d, slot);
				return true;
			}
		}
		plugTicks = 0;
		return false;
	}

	// ---- follow ----

	private static void followTick(Minecraft mc) {
		LocalPlayer p = mc.player;
		Player target = null;
		for (Player other : mc.level.players()) {
			if (other != p && other.getName().getString().equalsIgnoreCase(followName)) target = other;
		}
		if (target == null) {
			release(mc);
			cancelSearch();
			path = null;
			state = followName + " is out of view";
			return;
		}
		if (followWait > 0) {
			followWait--;
			release(mc);
			return;
		}
		BlockPos tp = BlockPos.containing(target.getX(), target.getY() + 0.2, target.getZ());
		if (p.distanceTo(target) < 3.0) {
			release(mc);
			cancelSearch();
			path = null;
			goal = null;
			state = "next to " + followName;
			return;
		}
		if (!(goal instanceof PathGoal.Near near) || Math.abs(near.x() - tp.getX()) + Math.abs(near.z() - tp.getZ()) > 3 || Math.abs(near.y() - tp.getY()) > 1) {
			goal = new PathGoal.Near(tp.getX(), tp.getY(), tp.getZ(), 1);
			cancelSearch();
			path = null;
		}
		drive(mc);
	}

	// ---- rendering ----

	/** Node centres from the current step to the end of the path. */
	public static List<Vec3> renderPath() {
		List<PathSearch.Step> pth = path;
		if (pth == null) return List.of();
		List<Vec3> out = new ArrayList<>();
		for (int i = Math.max(0, index); i < pth.size() && out.size() < 1000; i++) {
			BlockPos b = pth.get(i).pos();
			out.add(new Vec3(b.getX() + 0.5, b.getY() + 0.1, b.getZ() + 0.5));
		}
		return out;
	}

	/** The block being broken (red), the mine target (cyan) and a fixed goal block (green). */
	public static List<Mark> marks() {
		List<Mark> out = new ArrayList<>();
		if (breaking != null) out.add(new Mark(breaking, 0xFF3030));
		if (mineTarget != null && !mineTarget.equals(breaking)) out.add(new Mark(mineTarget, 0x33E6FF));
		if (mode == Mode.GOTO && goal instanceof PathGoal.Block b) out.add(new Mark(new BlockPos(b.x(), b.y(), b.z()), 0x55FF55));
		return out;
	}

	private static String title() {
		return switch (mode) {
			case GOTO -> "going to " + (goal == null ? "?" : goal.describe());
			case MINE -> "mining " + mineLabel + " (" + mined + (mineLimit > 0 ? "/" + mineLimit : "") + ")";
			case FOLLOW -> "following " + followName;
			default -> "idle";
		};
	}

	private static String detail(Minecraft mc) {
		StringBuilder sb = new StringBuilder(state);
		if (path != null) sb.append(", ").append(path.size() - 1 - index).append(" steps").append(partial ? "+" : "");
		if (mode == Mode.GOTO && goal != null) {
			BlockPos f = feet(mc.player);
			double dx;
			double dz;
			double dy = 0;
			if (goal instanceof PathGoal.Block b) {
				dx = b.x() - f.getX();
				dz = b.z() - f.getZ();
				dy = b.y() - f.getY();
			} else if (goal instanceof PathGoal.XZ g) {
				dx = g.x() - f.getX();
				dz = g.z() - f.getZ();
			} else {
				return sb.toString();
			}
			sb.append(", ").append(Math.round(Math.sqrt(dx * dx + dy * dy + dz * dz))).append(" m");
		}
		return sb.toString();
	}

	private static void renderHud(GuiGraphicsExtractor graphics, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (!StarClient.pathfinderOn || !Settings.on("pathfinder.show") || mc.player == null || mc.gui.hud.isHidden()) return;
		int y = mc.getWindow().getGuiScaledHeight() / 2 - 10;
		graphics.text(mc.font, Component.literal("Pathfinder: " + title()), 4, y, 0xFF55FFFF, true);
		graphics.text(mc.font, Component.literal(detail(mc)), 4, y + 10, 0xFFFFFFFF, true);
	}
}
