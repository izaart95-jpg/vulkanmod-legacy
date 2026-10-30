package net.vulkanmod.mixin.perf;

import net.minecraft.client.render.block.BlockModelRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.BitSet;

/**
 * Reuses the per-block scratch objects the chunk mesher allocates.
 *
 * <p>renderSmooth() and renderFlat() are called once per block per render layer while a chunk
 * section is being built, and each call allocates a fresh BitSet (and, in the smooth path, an
 * AmbientOcclusionCalculator). A dense section is 4096 blocks across one or two layers, so a
 * single chunk build throws away on the order of ten thousand short lived objects for state
 * that is fully rewritten before it is ever read.
 *
 * <p>This is OptiFine's RenderEnv idea narrowed to the allocation that can be intercepted
 * cleanly -- the AmbientOcclusionCalculator beside it is package private, so it cannot be
 * referenced from here, and the float[] is an array creation which @At("NEW") does not match. The instances are per thread because chunk building runs on worker threads, and the
 * BitSet is cleared on hand-out so a reused one behaves exactly like a fresh one -- its bits are
 * read as "was this quad face bound computed", and a stale bit would cull quad faces that
 * should be drawn.
 */
@Mixin(BlockModelRenderer.class)
public class BlockModelRendererMixin {

    @Unique
    private static final ThreadLocal<BitSet> VULKANMOD_BIT_SET = ThreadLocal.withInitial(() -> new BitSet(3));

    /**
     * Times the meshing of one block, so the cost of the quad emission path can be compared
     * against the frame budget before anything in it is rewritten.
     */
    @Unique private static final ThreadLocal<long[]> VULKANMOD_START = ThreadLocal.withInitial(() -> new long[1]);

    @Inject(method = {"renderSmooth", "renderFlat"}, at = @At("HEAD"))
    private void vulkanmodMeshStart(net.minecraft.world.BlockView view, net.minecraft.client.render.model.BakedModel model,
                                    net.minecraft.block.Block block, net.minecraft.util.math.BlockPos pos,
                                    net.minecraft.client.render.BufferBuilder buffer, boolean cull,
                                    CallbackInfoReturnable<Boolean> cir) {
        if (net.vulkanmod.vulkan.PerformanceProfiler.ENABLED) VULKANMOD_START.get()[0] = System.nanoTime();
    }

    @Inject(method = "renderSmooth", at = @At("RETURN"))
    private void vulkanmodMeshEndSmooth(net.minecraft.world.BlockView view, net.minecraft.client.render.model.BakedModel model,
                                        net.minecraft.block.Block block, net.minecraft.util.math.BlockPos pos,
                                        net.minecraft.client.render.BufferBuilder buffer, boolean cull,
                                        CallbackInfoReturnable<Boolean> cir) {
        if (!net.vulkanmod.vulkan.PerformanceProfiler.ENABLED) return;
        net.vulkanmod.vulkan.PerformanceProfiler.meshSmoothNs
                .addAndGet(System.nanoTime() - VULKANMOD_START.get()[0]);
        net.vulkanmod.vulkan.PerformanceProfiler.meshSmoothBlocks.incrementAndGet();
    }

    @Inject(method = {"renderSmooth", "renderFlat"}, at = @At("RETURN"))
    private void vulkanmodMeshEnd(net.minecraft.world.BlockView view, net.minecraft.client.render.model.BakedModel model,
                                  net.minecraft.block.Block block, net.minecraft.util.math.BlockPos pos,
                                  net.minecraft.client.render.BufferBuilder buffer, boolean cull,
                                  CallbackInfoReturnable<Boolean> cir) {
        if (!net.vulkanmod.vulkan.PerformanceProfiler.ENABLED) return;
        net.vulkanmod.vulkan.PerformanceProfiler.meshBuildNs
                .addAndGet(System.nanoTime() - VULKANMOD_START.get()[0]);
        net.vulkanmod.vulkan.PerformanceProfiler.meshBuildBlocks.incrementAndGet();
    }

    @Redirect(method = "renderSmooth", at = @At(value = "NEW", target = "(I)Ljava/util/BitSet;"))
    private BitSet reuseSmoothBitSet(int nbits) {
        BitSet set = VULKANMOD_BIT_SET.get();
        set.clear();
        return set;
    }

    @Redirect(method = "renderFlat", at = @At(value = "NEW", target = "(I)Ljava/util/BitSet;"))
    private BitSet reuseFlatBitSet(int nbits) {
        BitSet set = VULKANMOD_BIT_SET.get();
        set.clear();
        return set;
    }
}
