package com.starclient;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.tags.FluidTags;
import net.minecraft.util.Mth;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.LiquidBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/**
 * Pathfinder's {@code #build}: places a Litematica schematic. A sweep over the schematic (spread across ticks)
 * finds what is left; nearby work is done first (wrong blocks broken top-down, missing ones placed bottom-up),
 * otherwise {@link Pathing} walks within reach of the next job. Each placement is predicted with the game's own
 * placement code for every visible face and click point, so stairs, logs, slabs and the like come out facing the
 * right way, and the click only happens when the crosshair ray really hits that face.
 */
public final class Builder {
	private enum Kind { PLACE, BREAK }

	/** A rotation that clicks a face giving the wanted block. */
	private record Aim(float yaw, float pitch) { }

	private static final int SWEEP_PER_TICK = 40_000;
	private static final int JOB_TIMEOUT = 200;
	private static final int PLACE_DELAY = 2;
	private static final int MAX_TRIES = 4;
	private static final double[] OFFSETS = {0, -0.3, 0.3};

	private static Schematic.Placed plan;
	private static String fileName;

	// sweep in progress and its last published result
	private static int cursor;
	private static List<BlockPos> sweepJobs = new ArrayList<>();
	private static Map<Item, Integer> sweepMissing = new HashMap<>();
	private static int sweepLeft;
	private static BlockPos sweepUnloaded;
	private static List<BlockPos> jobs = List.of();
	private static Map<Item, Integer> missing = Map.of();
	private static int left = -1;
	private static BlockPos unloaded;
	private static int sweeps;

	private static BlockPos current;
	private static Kind kind;
	private static int jobTicks;
	private static Aim aim;
	private static Vec3 aimFrom;
	private static int placeWait;
	private static int swapWait;
	private static int placed;
	private static BlockPos travel;
	private static final Map<Long, Integer> tries = new HashMap<>();
	private static final Set<Long> skipped = new HashSet<>();
	/** Positions that could not be placed from where the player stands now; cleared when the player moves. */
	private static final Set<Long> blockedHere = new HashSet<>();
	private static BlockPos blockedFrom;
	private static List<Pathing.Mark> marks = List.of();
	private static Method placementState;

	private Builder() { }

	public static Schematic.Placed plan() {
		return plan;
	}

	private static Path folder(Minecraft mc) {
		return mc.gameDirectory.toPath().resolve("schematics");
	}

	/** {@code #build <name> [x y z]} and {@code #build list}. */
	static void command(Minecraft mc, String[] args) {
		if (args.length == 0) throw new IllegalArgumentException("#build <schematic> [x y z]  |  #build list");
		List<Path> files = schematicFiles(mc);
		if (args[0].equalsIgnoreCase("list")) {
			if (files.isEmpty()) {
				Pathing.say(mc, "No .litematic files in " + folder(mc), ChatFormatting.YELLOW);
				return;
			}
			List<String> names = new ArrayList<>();
			for (Path f : files) names.add(folder(mc).relativize(f).toString().replaceFirst("\\.litematic$", ""));
			Pathing.say(mc, "Schematics: " + String.join(", ", names), ChatFormatting.WHITE);
			return;
		}
		boolean coords = args.length >= 4 && args[args.length - 3].matches("~?-?\\d*") && args[args.length - 1].matches("~?-?\\d*");
		String name = String.join(" ", java.util.Arrays.copyOfRange(args, 0, coords ? args.length - 3 : args.length));
		Path file = find(mc, files, name);
		if (file == null) throw new IllegalArgumentException("No schematic '" + name + "' in " + folder(mc) + " (#build list)");
		Schematic s;
		try {
			s = Schematic.load(file);
		} catch (IOException | RuntimeException e) {
			StarClient.LOGGER.warn("Could not load schematic {}", file, e);
			throw new IllegalArgumentException("Could not read " + file.getFileName() + ": " + e.getMessage());
		}
		BlockPos feet = Pathing.feet(mc.player);
		int x = feet.getX();
		int y = feet.getY();
		int z = feet.getZ();
		if (coords) {
			x = Pathing.coord(args[args.length - 3], x);
			y = Pathing.coord(args[args.length - 2], y);
			z = Pathing.coord(args[args.length - 1], z);
		}
		Pathing.begin(mc, Pathing.Mode.BUILD, null);
		plan = new Schematic.Placed(s, x, y, z);
		fileName = file.getFileName().toString();
		Pathing.say(mc, "Building " + s.name + " (" + s.sizeX + "x" + s.sizeY + "x" + s.sizeZ + ", " + s.blocks + " blocks) at " + x + " " + y + " " + z,
				ChatFormatting.GREEN);
	}

