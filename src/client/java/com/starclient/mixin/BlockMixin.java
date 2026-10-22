package com.starclient.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyReturnValue;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import com.starclient.Xray;

@Mixin(Block.class)
public abstract class BlockMixin {
	// Runs for every face while chunks mesh. ModifyReturnValue passes the result through without the
	// CallbackInfo allocation that a cancellable HEAD inject makes on every call.
	// require = 0: if a Minecraft update changes this signature, X-ray stops working but the game still starts.
	@ModifyReturnValue(method = "shouldRenderFace", at = @At("RETURN"), require = 0)
	private static boolean starclient$xray(boolean original, BlockState state, BlockState neighbor, Direction direction) {
		Boolean forced = Xray.faceOverride(state);
		return forced != null ? forced : original;
	}
}
