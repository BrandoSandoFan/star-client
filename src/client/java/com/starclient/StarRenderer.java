package com.starclient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalDouble;

import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.PrimitiveTopology;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.textures.GpuTextureView;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.StagedVertexBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import net.fabricmc.fabric.api.client.rendering.v1.level.LevelExtractionContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents;

/** Draws translucent filled boxes through walls. Pipeline code follows the Fabric 26.1.2 docs. */
public final class StarRenderer {
	/** Immutable render state produced in the extraction phase. */
	private record Box(double minX, double minY, double minZ, double maxX, double maxY, double maxZ, float r, float g, float b, float a) { }

	/** A tracer destination and its colour. */
	private record Target(Vec3 pos, float r, float g, float b) { }

	/** A flat strip from A to B, widened by (px, py, pz) on each side; drawn double sided. */
	private record Line(double ax, double ay, double az, double bx, double by, double bz, float px, float py, float pz, float r, float g, float b, float a) { }

	private static final RenderPipeline FILLED_THROUGH_WALLS = RenderPipelines.register(
			RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
					.withLocation(Identifier.fromNamespaceAndPath(StarClient.ID, "pipeline/filled_through_walls"))
					.withDepthStencilState(Optional.empty())
					.build());

	private static final StagedVertexBuffer STAGED = new StagedVertexBuffer(() -> StarClient.ID + " boxes", RenderType.SMALL_BUFFER_SIZE);
	private static final Vector4f COLOR_MODULATOR = new Vector4f(1f, 1f, 1f, 1f);
	private static final Vector3f MODEL_OFFSET = new Vector3f();
	private static final Matrix4f TEXTURE_MATRIX = new Matrix4f();

	private static final Map<EntityType<?>, String> TYPE_IDS = new HashMap<>();

	private static volatile List<Box> boxes = List.of();
	private static volatile List<Line> lines = List.of();

	private StarRenderer() { }

	public static void init() {
		LevelRenderEvents.END_EXTRACTION.register(StarRenderer::extract);
		LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(StarRenderer::render);
	}

	// ---- extraction ----

