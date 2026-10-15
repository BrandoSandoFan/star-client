package com.starclient;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Trajectories: simulates the flight of whatever throwable or ranged weapon you are holding. */
public final class Trajectories {

	/** Points along the flight path; {@code landing} is the block impact point, or null if nothing was hit. */
	public record Path(List<Vec3> points, Vec3 landing) { }

	private Trajectories() { }

	public static Path compute(Minecraft mc) {
		double[] p = params(mc, mc.player.getMainHandItem());
		if (p == null) p = params(mc, mc.player.getOffhandItem());
		if (p == null) return null;
		double speed = p[0];
		double gravity = p[1];
		double drag = p[2];

		Vec3 pos = mc.player.getEyePosition().subtract(0, 0.1, 0);
		Vec3 vel = mc.player.getViewVector(1.0f).scale(speed);

		int maxSteps = Settings.getI("trajectories.steps");
		List<Vec3> points = new ArrayList<>();
		Vec3 landing = null;
		for (int i = 0; i < maxSteps; i++) {
			Vec3 next = pos.add(vel);
			HitResult hit = mc.level.clip(new ClipContext(pos, next, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, mc.player));
			if (hit.getType() != HitResult.Type.MISS) {
				landing = hit.getLocation();
				break;
			}
			pos = next;
			points.add(pos);
			vel = vel.scale(drag).subtract(0, gravity, 0);
			if (pos.y < mc.level.getMinY() - 16) break;
		}
		return new Path(points, landing);
	}

	/** {speed, gravity, drag} in blocks/tick terms for the held item, or null if it is not a projectile launcher. */
	private static double[] params(Minecraft mc, ItemStack s) {
		if (s.is(Items.BOW)) {
			// Arrows leave at 3 * draw power; power ramps up over the first second.
			double f = mc.player.isUsingItem() && mc.player.getUseItem() == s ? mc.player.getTicksUsingItem() / 20.0 : 1.0;
			f = Math.min(1.0, (f * f + f * 2.0) / 3.0);
			return new double[] {3.0 * f, 0.05, 0.99};
		}
		if (s.is(Items.CROSSBOW)) return new double[] {3.15, 0.05, 0.99};
		if (s.is(Items.TRIDENT)) return new double[] {2.5, 0.05, 0.99};
		if (s.is(Items.ENDER_PEARL) || s.is(Items.SNOWBALL) || s.is(Items.EGG)) return new double[] {1.5, 0.03, 0.99};
		if (s.is(Items.SPLASH_POTION) || s.is(Items.LINGERING_POTION)) return new double[] {0.5, 0.05, 0.99};
		return null;
	}
}
