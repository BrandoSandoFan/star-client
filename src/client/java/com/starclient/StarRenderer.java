package com.starclient;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalInt;

import com.mojang.blaze3d.buffers.GpuBuffer;
import com.mojang.blaze3d.buffers.GpuBufferSlice;
import com.mojang.blaze3d.pipeline.RenderPipeline;
import com.mojang.blaze3d.systems.CommandEncoder;
import com.mojang.blaze3d.systems.RenderPass;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.BufferBuilder;
import com.mojang.blaze3d.vertex.ByteBufferBuilder;
import com.mojang.blaze3d.vertex.MeshData;
import com.mojang.blaze3d.vertex.PoseStack;
import com.mojang.blaze3d.vertex.VertexFormat;
import org.joml.Matrix4f;
import org.joml.Matrix4fc;
import org.joml.Vector3f;
import org.joml.Vector4f;
import org.lwjgl.system.MemoryUtil;

import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.MappableRingBuffer;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.rendertype.RenderType;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.Entity;
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

	private static final RenderPipeline FILLED_THROUGH_WALLS = RenderPipelines.register(
			RenderPipeline.builder(RenderPipelines.DEBUG_FILLED_SNIPPET)
					.withLocation(Identifier.fromNamespaceAndPath(StarClient.ID, "pipeline/filled_through_walls"))
					.withDepthStencilState(Optional.empty())
					.build());

	private static final ByteBufferBuilder ALLOCATOR = new ByteBufferBuilder(RenderType.SMALL_BUFFER_SIZE);
	private static final Vector4f COLOR_MODULATOR = new Vector4f(1f, 1f, 1f, 1f);
	private static final Vector3f MODEL_OFFSET = new Vector3f();
	private static final Matrix4f TEXTURE_MATRIX = new Matrix4f();

	private static volatile List<Box> boxes = List.of();
	private static BufferBuilder buffer;
	private static MappableRingBuffer vertexBuffer;

	private StarRenderer() { }

	public static void init() {
		LevelRenderEvents.END_EXTRACTION.register(StarRenderer::extract);
		LevelRenderEvents.AFTER_TRANSLUCENT_TERRAIN.register(StarRenderer::render);
	}

	// ---- extraction ----

	private static void extract(LevelExtractionContext context) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.level == null || mc.player == null) {
			boxes = List.of();
			return;
		}

		List<Box> out = new ArrayList<>();

		if (StarClient.espOn) {
			for (Entity e : mc.level.entitiesForRendering()) {
				if (e == Freecam.camera()) continue;
				if (e == mc.player && !Freecam.isOn()) continue;

				float[] c = colorFor(e);
				if (c == null) continue;

				AABB bb = e.getBoundingBox();
				out.add(new Box(bb.minX, bb.minY, bb.minZ, bb.maxX, bb.maxY, bb.maxZ, c[0], c[1], c[2], 0.35f));
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

		boxes = out;
	}

	private static float[] colorFor(Entity e) {
		if (e instanceof Player) return new float[] {1.0f, 0.15f, 0.15f};
		if (e instanceof Enemy) return new float[] {1.0f, 0.6f, 0.0f};
		if (e instanceof ItemEntity) return new float[] {0.2f, 0.9f, 1.0f};
		if (e instanceof LivingEntity) return new float[] {0.3f, 1.0f, 0.3f};
		return null;
	}

	// ---- drawing ----

	private static void render(LevelRenderContext context) {
		List<Box> snapshot = boxes;
		if (snapshot.isEmpty()) return;

		PoseStack matrices = context.poseStack();
		Vec3 camera = context.levelState().cameraRenderState.pos;

		matrices.pushPose();
		matrices.translate(-camera.x, -camera.y, -camera.z);

		if (buffer == null) {
			buffer = new BufferBuilder(ALLOCATOR, FILLED_THROUGH_WALLS.getVertexFormatMode(), FILLED_THROUGH_WALLS.getVertexFormat());
		}

		Matrix4fc pose = matrices.last().pose();
		for (Box b : snapshot) {
			addBox(pose, buffer, (float) b.minX(), (float) b.minY(), (float) b.minZ(), (float) b.maxX(), (float) b.maxY(), (float) b.maxZ(), b.r(), b.g(), b.b(), b.a());
		}

		matrices.popPose();

		draw(Minecraft.getInstance());
	}

	private static void addBox(Matrix4fc m, BufferBuilder buf, float x0, float y0, float z0, float x1, float y1, float z1, float r, float g, float b, float a) {
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

	private static void draw(Minecraft client) {
		RenderPipeline pipeline = FILLED_THROUGH_WALLS;

		MeshData built = buffer.buildOrThrow();
		MeshData.DrawState state = built.drawState();
		VertexFormat format = state.format();

		GpuBuffer vertices = upload(state, format, built);

		GpuBuffer indices;
		VertexFormat.IndexType indexType;
		built.sortQuads(ALLOCATOR, RenderSystem.getProjectionType().vertexSorting());
		indices = pipeline.getVertexFormat().uploadImmediateIndexBuffer(built.indexBuffer());
		indexType = built.drawState().indexType();

		GpuBufferSlice dynamicTransforms = RenderSystem.getDynamicUniforms()
				.writeTransform(RenderSystem.getModelViewMatrix(), COLOR_MODULATOR, MODEL_OFFSET, TEXTURE_MATRIX);

		try (RenderPass pass = RenderSystem.getDevice().createCommandEncoder().createRenderPass(
				() -> StarClient.ID + " boxes",
				client.getMainRenderTarget().getColorTextureView(), OptionalInt.empty(),
				client.getMainRenderTarget().getDepthTextureView(), OptionalDouble.empty())) {
			pass.setPipeline(pipeline);
			RenderSystem.bindDefaultUniforms(pass);
			pass.setUniform("DynamicTransforms", dynamicTransforms);
			pass.setVertexBuffer(0, vertices);
			pass.setIndexBuffer(indices, indexType);
			pass.drawIndexed(0, 0, state.indexCount(), 1);
		}

		built.close();
		vertexBuffer.rotate();
		buffer = null;
	}

	private static GpuBuffer upload(MeshData.DrawState state, VertexFormat format, MeshData built) {
		int size = state.vertexCount() * format.getVertexSize();

		if (vertexBuffer == null || vertexBuffer.size() < size) {
			if (vertexBuffer != null) vertexBuffer.close();
			vertexBuffer = new MappableRingBuffer(() -> StarClient.ID + " vertices", GpuBuffer.USAGE_VERTEX | GpuBuffer.USAGE_MAP_WRITE, size);
		}

		CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
		try (GpuBuffer.MappedView view = encoder.mapBuffer(vertexBuffer.currentBuffer().slice(0, built.vertexBuffer().remaining()), false, true)) {
			MemoryUtil.memCopy(built.vertexBuffer(), view.data());
		}
		return vertexBuffer.currentBuffer();
	}

	public static void close() {
		ALLOCATOR.close();
		if (vertexBuffer != null) {
			vertexBuffer.close();
			vertexBuffer = null;
		}
	}
}
