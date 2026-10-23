package com.starclient;

import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageTypeSpecifier;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.metadata.IIOMetadataNode;
import javax.imageio.stream.ImageOutputStream;
import javax.imageio.stream.MemoryCacheImageOutputStream;

import com.mojang.blaze3d.pipeline.RenderTarget;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.commands.CommandEncoder;
import com.mojang.renderpearl.api.commands.RenderPass;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import com.mojang.renderpearl.api.textures.GpuTextureView;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.network.chat.Component;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Clipper: keeps the last {@code clipper.seconds} of gameplay as small PNG frames in memory and writes them to
 * {@code clips/<time>/} when the module key is pressed. Frames are taken every {@code clipper.interval} ticks
 * and scaled to {@code clipper.width}. If ffmpeg is on the PATH the frames are encoded to {@code clip.mp4},
 * otherwise to a looping {@code clip.gif} written in pure Java.
 */
public final class Clipper {
	private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "starclient-clipper");
		t.setDaemon(true);
		return t;
	});
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

	/** Deflate level about 2 (the JDK PNG writer maps quality q to level 9 * (1 - q)): several times faster than the default for a slightly bigger frame. */
	private static final float PNG_QUALITY = 0.75f;
	/** Frames queued for the worker. Capture is skipped while this many wait, so a slow encoder never piles up work. */
	private static final int MAX_PENDING = 3;
	private static final AtomicInteger PENDING = new AtomicInteger();

	private static final ArrayDeque<byte[]> frames = new ArrayDeque<>();
	private static int ticks;
	private static boolean capturing;

	// Render thread: the small texture the frame is scaled into on the GPU and the mappable buffer it is read back through.
	// Kept between captures and recreated only when the window or the width setting changes.
	private static GpuTexture small;
	private static GpuTextureView smallView;
	private static GpuBuffer readBuffer;
	private static int width;
	private static int height;

	// Worker thread only.
	private static BufferedImage image;
	private static ImageWriter png;

	private Clipper() { }

	/**
	 * Same path as vanilla's Tracy frame capture: a linear GPU blit into a small texture, then an async copy to a
	 * buffer. {@code Screenshot.takeScreenshot} instead reads the full-resolution frame into a fresh buffer and
	 * converts it pixel by pixel on the render thread, which stalled every capture.
	 */
	public static void tick(Minecraft mc) {
		if (!Settings.on("clipper.record")) {
			if (!capturing && small != null) close();
			return;
		}
		if (capturing || ++ticks < Settings.getI("clipper.interval")) return;
		ticks = 0;
		if (PENDING.get() >= MAX_PENDING) return;
		RenderTarget target = mc.gameRenderer.mainRenderTarget();
		if (target.getColorTexture() == null || target.getColorTextureView() == null) return;

		capturing = true;
		try {
			resize(target.width, target.height, Settings.getI("clipper.width"));
			CommandEncoder encoder = RenderSystem.getDevice().createCommandEncoder();
			try (RenderPass pass = encoder.createRenderPass(() -> StarClient.ID + " clipper blit", smallView, Optional.empty())) {
				RenderSystem.bindDefaultUniforms(pass);
				pass.setPipeline(RenderSystem.getCompiledPipeline(RenderPipelines.TRACY_BLIT));
				pass.setUniform("InSampler", target.getColorTextureView(), RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR));
				pass.draw(3, 1, 0, 0);
			}
			GpuBuffer buffer = readBuffer;
			int w = width;
			int h = height;
			encoder.copyTextureToBuffer(small, buffer, 0, () -> readBack(buffer, w, h), 0);
		} catch (RuntimeException e) {
			capturing = false;
			StarClient.LOGGER.warn("Clipper: frame capture failed", e);
		}
	}

	/** Runs on the render thread once the GPU copy is done: one bulk copy out of the mapped buffer, the rest on the worker. */
	private static void readBack(GpuBuffer buffer, int w, int h) {
		try {
			byte[] rgba = new byte[w * h * 4];
			try (var view = buffer.map(true, false)) {
				view.data().get(0, rgba);
			}
			PENDING.incrementAndGet();
			WORKER.execute(() -> {
				try {
					encode(rgba, w, h);
				} finally {
					PENDING.decrementAndGet();
				}
			});
		} catch (RuntimeException e) {
			StarClient.LOGGER.warn("Clipper: frame read-back failed", e);
		} finally {
			capturing = false;
		}
	}

	/** Even width and height (ffmpeg's yuv420p needs them), at most {@code targetWidth} wide, same aspect as the window. */
	private static void resize(int srcW, int srcH, int targetWidth) {
		int w = Math.max(2, Math.min(targetWidth, srcW) & ~1);
		int h = Math.max(2, (int) ((long) srcH * w / srcW) & ~1);
		if (small != null && w == width && h == height) return;
		close();
		var device = RenderSystem.getDevice();
		small = device.createTexture(StarClient.ID + " clipper", GpuTexture.USAGE_COPY_SRC | GpuTexture.USAGE_RENDER_ATTACHMENT, GpuFormat.RGBA8_UNORM, w, h, 1, 1);
		smallView = device.createTextureView(small);
		readBuffer = device.createBuffer(() -> StarClient.ID + " clipper read-back", GpuBuffer.USAGE_MAP_READ | GpuBuffer.USAGE_COPY_DST, (long) w * h * 4);
		width = w;
		height = h;
	}

	/** Frees the GPU texture and buffer. Only called while no copy is in flight. */
	private static void close() {
		if (smallView != null) smallView.close();
		if (small != null) small.close();
		if (readBuffer != null) readBuffer.close();
		smallView = null;
		small = null;
		readBuffer = null;
	}

	private static void encode(byte[] rgba, int w, int h) {
		if (image == null || image.getWidth() != w || image.getHeight() != h) image = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		int[] out = ((DataBufferInt) image.getRaster().getDataBuffer()).getData();
		// RGBA8 bytes; GPU rows run bottom-up.
		for (int y = 0; y < h; y++) {
			int src = (h - 1 - y) * w * 4;
			int dst = y * w;
			for (int x = 0; x < w; x++, src += 4) {
				out[dst + x] = (rgba[src] & 0xFF) << 16 | (rgba[src + 1] & 0xFF) << 8 | rgba[src + 2] & 0xFF;
			}
		}
		ByteArrayOutputStream bytes = new ByteArrayOutputStream(w * h);
		try {
			writePng(bytes);
		} catch (IOException | RuntimeException e) {
			return;
		}
		int max = Math.max(1, Settings.getI("clipper.seconds") * 20 / Math.max(1, Settings.getI("clipper.interval")));
		synchronized (frames) {
			frames.addLast(bytes.toByteArray());
			while (frames.size() > max) frames.pollFirst();
		}
	}

	/** Writes {@link #image} as PNG with a fast deflate level. In-memory stream: {@code ImageIO.write} may cache through a temp file. */
	private static void writePng(ByteArrayOutputStream bytes) throws IOException {
		if (png == null) png = ImageIO.getImageWritersByFormatName("png").next();
		ImageWriteParam param = png.getDefaultWriteParam();
		if (param.canWriteCompressed()) {
			param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
			String[] types = param.getCompressionTypes();
			if (types != null && types.length > 0) param.setCompressionType(types[0]);
			param.setCompressionQuality(PNG_QUALITY);
		}
		try (ImageOutputStream out = new MemoryCacheImageOutputStream(bytes)) {
			png.setOutput(out);
			png.write(null, new IIOImage(image, null, null), param);
		} finally {
			png.setOutput(null);
		}
	}

	/** Writes the buffered frames to disk on the worker thread. */
	public static void save(Minecraft mc) {
		List<byte[]> snapshot;
		synchronized (frames) {
			snapshot = new ArrayList<>(frames);
		}
		if (snapshot.isEmpty()) {
			mc.gui.hud.setOverlayMessage(Component.literal("Clipper: nothing recorded yet").withStyle(ChatFormatting.RED), false);
			return;
		}
		double fps = 20.0 / Math.max(1, Settings.getI("clipper.interval"));
		boolean ffmpeg = Settings.on("clipper.ffmpeg");
		Path dir = FabricLoader.getInstance().getGameDir().resolve("clips").resolve(LocalDateTime.now().format(STAMP));
		mc.gui.hud.setOverlayMessage(Component.literal("Clipper: saving " + snapshot.size() + " frames").withStyle(ChatFormatting.YELLOW), false);
		WORKER.execute(() -> {
			String result;
			try {
				Files.createDirectories(dir);
				for (int i = 0; i < snapshot.size(); i++) Files.write(dir.resolve(String.format("frame_%04d.png", i)), snapshot.get(i));
				String base = "clips/" + dir.getFileName();
				if (ffmpeg && encodeVideo(dir, fps)) result = base + "/clip.mp4";
				else if (encodeGif(dir, snapshot, fps)) result = base + "/clip.gif";
				else result = base;
			} catch (IOException e) {
				result = null;
			}
			String shown = result;
			mc.execute(() -> mc.gui.hud.setOverlayMessage(shown == null
					? Component.literal("Clipper: could not write the clip").withStyle(ChatFormatting.RED)
					: Component.literal("Clip saved: " + shown).withStyle(ChatFormatting.GREEN), false));
		});
	}

	/** Runs ffmpeg over the frames. True on success, in which case the frames are deleted. */
	private static boolean encodeVideo(Path dir, double fps) {
		try {
			Process proc = new ProcessBuilder("ffmpeg", "-y", "-loglevel", "error", "-framerate", String.valueOf(fps), "-i",
					dir.resolve("frame_%04d.png").toString(), "-c:v", "libx264", "-pix_fmt", "yuv420p", dir.resolve("clip.mp4").toString())
					.redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
			if (!proc.waitFor(120, TimeUnit.SECONDS) || proc.exitValue() != 0) {
				proc.destroyForcibly();
				return false;
			}
			deleteFrames(dir);
			return true;
		} catch (IOException e) {
			return false;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	private static void deleteFrames(Path dir) throws IOException {
		try (var files = Files.list(dir)) {
			for (Path f : (Iterable<Path>) files.filter(f -> f.getFileName().toString().endsWith(".png"))::iterator) Files.delete(f);
		}
	}

	/** Pure-Java fallback: writes a looping animated GIF with the JDK's GIF writer. True on success, in which case the frames are deleted. */
	private static boolean encodeGif(Path dir, List<byte[]> frames, double fps) {
		ImageWriter writer = ImageIO.getImageWritersByFormatName("gif").next();
		try (ImageOutputStream out = ImageIO.createImageOutputStream(dir.resolve("clip.gif").toFile())) {
			writer.setOutput(out);
			writer.prepareWriteSequence(null);
			String delay = String.valueOf(Math.max(2, Math.round(100 / fps)));
			for (int i = 0; i < frames.size(); i++) {
				BufferedImage img = ImageIO.read(new ByteArrayInputStream(frames.get(i)));
				ImageWriteParam param = writer.getDefaultWriteParam();
				IIOMetadata meta = writer.getDefaultImageMetadata(ImageTypeSpecifier.createFromRenderedImage(img), param);
				String format = meta.getNativeMetadataFormatName();
				IIOMetadataNode root = (IIOMetadataNode) meta.getAsTree(format);
				IIOMetadataNode gce = node(root, "GraphicControlExtension");
				gce.setAttribute("disposalMethod", "none");
				gce.setAttribute("userInputFlag", "FALSE");
				gce.setAttribute("transparentColorFlag", "FALSE");
				gce.setAttribute("delayTime", delay);
				gce.setAttribute("transparentColorIndex", "0");
				if (i == 0) {
					IIOMetadataNode loop = new IIOMetadataNode("ApplicationExtension");
					loop.setAttribute("applicationID", "NETSCAPE");
					loop.setAttribute("authenticationCode", "2.0");
					loop.setUserObject(new byte[] {1, 0, 0});
					node(root, "ApplicationExtensions").appendChild(loop);
				}
				meta.setFromTree(format, root);
				writer.writeToSequence(new IIOImage(img, null, meta), param);
			}
			writer.endWriteSequence();
			out.flush();
			deleteFrames(dir);
			return true;
		} catch (IOException | RuntimeException e) {
			return false;
		} finally {
			writer.dispose();
		}
	}

	private static IIOMetadataNode node(IIOMetadataNode root, String name) {
		for (int i = 0; i < root.getLength(); i++) {
			if (root.item(i).getNodeName().equalsIgnoreCase(name)) return (IIOMetadataNode) root.item(i);
		}
		IIOMetadataNode created = new IIOMetadataNode(name);
		root.appendChild(created);
		return created;
	}

	public static void reset() {
		synchronized (frames) {
			frames.clear();
		}
		ticks = 0;
	}
}
