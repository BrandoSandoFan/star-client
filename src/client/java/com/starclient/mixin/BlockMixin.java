package com.starclient.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import com.starclient.Xray;

@Mixin(Block.class)
public abstract class BlockMixin {
	// require = 0: if a Minecraft update changes this signature, X-ray stops working but the game still starts.
	@Inject(method = "shouldRenderFace", at = @At("HEAD"), cancellable = true, require = 0)
	private static void starclient$xray(BlockState state, BlockState neighbor, Direction direction, CallbackInfoReturnable<Boolean> cir) {
		Boolean forced = Xray.faceOverride(state);
		if (forced != null) cir.setReturnValue(forced);
	}
}
