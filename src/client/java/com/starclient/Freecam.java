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
	private static final double SPEED = 0.5;
	private static final double SPRINT_MULT = 2.5;

	private static ArmorStand cam;

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
	}

	private static void disable(Minecraft mc) {
		if (mc.player != null) mc.setCameraEntity(mc.player);
		cam = null;
	}

	/** Drop state without touching the camera (world unload, disconnect). */
	public static void reset() {
		cam = null;
	}

	public static void tick(Minecraft mc) {
		if (cam == null) return;
		if (mc.getCameraEntity() != cam) { // something else took the camera
			cam = null;
			return;
		}

		cam.setOldPosAndRot();

		// Mouse look still turns the real player, so mirror its rotation.
		float yaw = mc.player.getYRot();
		cam.setYRot(yaw);
		cam.setXRot(mc.player.getXRot());

		double fwd = (mc.options.keyUp.isDown() ? 1 : 0) - (mc.options.keyDown.isDown() ? 1 : 0);
		double side = (mc.options.keyLeft.isDown() ? 1 : 0) - (mc.options.keyRight.isDown() ? 1 : 0);
		double vert = (mc.options.keyJump.isDown() ? 1 : 0) - (mc.options.keyShift.isDown() ? 1 : 0);

		double rad = Math.toRadians(yaw);
		double dx = -Math.sin(rad) * fwd + Math.cos(rad) * side;
		double dz = Math.cos(rad) * fwd + Math.sin(rad) * side;
		double dy = vert;

		double len = Math.sqrt(dx * dx + dy * dy + dz * dz);
		if (len > 0) {
			double speed = SPEED * (mc.options.keySprint.isDown() ? SPRINT_MULT : 1.0);
			cam.setPos(cam.getX() + dx / len * speed, cam.getY() + dy / len * speed, cam.getZ() + dz / len * speed);
		}
	}
}
