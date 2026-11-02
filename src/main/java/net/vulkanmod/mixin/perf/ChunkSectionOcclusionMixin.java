package net.vulkanmod.mixin.perf;

import net.minecraft.block.BlockState;
import net.minecraft.world.chunk.ChunkSection;
import net.vulkanmod.vulkan.render.OcclusionCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Marks the cached terrain occlusion result stale whenever a block changes.
 *
 * <p>Both writes matter: {@code setBlockState} covers player and neighbour updates,
 * {@code setBlockStates} replaces a whole section when chunk data is loaded, which can happen
 * under a standing player after a respawn or dimension change.
 */
@Mixin(ChunkSection.class)
public class ChunkSectionOcclusionMixin {

    @Inject(method = "setBlockState", at = @At("RETURN"))
    private void vulkanmod$invalidateOcclusion(int x, int y, int z, BlockState state, CallbackInfo ci) {
        OcclusionCache.invalidate();
    }

    @Inject(method = "setBlockStates", at = @At("RETURN"))
    private void vulkanmod$invalidateOcclusion(char[] states, CallbackInfo ci) {
        OcclusionCache.invalidate();
    }
}