	private static void extract(LevelExtractionContext context) {
		Minecraft mc = Minecraft.getInstance();
		Freecam.frame(mc);
		if (mc.level == null || mc.player == null) {
			boxes = List.of();
			lines = List.of();
			return;
		}

		List<Box> out = new ArrayList<>();

		Config cfg = Config.get();
		Vec3 origin = context.levelState().cameraRenderState.pos;

		if (StarClient.espOn) {
			for (Entity e : mc.level.entitiesForRendering()) {
				int rgb = espColour(mc, cfg, e, origin);
				if (rgb < 0) continue;
				AABB bb = e.getBoundingBox();
				out.add(new Box(bb.minX, bb.minY, bb.minZ, bb.maxX, bb.maxY, bb.maxZ,
						((rgb >> 16) & 0xFF) / 255f, ((rgb >> 8) & 0xFF) / 255f, (rgb & 0xFF) / 255f, cfg.espAlpha));
			}
		}

		List<Line> strips = new ArrayList<>();
		if (StarClient.tracersOn) {
			Entity viewer = mc.getCameraEntity() != null ? mc.getCameraEntity() : mc.player;
			Vec3 look = viewer.getViewVector(1.0f);
			Vec3 from = origin.add(look.scale(0.6));
			double range = Settings.get("tracers.range");
			float alpha = Settings.getF("tracers.alpha");
			float width = Settings.getF("tracers.width");
			// Only what is switched on gets a tracer: entities need ESP, blocks need Chest ESP / Portal Finder.
			List<Target> targets = new ArrayList<>();
			if (StarClient.espOn) {
				for (Entity e : mc.level.entitiesForRendering()) {
					int rgb = espColour(mc, cfg, e, origin);
					if (rgb < 0) continue;
					targets.add(new Target(e.getBoundingBox().getCenter(), ((rgb >> 16) & 0xFF) / 255f, ((rgb >> 8) & 0xFF) / 255f, (rgb & 0xFF) / 255f));
				}
			}
			if (StarClient.chestEspOn) {
				for (ChestEsp.Hit hit : ChestEsp.hits()) targets.add(new Target(Vec3.atCenterOf(hit.pos()), hit.r(), hit.g(), hit.b()));
			}
			if (StarClient.portalFinderOn) {
				for (PortalFinder.Hit hit : PortalFinder.hits()) targets.add(new Target(Vec3.atCenterOf(hit.pos()), hit.r(), hit.g(), hit.b()));
			}
			double max = range > 0 ? range * range : Double.MAX_VALUE;
			targets.removeIf(t -> t.pos().distanceToSqr(origin) > max);
			targets.sort(Comparator.comparingDouble(t -> t.pos().distanceToSqr(origin)));
			int limit = Math.min(targets.size(), Settings.getI("tracers.max"));
			for (int i = 0; i < limit; i++) {
				Target t = targets.get(i);
				addLine(strips, origin, from, t.pos(), width, t.r(), t.g(), t.b(), alpha);
			}
		}

		if (StarClient.waypointsOn) {
			float alpha = Settings.getF("waypoints.alpha");
			double beam = Settings.get("waypoints.beam");
			for (Config.Waypoint w : Waypoints.visible(mc)) {
				float r = ((w.rgb >> 16) & 0xFF) / 255f;
				float g = ((w.rgb >> 8) & 0xFF) / 255f;
				float b = (w.rgb & 0xFF) / 255f;
				out.add(new Box(w.x, w.y, w.z, w.x + 1, w.y + 1, w.z + 1, r, g, b, alpha));
				out.add(new Box(w.x + 0.4, w.y + 1, w.z + 0.4, w.x + 0.6, w.y + 1 + beam, w.z + 0.6, r, g, b, alpha * 0.7f));
			}
		}

		if (StarClient.portalFinderOn) {
			float alpha = Settings.getF("portalfinder.alpha");
			for (PortalFinder.Hit hit : PortalFinder.hits()) {
				BlockPos p = hit.pos();
				out.add(new Box(p.getX(), p.getY(), p.getZ(), p.getX() + 1, p.getY() + 1, p.getZ() + 1, hit.r(), hit.g(), hit.b(), alpha));
			}
		}

		if (StarClient.signReaderOn && Settings.on("signreader.highlight")) {
			for (SignReader.Entry sign : SignReader.entries()) {
				BlockPos p = sign.pos();
				out.add(new Box(p.getX(), p.getY(), p.getZ(), p.getX() + 1, p.getY() + 1, p.getZ() + 1, 1f, 0.9f, 0.3f, 0.35f));
			}
		}

		if (StarClient.baseFinderOn) {
			for (BaseFinder.Hit hit : BaseFinder.hits()) {
				double x = hit.pos().getX() + 0.5;
				double y = hit.pos().getY();
				double z = hit.pos().getZ() + 0.5;
				// Tall beacon-style column so it is visible from far away.
				out.add(new Box(x - 1.5, y - 8, z - 1.5, x + 1.5, y + 40, z + 1.5, 0.7f, 0.2f, 1.0f, 0.4f));
			}
		}

		if (StarClient.chestEspOn) {
			float alpha = Settings.getF("chestesp.alpha");
			for (ChestEsp.Hit hit : ChestEsp.hits()) {
				BlockPos p = hit.pos();
				out.add(new Box(p.getX(), p.getY(), p.getZ(), p.getX() + 1, p.getY() + 1, p.getZ() + 1, hit.r(), hit.g(), hit.b(), alpha));
			}
		}

		if (StarClient.trajectoriesOn && !Freecam.isOn()) {
			Trajectories.Path path = Trajectories.compute(mc);
			if (path != null) {
				final double s = 0.04;
				if (Settings.on("trajectories.path")) {
					for (Vec3 p : path.points()) {
						out.add(new Box(p.x - s, p.y - s, p.z - s, p.x + s, p.y + s, p.z + s, 1f, 1f, 1f, 0.8f));
					}
				}
				Vec3 l = path.landing();
				if (l != null && Settings.on("trajectories.landing")) {
					final double h = 0.25;
					out.add(new Box(l.x - h, l.y - h, l.z - h, l.x + h, l.y + h, l.z + h, 1f, 0.2f, 0.2f, 0.7f));
				}
			}
		}

		boxes = out;
		lines = strips;
	}

