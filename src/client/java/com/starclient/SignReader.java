package com.starclient;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.SignBlockEntity;
import net.minecraft.world.level.block.entity.SignText;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;

/** Sign Reader: nearest signs with text, listed top-left with distance and direction; {@link StarRenderer} highlights them. */
public final class SignReader {
	public record Entry(BlockPos pos, double distance, String text) { }

	private static final Identifier ID = Identifier.fromNamespaceAndPath(StarClient.ID, "signreader");
	private static final int INTERVAL_TICKS = 10;
	private static final int MAX_CHARS = 48;
	private static final int MARGIN = 4;
	private static final int LINE = 10;

	private static volatile List<Entry> entries = List.of();
	private static int ticks;

	private SignReader() { }

	public static void init() {
		HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, ID, SignReader::render);
	}

	public static List<Entry> entries() {
		return entries;
	}

	public static void clear() {
		entries = List.of();
		ticks = 0;
	}

	public static void tick(Minecraft mc) {
		if (++ticks % INTERVAL_TICKS != 0) return;

		double range = Settings.get("signreader.range");
		int radius = Math.min(mc.options.getEffectiveRenderDistance(), (int) Math.ceil(range / 16.0));
		ChunkPos center = mc.player.chunkPosition();
		var pos = mc.player.position();
		List<Entry> out = new ArrayList<>();
		for (int dx = -radius; dx <= radius; dx++) {
			for (int dz = -radius; dz <= radius; dz++) {
				ChunkAccess chunk = mc.level.getChunk(center.x() + dx, center.z() + dz, ChunkStatus.FULL, false);
				if (!(chunk instanceof LevelChunk lc)) continue;
				for (BlockEntity be : lc.getBlockEntities().values()) {
					if (!(be instanceof SignBlockEntity sign)) continue;
					double d = Math.sqrt(be.getBlockPos().distToCenterSqr(pos));
					if (d > range) continue;
					String text = text(sign);
					if (!text.isEmpty()) out.add(new Entry(be.getBlockPos(), d, text));
				}
			}
		}
		out.sort(Comparator.comparingDouble(Entry::distance));
		int max = Settings.getI("signreader.max");
		entries = out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
	}

	private static String text(SignBlockEntity sign) {
		StringBuilder sb = new StringBuilder();
		for (SignText side : new SignText[] {sign.getFrontText(), sign.getBackText()}) {
			StringBuilder line = new StringBuilder();
			for (Component c : side.getMessages(false)) {
				String s = c.getString().trim();
				if (s.isEmpty()) continue;
				if (line.length() > 0) line.append(" / ");
				line.append(s);
			}
			if (line.length() == 0) continue;
			if (sb.length() > 0) sb.append(" | ");
			sb.append(line);
		}
		return sb.length() > MAX_CHARS ? sb.substring(0, MAX_CHARS - 1) + "…" : sb.toString();
	}

	private static void render(GuiGraphicsExtractor graphics, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (!StarClient.signReaderOn || mc.player == null || mc.level == null || mc.options.hideGui) return;
		int y = MARGIN;
		for (Entry e : entries) {
			Component line = Component.literal(Waypoints.arrow(mc, e.pos().getX() + 0.5, e.pos().getZ() + 0.5) + " " + Math.round(e.distance()) + "m  " + e.text());
			int w = mc.font.width(line);
			graphics.fill(MARGIN - 2, y - 1, MARGIN + w + 2, y + LINE - 1, 0x90101216);
			graphics.text(mc.font, line, MARGIN, y, 0xFFFFE066, true);
			y += LINE;
		}
	}
}
