package com.starclient;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.imageio.ImageIO;

import com.mojang.blaze3d.platform.NativeImage;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.network.chat.Component;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Clipper: keeps the last {@code clipper.seconds} of gameplay as small PNG frames in memory and writes them to
 * {@code clips/<time>/} when the module key is pressed. Frames are taken every {@code clipper.interval} ticks
 * and scaled to {@code clipper.width}. If ffmpeg is on the PATH the frames are encoded to {@code clip.mp4}.
 */
public final class Clipper {
	private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "starclient-clipper");
		t.setDaemon(true);
		return t;
	});
	private static final DateTimeFormatter STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");

	private static final ArrayDeque<byte[]> frames = new ArrayDeque<>();
	private static int ticks;
	private static boolean capturing;

	private Clipper() { }

	public static void tick(Minecraft mc) {
		if (!Settings.on("clipper.record") || capturing || ++ticks < Settings.getI("clipper.interval")) return;
		ticks = 0;
		capturing = true;
		Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), image -> {
			try {
				int[] pixels = scale(image, Settings.getI("clipper.width"));
				int w = pixels[0];
				int h = pixels[1];
				WORKER.execute(() -> encode(pixels, w, h));
			} finally {
				image.close();
				capturing = false;
			}
		});
	}

	/** Nearest-neighbour downscale to an even width and height. Element 0 and 1 of the result are width and height, the pixels follow. */
	private static int[] scale(NativeImage image, int targetWidth) {
		int w = Math.max(2, Math.min(targetWidth, image.getWidth()) & ~1);
		int h = Math.max(2, (int) ((long) image.getHeight() * w / image.getWidth()) & ~1);
		int[] out = new int[2 + w * h];
		out[0] = w;
		out[1] = h;
		for (int y = 0; y < h; y++) {
			int sy = y * image.getHeight() / h;
			for (int x = 0; x < w; x++) out[2 + y * w + x] = image.getPixel(x * image.getWidth() / w, sy);
		}
		return out;
	}

	private static void encode(int[] pixels, int w, int h) {
		BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
		img.setRGB(0, 0, w, h, pixels, 2, w);
		ByteArrayOutputStream bytes = new ByteArrayOutputStream(w * h / 4);
		try {
			ImageIO.write(img, "png", bytes);
		} catch (IOException e) {
			return;
		}
		int max = Math.max(1, Settings.getI("clipper.seconds") * 20 / Math.max(1, Settings.getI("clipper.interval")));
		synchronized (frames) {
			frames.addLast(bytes.toByteArray());
			while (frames.size() > max) frames.pollFirst();
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
				result = ffmpeg && encodeVideo(dir, fps) ? "clips/" + dir.getFileName() + "/clip.mp4" : "clips/" + dir.getFileName();
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
			try (var files = Files.list(dir)) {
				for (Path f : (Iterable<Path>) files.filter(f -> f.getFileName().toString().endsWith(".png"))::iterator) Files.delete(f);
			}
			return true;
		} catch (IOException e) {
			return false;
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			return false;
		}
	}

	public static void reset() {
		synchronized (frames) {
			frames.clear();
		}
		ticks = 0;
	}
}
