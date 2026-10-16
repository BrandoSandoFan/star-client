package com.starclient;

import net.minecraft.client.Minecraft;

/** Zoom: lowers the FOV option while on and restores the previous value when switched off. */
public final class Zoom {
	private static int saved = -1;

	private Zoom() { }

	public static void tick(Minecraft mc) {
		if (!StarClient.zoomOn) return;
		var fov = mc.options.fov();
		if (saved < 0) saved = fov.get();
		int want = Settings.getI("zoom.fov");
		if (fov.get() != want) fov.set(want);
	}

	public static void off(Minecraft mc) {
		if (saved < 0) return;
		mc.options.fov().set(saved);
		saved = -1;
	}
}