	private static List<Path> schematicFiles(Minecraft mc) {
		Path dir = folder(mc);
		if (!Files.isDirectory(dir)) return List.of();
		try (Stream<Path> walk = Files.walk(dir, 4)) {
			return walk.filter(p -> p.toString().toLowerCase(Locale.ROOT).endsWith(".litematic")).sorted().toList();
		} catch (IOException e) {
			return List.of();
		}
	}

	private static Path find(Minecraft mc, List<Path> files, String name) {
		String want = name.toLowerCase(Locale.ROOT).replaceFirst("\\.litematic$", "");
		for (Path f : files) {
			String rel = folder(mc).relativize(f).toString().toLowerCase(Locale.ROOT).replace('\\', '/').replaceFirst("\\.litematic$", "");
			String base = f.getFileName().toString().toLowerCase(Locale.ROOT).replaceFirst("\\.litematic$", "");
			if (rel.equals(want) || base.equals(want)) return f;
		}
		return null;
	}

	static void clear() {
		plan = null;
		cursor = 0;
		sweepJobs = new ArrayList<>();
		sweepMissing = new HashMap<>();
		sweepLeft = 0;
		sweepUnloaded = null;
		jobs = List.of();
		missing = Map.of();
		left = -1;
		unloaded = null;
		sweeps = 0;
		current = null;
		aim = null;
		placeWait = 0;
		swapWait = 0;
		placed = 0;
		travel = null;
		tries.clear();
		skipped.clear();
		blockedHere.clear();
		blockedFrom = null;
		marks = List.of();
	}

	static String title() {
		if (plan == null) return "building";
		return "building " + plan.schematic().name + " (" + placed + " placed" + (left >= 0 ? ", " + left + " left" : "") + ")";
	}

	static List<Pathing.Mark> marks() {
		return marks;
	}

	// ---- tick ----

	static void tick(Minecraft mc) {
		LocalPlayer p = mc.player;
		sweep(mc);
		if (sweeps > 0 && left == 0 && unloaded == null) {
			Pathing.stop(mc, "Finished building " + plan.schematic().name + " (" + placed + " placed)");
			return;
		}
		if (mc.level.getGameTime() % 10 == 0) updateMarks(mc);
		if (placeWait > 0) placeWait--;
		if (swapWait > 0) {
			swapWait--;
			Pathing.release(mc);
			return;
		}
		PathWorld w = Pathing.world(mc);
		BlockPos feet = Pathing.feet(p);
		if (!feet.equals(blockedFrom)) {
			blockedHere.clear();
			blockedFrom = feet;
		}

		if (current != null && work(mc, w)) return;

		if (p.onGround() || p.isInWater()) {
			BlockPos job = localJob(mc);
			if (job != null) {
				current = job;
				kind = null;
				jobTicks = 0;
				aim = null;
				travel = null;
				Pathing.dropPath(mc);
				work(mc, w);
				return;
			}
		}

		if (sweeps == 0) {
			Pathing.release(mc);
			Pathing.state = "reading the schematic";
			return;
		}
		if (jobs.isEmpty()) {
			if (unloaded != null) {
				goTo(mc, new PathGoal.XZ(unloaded.getX(), unloaded.getZ()), unloaded);
				Pathing.state = "walking to unloaded part";
				return;
			}
			finishIncomplete(mc);
			return;
		}
		if (travel == null || classify(mc, travel, plan.desired(travel)) == null || skipped.contains(travel.asLong())) travel = pickTravel(mc);
		if (travel == null) {
			finishIncomplete(mc);
			return;
		}
		// Arrived where the job should be reachable but nothing local could be done: try another side, then give up on it.
		PathGoal want = travelGoal(travel);
		if (want.isGoal(feet.getX(), feet.getY(), feet.getZ()) && Pathing.path == null && Pathing.pending == null) {
			int n = tries.merge(travel.asLong(), 1, Integer::sum);
			if (n >= MAX_TRIES) skipped.add(travel.asLong());
			travel = null;
			return;
		}
		goTo(mc, want, travel);
		Pathing.state = "walking to the next block";
	}

