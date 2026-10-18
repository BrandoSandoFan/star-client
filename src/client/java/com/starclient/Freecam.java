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
	private static final double TICK = 1.0 / 20.0;

	private static ArmorStand cam;
	private static boolean smartCull = true;
	private static double velX, velY, velZ;

	private Freecam() { }

	/**
	 * Camera entity whose view rotation is the player's current rotation. Mouse look turns the real
	 * player every frame, and the camera reads this when it is set up, so turning has no lag or steps.
	 */
	private static final class Camera extends ArmorStand {
		private final Player player;

		Camera(Player player) {
			super(player.level(), player.getX(), player.getEyeY(), player.getZ());
			this.player = player;
		}

		@Override
		public float getViewYRot(float partialTick) {
			return player.getYRot();
		}

		@Override
		public float getViewXRot(float partialTick) {
			return player.getXRot();
		}
	}

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
		cam = new Camera(p);
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

	/**
	 * Per-tick move. The camera position is interpolated between the previous and current tick
	 * position with the frame's partial tick, like any entity, so motion is even at every frame rate.
	 */
	public static void tick(Minecraft mc) {
		if (cam == null) return;
		if (mc.getCameraEntity() != cam) { // something else took the camera
			cam = null;
			return;
		}

		float yaw = mc.player.getYRot();
		cam.setYRot(yaw);
		cam.setXRot(mc.player.getXRot());
		cam.setOldPosAndRot();

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
		double k = 1.0 - Math.exp(-TICK * ACCEL);
		velX += (tx - velX) * k;
		velY += (ty - velY) * k;
		velZ += (tz - velZ) * k;
		if (len == 0 && Math.abs(velX) + Math.abs(velY) + Math.abs(velZ) < 0.01) velX = velY = velZ = 0;

		cam.setPos(cam.getX() + velX * TICK, cam.getY() + velY * TICK, cam.getZ() + velZ * TICK);
	}
}