	/** Colour (RGB) ESP would draw {@code e} in, or -1 when ESP filters it out. Shared by the ESP boxes and Tracers. */
	private static int espColour(Minecraft mc, Config cfg, Entity e, Vec3 origin) {
		if (e == Freecam.camera()) return -1;
		if (e == mc.player && !Freecam.isOn()) return -1;

		int cat = categoryOf(e);
		if (cat < 0) return -1;
		if ((cat == 1 || cat == 2) && cfg.espMobMode != 0) {
			boolean listed = cfg.hasMob(TYPE_IDS.computeIfAbsent(e.getType(), t -> BuiltInRegistries.ENTITY_TYPE.getKey(t).toString()));
			// "Only selected" shows listed mobs whatever their category toggle says.
			if (cfg.espMobMode == 1 ? !listed : listed || !cfg.espEnabled[cat]) return -1;
		} else if (!cfg.espEnabled[cat]) {
			return -1;
		}
		if (cfg.espMaxDistance > 0 && e.distanceToSqr(origin) > cfg.espMaxDistance * (double) cfg.espMaxDistance) return -1;
		return cfg.espColor[cat];
	}

	private static void addLine(List<Line> out, Vec3 camera, Vec3 a, Vec3 b, float width, float r, float g, float bl, float alpha) {
		Vec3 dir = b.subtract(a);
		Vec3 mid = a.add(b).scale(0.5);
		Vec3 side = dir.cross(mid.subtract(camera));
		double len = side.length();
		if (len < 1e-6) return;
		// Widen with distance so far tracers stay visible.
		double half = width * (0.5 + mid.distanceTo(camera) * 0.02) / len;
		out.add(new Line(a.x, a.y, a.z, b.x, b.y, b.z, (float) (side.x * half), (float) (side.y * half), (float) (side.z * half), r, g, bl, alpha));
	}

	/** Index into {@link Config#ESP_NAMES}, or -1 for entities ESP ignores. */
	private static int categoryOf(Entity e) {
		if (e instanceof Player) return 0;
		if (e instanceof Enemy) return 1;
		if (e instanceof ItemEntity) return 3;
		if (e instanceof LivingEntity) return 2;
		return -1;
	}

	// ---- drawing ----

	private static void render(LevelRenderContext context) {
		List<Box> snapshot = boxes;
		List<Line> strips = lines;
		if (snapshot.isEmpty() && strips.isEmpty()) return;

		PoseStack matrices = context.poseStack();
		Vec3 camera = context.levelState().cameraRenderState.pos;

		matrices.pushPose();
		matrices.translate(-camera.x, -camera.y, -camera.z);

		VertexFormat format = FILLED_THROUGH_WALLS.getVertexFormatBinding(0);
		PrimitiveTopology topology = FILLED_THROUGH_WALLS.getPrimitiveTopology();
		StagedVertexBuffer.Draw draw = STAGED.appendDraw(format, topology,
				topology == PrimitiveTopology.QUADS ? RenderSystem.getProjectionType().vertexSorting() : null);
		VertexConsumer buffer = STAGED.getVertexBuilder(draw);

		Matrix4fc pose = matrices.last().pose();
		for (Box b : snapshot) {
			addBox(pose, buffer, (float) b.minX(), (float) b.minY(), (float) b.minZ(), (float) b.maxX(), (float) b.maxY(), (float) b.maxZ(), b.r(), b.g(), b.b(), b.a());
		}

		for (Line l : strips) addLine(pose, buffer, l);

		matrices.popPose();

		STAGED.upload();
		StagedVertexBuffer.ExecuteInfo info = STAGED.getExecuteInfo(draw);
		if (info != null) draw(Minecraft.getInstance(), info);
		STAGED.endFrame();
	}