	private static PathGoal travelGoal(BlockPos t) {
		int n = tries.getOrDefault(t.asLong(), 0);
		if (n == 0) return new PathGoal.Within(t, 3.5);
		Direction side = Direction.from2DDataValue(n);
		BlockPos stand = t.relative(side, 2);
		return new PathGoal.Near(stand.getX(), t.getY(), stand.getZ(), 0);
	}

	private static void goTo(Minecraft mc, PathGoal g, BlockPos target) {
		if (!g.equals(Pathing.goal)) {
			Pathing.goal = g;
			Pathing.dropPath(mc);
		}
		Pathing.drive(mc);
	}

	/** Nothing left that can be done: report what is missing or could not be placed, then stop. */
	private static void finishIncomplete(Minecraft mc) {
		if (!missing.isEmpty()) {
			List<Map.Entry<Item, Integer>> list = new ArrayList<>(missing.entrySet());
			list.sort(Map.Entry.<Item, Integer>comparingByValue().reversed());
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < list.size() && i < 10; i++) {
				if (i > 0) sb.append(", ");
				sb.append(list.get(i).getValue()).append(" ").append(BuiltInRegistries.ITEM.getKey(list.get(i).getKey()).getPath());
			}
			if (list.size() > 10) sb.append(", ...");
			Pathing.stop(mc, "Out of materials. Missing: " + sb);
			return;
		}
		Pathing.stop(mc, "Stopped with " + left + " blocks left that could not be placed or broken (no face to place against, unreachable or protected)");
	}

	// ---- sweep ----

	/** Checks the next slice of the schematic; publishes the job list when a full pass completes. */
	private static void sweep(Minecraft mc) {
		Schematic s = plan.schematic();
		int end = Math.min(s.volume(), cursor + SWEEP_PER_TICK);
		boolean creative = mc.player.getAbilities().instabuild;
		long lastChunk = Long.MIN_VALUE;
		boolean loaded = false;
		for (int i = cursor; i < end; i++) {
			BlockState d = s.at(i);
			if (d == null) continue;
			BlockPos pos = plan.pos(i);
			long chunk = ChunkPos.pack(pos.getX() >> 4, pos.getZ() >> 4);
			if (chunk != lastChunk) {
				loaded = mc.level.getChunk(pos.getX() >> 4, pos.getZ() >> 4, ChunkStatus.FULL, false) != null;
				lastChunk = chunk;
			}
			if (!loaded) {
				if (sweepUnloaded == null) sweepUnloaded = pos;
				continue;
			}
			Kind k = classify(mc, pos, d);
			if (k == null) continue;
			sweepLeft++;
			if (skipped.contains(pos.asLong())) continue;
			if (k == Kind.PLACE) {
				Item item = d.getBlock().asItem();
				if (!creative && !has(mc.player, item)) {
					sweepMissing.merge(item, 1, Integer::sum);
					continue;
				}
				if (!clickable(mc, pos)) continue;
			}
			sweepJobs.add(pos);
		}
		cursor = end;
		if (cursor < s.volume()) return;
		jobs = sweepJobs;
		missing = sweepMissing;
		left = sweepLeft;
		unloaded = sweepUnloaded;
		sweeps++;
		cursor = 0;
		sweepJobs = new ArrayList<>();
		sweepMissing = new HashMap<>();
		sweepLeft = 0;
		sweepUnloaded = null;
	}

	/** What (pos) still needs, or null when it is done or nothing can be done about it. */
	private static Kind classify(Minecraft mc, BlockPos pos, BlockState d) {
		if (d == null || Schematic.automatic(d)) return null;
		BlockState c = mc.level.getBlockState(pos);
		if (Schematic.matches(c, d)) return null;
		boolean clear = Settings.on("pathfinder.buildclear");
		if (d.isAir()) return clear && !c.isAir() && breakable(mc, pos, c) ? Kind.BREAK : null;
		if (c.canBeReplaced()) return Kind.PLACE;
		if (c.getBlock() == d.getBlock() && d.hasProperty(BlockStateProperties.SLAB_TYPE) && d.getValue(BlockStateProperties.SLAB_TYPE) == SlabType.DOUBLE
				&& c.getValue(BlockStateProperties.SLAB_TYPE) != SlabType.DOUBLE) return Kind.PLACE;
		return clear && breakable(mc, pos, c) ? Kind.BREAK : null;
	}

	/** Not bedrock-hard, not a container or other block entity, not a fluid, and no lava beside it. */
	private static boolean breakable(Minecraft mc, BlockPos pos, BlockState c) {
		if (c.getDestroySpeed(mc.level, pos) < 0 || c.hasBlockEntity() || c.getBlock() instanceof LiquidBlock) return false;
		for (Direction d : Direction.values()) {
			if (mc.level.getFluidState(pos.relative(d)).is(FluidTags.LAVA)) return false;
		}
		return true;
	}

	/** A neighbour (or the half slab in the cell itself) that a block can be placed against. */
	private static boolean clickable(Minecraft mc, BlockPos pos) {
		for (Direction d : Direction.values()) {
			BlockState n = mc.level.getBlockState(pos.relative(d));
			if (!n.isAir() && !n.canBeReplaced()) return true;
		}
		return !mc.level.getBlockState(pos).canBeReplaced();
	}

	private static boolean has(LocalPlayer p, Item item) {
		var inv = p.getInventory();
		for (int i = 0; i < 36; i++) if (inv.getItem(i).is(item)) return true;
		return false;
	}

	// ---- local work ----

	/** The best job within reach: wrong blocks first (top down), then missing blocks (bottom up, nearest). */
	private static BlockPos localJob(Minecraft mc) {
		LocalPlayer p = mc.player;
		Vec3 eye = p.getEyePosition();
		double reach = p.blockInteractionRange() - 0.3;
		int r = Mth.ceil(reach);
		BlockPos centre = BlockPos.containing(eye);
		AABB body = p.getBoundingBox();
		AABB under = body.move(0, -0.2, 0);
		boolean creative = p.getAbilities().instabuild;
		BlockPos bestBreak = null;
		BlockPos bestPlace = null;
		double bestBreakScore = Double.MAX_VALUE;
		double bestPlaceScore = Double.MAX_VALUE;
		BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
		for (int dx = -r; dx <= r; dx++) {
			for (int dy = -r; dy <= r; dy++) {
				for (int dz = -r; dz <= r; dz++) {
					m.set(centre.getX() + dx, centre.getY() + dy, centre.getZ() + dz);
					BlockState d = plan.desired(m);
					if (d == null || skipped.contains(m.asLong()) || blockedHere.contains(m.asLong())) continue;
					double dist = eye.distanceTo(Vec3.atCenterOf(m));
					if (dist > reach) continue;
					Kind k = classify(mc, m, d);
					if (k == null) continue;
					AABB cell = new AABB(m);
					if (k == Kind.BREAK) {
						// never the block you stand on
						if (cell.intersects(under) && !cell.intersects(body)) continue;
						double score = -m.getY() * 10 + dist;
						if (score < bestBreakScore) {
							bestBreakScore = score;
							bestBreak = m.immutable();
						}
					} else {
						if (cell.intersects(body) || (!creative && !has(p, d.getBlock().asItem())) || !clickable(mc, m)) continue;
						double score = m.getY() * 10 + dist;
						if (score < bestPlaceScore) {
							bestPlaceScore = score;
							bestPlace = m.immutable();
						}
					}
				}
			}
		}
		return bestBreak != null ? bestBreak : bestPlace;
	}

	/** Continues the current job; false once it is done or abandoned. */
	private static boolean work(Minecraft mc, PathWorld w) {
		Kind k = classify(mc, current, plan.desired(current));
		if (k == null) {
			if (kind == Kind.PLACE) placed++;
			current = null;
			Pathing.release(mc);
			return false;
		}
		if (k != kind) {
			kind = k;
			jobTicks = 0;
			aim = null;
		}
		if (++jobTicks > JOB_TIMEOUT) {
			giveUp(mc);
			return false;
		}
		if (k == Kind.BREAK) {
			Pathing.state = "breaking";
			if (!Pathing.dig(mc, w, current)) giveUp(mc);
			return true;
		}
		Pathing.state = "placing";
		return place(mc);
	}

	private static void giveUp(Minecraft mc) {
		if (tries.merge(current.asLong(), 1, Integer::sum) >= MAX_TRIES) skipped.add(current.asLong());
		else blockedHere.add(current.asLong());
		current = null;
		Pathing.release(mc);
	}

	private static boolean place(Minecraft mc) {
		LocalPlayer p = mc.player;
		BlockState d = plan.desired(current);
		Item item = d.getBlock().asItem();
		int slot = hotbarSlot(p, item);
		if (slot < 0) {
			if (!bringToHotbar(mc, item)) {
				current = null;
				return false;
			}
			swapWait = 2;
			return true;
		}
		p.getInventory().setSelectedSlot(slot);
		ItemStack stack = p.getInventory().getItem(slot);
		Pathing.keys(mc, false, false, false, false, false, true, false);

		// Click only with the rotation the server already has and only when that ray gives exactly the wanted block.
		BlockHitResult hit = Pathing.crosshair(p);
		if (placeWait == 0 && p.isShiftKeyDown() && hit != null && predicts(mc, stack, hit, p.getYRot(), p.getXRot(), current, d)) {
			var result = mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, hit);
			if (result.consumesAction()) p.swing(InteractionHand.MAIN_HAND, p.getMainHandItem().getAttackAnimation(), false);
			placeWait = PLACE_DELAY;
			return true;
		}
		Vec3 eye = p.getEyePosition();
		if (aim == null || aimFrom == null || aimFrom.distanceToSqr(eye) > 0.01 || jobTicks % 10 == 0) {
			aim = bestAim(mc, stack, current, d);
			aimFrom = eye;
		}
		if (aim == null) {
			blockedHere.add(current.asLong());
			current = null;
			Pathing.release(mc);
			return false;
		}
		Pathing.turn(p, aim.yaw(), aim.pitch());
		return true;
	}

	/**
	 * The visible click (face and point on it, from where the player stands) that the game would turn into exactly
	 * the wanted block, needing the least turning; null when there is none from here.
	 */
	private static Aim bestAim(Minecraft mc, ItemStack stack, BlockPos target, BlockState d) {
		LocalPlayer p = mc.player;
		Vec3 eye = p.getEyePosition();
		double reach = p.blockInteractionRange() - 0.05;
		Aim best = null;
		float bestTurn = Float.MAX_VALUE;
		List<BlockPos> clickables = new ArrayList<>(7);
		for (Direction dir : Direction.values()) clickables.add(target.relative(dir));
		clickables.add(target);
		for (BlockPos n : clickables) {
			BlockState ns = mc.level.getBlockState(n);
			if (ns.isAir() || (!n.equals(target) && ns.canBeReplaced())) continue;
			for (Direction face : Direction.values()) {
				// a neighbour is clicked on the face toward the target; the target cell itself (a half slab) on any face
				if (!n.equals(target) && !n.relative(face).equals(target)) continue;
				Direction.Axis axis = face.getAxis();
				for (double a : OFFSETS) {
					for (double b : OFFSETS) {
						// a 3x3 grid of points on the face: the click height decides slab and stair halves
						Vec3 q = Vec3.atCenterOf(n).add(switch (axis) {
							case X -> new Vec3(face.getStepX() * 0.5, a, b);
							case Y -> new Vec3(a, face.getStepY() * 0.5, b);
							case Z -> new Vec3(a, b, face.getStepZ() * 0.5);
						});
						if (eye.distanceTo(q) > reach) continue;
						Vec3 end = q.add(q.subtract(eye).normalize().scale(0.05));
						BlockHitResult r = mc.level.clip(new ClipContext(eye, end, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, p));
						if (r.getType() != HitResult.Type.BLOCK || !r.getBlockPos().equals(n) || r.getDirection() != face) continue;
						Vec3 to = q.subtract(eye);
						float yaw = (float) (Mth.atan2(to.z, to.x) * Mth.RAD_TO_DEG) - 90.0f;
						float pitch = (float) -(Mth.atan2(to.y, Math.sqrt(to.x * to.x + to.z * to.z)) * Mth.RAD_TO_DEG);
						if (!predicts(mc, stack, r, yaw, pitch, target, d)) continue;
						float turn = Math.abs(Mth.wrapDegrees(yaw - p.getYRot())) + Math.abs(pitch - p.getXRot());
						if (turn < bestTurn) {
							bestTurn = turn;
							best = new Aim(yaw, pitch);
						}
					}
				}
			}
		}
		return best;
	}

	/** Runs the game's placement logic for a click with the given rotation and checks it yields the wanted block at {@code target}. */
	private static boolean predicts(Minecraft mc, ItemStack stack, BlockHitResult hit, float yaw, float pitch, BlockPos target, BlockState d) {
		if (!(stack.getItem() instanceof BlockItem item)) return false;
		LocalPlayer p = mc.player;
		float oldYaw = p.getYRot();
		float oldPitch = p.getXRot();
		p.setYRot(yaw);
		p.setXRot(pitch);
		try {
			BlockPlaceContext ctx = item.updatePlacementContext(new BlockPlaceContext(p, InteractionHand.MAIN_HAND, stack, hit));
			if (ctx == null || !ctx.canPlace() || !ctx.getClickedPos().equals(target)) return false;
			BlockState s = placementState(item, ctx);
			return s != null && s.canSurvive(mc.level, target) && Schematic.matches(s, d);
		} catch (RuntimeException e) {
			return false;
		} finally {
			p.setYRot(oldYaw);
			p.setXRot(oldPitch);
		}
	}

	/** {@code BlockItem.getPlacementState} (protected; wall torches and signs override it), else the block's own placement state. */
	private static BlockState placementState(BlockItem item, BlockPlaceContext ctx) {
		try {
			if (placementState == null) {
				placementState = BlockItem.class.getDeclaredMethod("getPlacementState", BlockPlaceContext.class);
				placementState.setAccessible(true);
			}
			return (BlockState) placementState.invoke(item, ctx);
		} catch (ReflectiveOperationException | RuntimeException e) {
			return item.getBlock().getStateForPlacement(ctx);
		}
	}

	private static int hotbarSlot(LocalPlayer p, Item item) {
		var inv = p.getInventory();
		if (inv.getItem(inv.getSelectedSlot()).is(item)) return inv.getSelectedSlot();
		for (int i = 0; i < 9; i++) if (inv.getItem(i).is(item)) return i;
		return -1;
	}

	/** Moves {@code item} into the hotbar (an empty slot, else one without a tool); in creative mode it is simply given. */
	private static boolean bringToHotbar(Minecraft mc, Item item) {
		LocalPlayer p = mc.player;
		var inv = p.getInventory();
		int target = -1;
		for (int i = 0; i < 9 && target < 0; i++) if (inv.getItem(i).isEmpty()) target = i;
		for (int i = 0; i < 9 && target < 0; i++) if (!inv.getItem(i).isDamageableItem()) target = i;
		if (target < 0) target = inv.getSelectedSlot();
		if (p.getAbilities().instabuild) {
			ItemStack stack = new ItemStack(item, Math.max(1, item.getDefaultMaxStackSize()));
			inv.setItem(target, stack);
			mc.gameMode.handleCreativeModeItemAdd(stack, 36 + target);
			return true;
		}
		if (p.containerMenu != p.inventoryMenu || !p.inventoryMenu.getCarried().isEmpty()) return false;
		for (int s = 9; s < 36; s++) {
			if (inv.getItem(s).is(item)) {
				mc.gameMode.handleContainerInput(p.inventoryMenu.containerId, s, target, ContainerInput.SWAP, p);
				return true;
			}
		}
		return false;
	}

	// ---- travel and marks ----

	/** The next job to walk to: low layers first, then the nearest. */
	private static BlockPos pickTravel(Minecraft mc) {
		Vec3 at = mc.player.position();
		int minY = Integer.MAX_VALUE;
		for (BlockPos j : jobs) if (!skipped.contains(j.asLong())) minY = Math.min(minY, j.getY());
		BlockPos best = null;
		double bestScore = Double.MAX_VALUE;
		for (BlockPos j : jobs) {
			if (skipped.contains(j.asLong())) continue;
			double score = Math.sqrt(j.distToCenterSqr(at)) + (j.getY() - minY) * 4.0;
			if (score < bestScore && classify(mc, j, plan.desired(j)) != null) {
				bestScore = score;
				best = j;
			}
		}
		return best;
	}

	/** Boxes on the nearest open jobs: blue to place, red to break. */
	private static void updateMarks(Minecraft mc) {
		Vec3 at = mc.player.position();
		List<BlockPos> near = new ArrayList<>();
		for (BlockPos j : jobs) if (j.distToCenterSqr(at) < 16 * 16) near.add(j);
		near.sort(Comparator.comparingDouble(j -> j.distToCenterSqr(at)));
		List<Pathing.Mark> out = new ArrayList<>();
		for (BlockPos j : near) {
			if (out.size() >= 150) break;
			Kind k = classify(mc, j, plan.desired(j));
			if (k != null) out.add(new Pathing.Mark(j, k == Kind.PLACE ? 0x4A90FF : 0xFF4040));
		}
		marks = out;
	}

	/** Called when the planner finds no way to the current travel target. */
	static void noPath(Minecraft mc) {
		if (travel != null) {
			if (tries.merge(travel.asLong(), 1, Integer::sum) >= MAX_TRIES) skipped.add(travel.asLong());
			travel = null;
		}
		Pathing.goal = null;
	}
}
