package com.starclient;

import net.minecraft.client.Minecraft;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.player.Player;

/**
 * Freecam via a client-only camera entity. While the camera entity is not the
 * local player, vanilla stops applying movement input and stops sending
 * movement packets, so the real player stays put (same mechanism as spectating).
 */
public final class Freecam {
	private static final double ACCEL = 14.0; // velocity easing rate, 1/s

	private static ArmorStand cam;
	private static boolean smartCull = true;
	private static long lastNanos = System.nanoTime();
	private static double smoothDt = 1.0 / 60.0;
	private static double velX, velY, velZ;

	private Freecam() { }

	public static boolean isOn() {
		return cam != null;
	}

	public static ArmorStand camera() {
		return cam;
	}

	public static void toggle(Minecraft mc) {
		if (cam == null) enable(mc); else disable(mc);
	}

	private static void enable(Minecraft mc) {
		Player p = mc.player;
		cam = new ArmorStand(mc.level, p.getX(), p.getEyeY(), p.getZ());
		cam.noPhysics = true;
		cam.setInvisible(true);
		cam.setPos(p.getX(), p.getEyeY() - cam.getEyeHeight(), p.getZ());
		cam.setYRot(p.getYRot());
		cam.setXRot(p.getXRot());
		cam.setOldPosAndRot();
		mc.setCameraEntity(cam);
		smartCull = mc.smartCull;
		velX = velY = velZ = 0;
		mc.smartCull = false; // keep chunks visible while the camera is inside terrain
	}

	private static void disable(Minecraft mc) {
		if (mc.player != null) mc.setCameraEntity(mc.player);
		mc.smartCull = smartCull;
		cam = null;
	}

	/** Drop state without touching the camera (world unload, disconnect). */
	public static void reset() {
		cam = null;
	}

	/** Per-tick safety check: drop state if something else took the camera. */
	public static void tick(Minecraft mc) {
		if (cam != null && mc.getCameraEntity() != cam) cam = null;
	}

	/**
	 * Per-frame update (called from render extraction). Moving by frame time and
	 * resetting the old position/rotation each frame avoids tick-rate stutter
	 * because there is nothing left for the renderer to interpolate.
	 */
	public static void frame(Minecraft mc) {
		long now = System.nanoTime();
		double rawDt = Math.min((now - lastNanos) / 1.0e9, 0.1);
		lastNanos = now;
		if (cam == null || mc.player == null || mc.getCameraEntity() != cam) return;

		// Frame times vary; low-pass them so spikes do not become visible position steps.
		smoothDt += (rawDt - smoothDt) * 0.2;
		double dt = smoothDt;

		float yaw = mc.player.getYRot();
		cam.setYRot(yaw);
		cam.setXRot(mc.player.getXRot());

		double fwd = (mc.options.keyUp.isDown() ? 1 : 0) - (mc.options.keyDown.isDown() ? 1 : 0);
		double side = (mc.options.keyLeft.isDown() ? 1 : 0) - (mc.options.keyRight.isDown() ? 1 : 0);
		double vert = (mc.options.keyJump.isDown() ? 1 : 0) - (mc.options.keyShift.isDown() ? 1 : 0);

		double rad = Math.toRadians(yaw);
		double dx = -Math.sin(rad) * fwd + Math.cos(rad) * side;
		double dz = Math.cos(rad) * fwd + Math.sin(rad) * side;

		double len = Math.sqrt(dx * dx + vert * vert + dz * dz);
		double speed = Settings.get("freecam.speed") * (mc.options.keySprint.isDown() ? Settings.get("freecam.sprint") : 1.0);
		double tx = 0, ty = 0, tz = 0;
		if (len > 0) {
			tx = dx / len * speed;
			ty = vert / len * speed;
			tz = dz / len * speed;
		}

		// Ease velocity toward the target so starting and stopping are not abrupt.
		double k = 1.0 - Math.exp(-dt * ACCEL);
		velX += (tx - velX) * k;
		velY += (ty - velY) * k;
		velZ += (tz - velZ) * k;
		if (len == 0 && Math.abs(velX) + Math.abs(velY) + Math.abs(velZ) < 0.01) velX = velY = velZ = 0;

		cam.setPos(cam.getX() + velX * dt, cam.getY() + velY * dt, cam.getZ() + velZ * dt);
		cam.setOldPosAndRot();
	}
}