	private static void addLine(Matrix4fc m, VertexConsumer buf, Line l) {
		float ax = (float) l.ax();
		float ay = (float) l.ay();
		float az = (float) l.az();
		float bx = (float) l.bx();
		float by = (float) l.by();
		float bz = (float) l.bz();
		float r = l.r();
		float g = l.g();
		float b = l.b();
		float a = l.a();
		// One quad per winding so the strip shows from either side whatever the pipeline culls.
		buf.addVertex(m, ax + l.px(), ay + l.py(), az + l.pz()).setColor(r, g, b, a);
		buf.addVertex(m, ax - l.px(), ay - l.py(), az - l.pz()).setColor(r, g, b, a);
		buf.addVertex(m, bx - l.px(), by - l.py(), bz - l.pz()).setColor(r, g, b, a);
		buf.addVertex(m, bx + l.px(), by + l.py(), bz + l.pz()).setColor(r, g, b, a);
		buf.addVertex(m, bx + l.px(), by + l.py(), bz + l.pz()).setColor(r, g, b, a);
		buf.addVertex(m, bx - l.px(), by - l.py(), bz - l.pz()).setColor(r, g, b, a);
		buf.addVertex(m, ax - l.px(), ay - l.py(), az - l.pz()).setColor(r, g, b, a);
		buf.addVertex(m, ax + l.px(), ay + l.py(), az + l.pz()).setColor(r, g, b, a);
	}

	private static void addBox(Matrix4fc m, VertexConsumer buf, float x0, float y0, float z0, float x1, float y1, float z1, float r, float g, float b, float a) {
		// Front
		buf.addVertex(m, x0, y0, z1).setColor(r, g, b, a);
		buf.addVertex(m, x1, y0, z1).setColor(r, g, b, a);
		buf.addVertex(m, x1, y1, z1).setColor(r, g, b, a);
		buf.addVertex(m, x0, y1, z1).setColor(r, g, b, a);
		// Back
		buf.addVertex(m, x1, y0, z0).setColor(r, g, b, a);
		buf.addVertex(m, x0, y0, z0).setColor(r, g, b, a);
		buf.addVertex(m, x0, y1, z0).setColor(r, g, b, a);
		buf.addVertex(m, x1, y1, z0).setColor(r, g, b, a);
		// Left
		buf.addVertex(m, x0, y0, z0).setColor(r, g, b, a);
		buf.addVertex(m, x0, y0, z1).setColor(r, g, b, a);
		buf.addVertex(m, x0, y1, z1).setColor(r, g, b, a);
		buf.addVertex(m, x0, y1, z0).setColor(r, g, b, a);
		// Right
		buf.addVertex(m, x1, y0, z1).setColor(r, g, b, a);
		buf.addVertex(m, x1, y0, z0).setColor(r, g, b, a);
		buf.addVertex(m, x1, y1, z0).setColor(r, g, b, a);
		buf.addVertex(m, x1, y1, z1).setColor(r, g, b, a);
		// Top
		buf.addVertex(m, x0, y1, z1).setColor(r, g, b, a);
		buf.addVertex(m, x1, y1, z1).setColor(r, g, b, a);
		buf.addVertex(m, x1, y1, z0).setColor(r, g, b, a);
		buf.addVertex(m, x0, y1, z0).setColor(r, g, b, a);
		// Bottom
		buf.addVertex(m, x0, y0, z0).setColor(r, g, b, a);
		buf.addVertex(m, x1, y0, z0).setColor(r, g, b, a);
		buf.addVertex(m, x1, y0, z1).setColor(r, g, b, a);
		buf.addVertex(m, x0, y0, z1).setColor(r, g, b, a);
	}

	private static void draw(Minecraft client, StagedVertexBuffer.ExecuteInfo info) {
		GpuBufferSlice dynamicTransforms = RenderSystem.getDynamicUniforms()
				.writeTransform(RenderSystem.getModelViewMatrixCopy(), COLOR_MODULATOR, MODEL_OFFSET, TEXTURE_MATRIX);

		RenderTarget target = client.gameRenderer.mainRenderTarget();
		GpuTextureView colour = target.getColorTextureView();
		if (colour == null) return;

		try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
				() -> StarClient.ID + " boxes", colour, Optional.empty(), target.getDepthTextureView(), OptionalDouble.empty())) {
			pass.setPipeline(FILLED_THROUGH_WALLS);
			RenderSystem.bindDefaultUniforms(pass);
			pass.setUniform("DynamicTransforms", dynamicTransforms);
			pass.setVertexBuffer(0, info.vertexBuffer().slice());
			pass.setIndexBuffer(info.indexBuffer(), info.indexType());
			pass.drawIndexed(info.indexCount(), 1, info.firstIndex(), info.baseVertex(), 0);
		}
	}

	public static void close() {
		STAGED.close();
	}
}
