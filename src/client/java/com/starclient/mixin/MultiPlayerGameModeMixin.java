package com.starclient.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.MultiPlayerGameMode;

import com.starclient.AttributeSwap;

@Mixin(MultiPlayerGameMode.class)
public abstract class MultiPlayerGameModeMixin {
	// HEAD runs before attack() sends the carried item, so the server sees the swapped slot for this hit.
	// No target arguments are captured, and require = 0, so a changed signature only disables manual Attribute Swap.
	@Inject(method = "attack", at = @At("HEAD"), require = 0)
	private void starclient$attributeSwap(CallbackInfo ci) {
		AttributeSwap.beforeAttack(Minecraft.getInstance());
	}
}
