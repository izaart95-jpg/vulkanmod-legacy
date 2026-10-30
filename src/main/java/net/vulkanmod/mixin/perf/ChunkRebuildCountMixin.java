package net.vulkanmod.mixin.perf;

import net.minecraft.client.world.BuiltChunk;
import net.minecraft.client.world.ChunkBuilder;
import net.vulkanmod.vulkan.PerformanceProfiler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Counts section rebuilds so repeated work can be told apart from a world that is still loading.
 *
 * <p>Total rebuilds against distinct sections is the number that matters: with a stationary
 * player in a settled world the two should converge and then stop growing. Profiler-gated.
 */
@Mixin(BuiltChunk.class)
public class ChunkRebuildCountMixin {

    @Inject(method = "method_10164", at = @At("HEAD"))
    private void vulkanmodCountRebuild(float x, float y, float z, ChunkBuilder builder, CallbackInfo ci) {
        if (!PerformanceProfiler.ENABLED) return;
        PerformanceProfiler.chunkRebuilds.incrementAndGet();
        PerformanceProfiler.chunkRebuildPositions.add(((BuiltChunk) (Object) this).getPos().toString());
    }
}
