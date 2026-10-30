package net.vulkanmod.mixin.perf;

import com.mojang.blaze3d.platform.GlStateManager;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.WorldRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Builds the cloud layer once per frame instead of twice, and draws it in two batches instead of
 * a hundred and twenty eight.
 *
 * <p>renderFancyClouds walks an eight by eight grid of cloud cells and, inside the innermost loop,
 * calls begin, emits twenty four vertices and calls draw. The whole grid is then built a second
 * time: the outer loop runs twice, once with the colour mask off to lay down depth and once to
 * shade. One call site in the bytecode, 128 executions and 3072 vertices per frame.
 *
 * <p>Sampling the client thread put 17.6% of it inside this one method, in a flat test world with
 * almost nothing to draw. That is the shape of the problem: the cost does not depend on how much
 * terrain is in view, so it is invisible in a heavy scene and dominant in a light one.
 *
 * <p>Two things are wrong with it, and they are separate:
 *
 * <p>First, the 128 draws. Every cell uses the same vertex format, mode, texture and state, and
 * emits independent quads, so they concatenate into one buffer. That was measured at roughly 2% --
 * real but small, because a draw on this backend costs about 1.3 us.
 *
 * <p>Second, and much larger, the geometry is built twice. The pass index is read in exactly three
 * places in the compiled method -- the loop condition, the branch that picks the colour mask, and
 * the increment -- and nowhere in the vertex code, so both passes produce byte identical data.
 * The loop is cut to one iteration and the single built buffer is drawn twice, once per mask. The
 * Tessellator draw path here cancels vanilla's reset, so the built data survives the first draw
 * and can simply be issued again.
 */
@Mixin(WorldRenderer.class)
public class CloudBatchMixin {

    /** -Dvulkanmod.nocloudbatch=true restores vanilla's build and draw per cell, for measurement. */
    @Unique private static final boolean VULKANMOD_BATCH =
            !Boolean.getBoolean("vulkanmod.nocloudbatch");

    /** Whether a batch is open, so only the first cell of a pass calls begin. */
    @Unique private boolean vulkanmodCloudBatchOpen = false;

    /**
     * Cuts the pass loop to a single iteration.
     *
     * <p>Ordinal 1: the method pushes the int 2 twice, and the first is part of the coordinate
     * arithmetic, not the loop bound.
     */
    @ModifyConstant(method = "renderFancyClouds",
                    constant = @Constant(intValue = 2, ordinal = 1))
    private int vulkanmodSinglePass(int passes) {
        return VULKANMOD_BATCH ? 1 : passes;
    }

    @Redirect(method = "renderFancyClouds",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/client/render/BufferBuilder;begin(ILnet/minecraft/client/render/VertexFormat;)V"))
    private void vulkanmodOpenCloudBatch(BufferBuilder buffer, int mode, VertexFormat format) {
        if (!VULKANMOD_BATCH) {
            buffer.begin(mode, format);
            return;
        }
        if (vulkanmodCloudBatchOpen) return;
        buffer.begin(mode, format);
        vulkanmodCloudBatchOpen = true;
    }

    @Redirect(method = "renderFancyClouds",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/render/Tessellator;draw()V"))
    private void vulkanmodDeferCloudDraw(Tessellator tessellator) {
        // Deferred to the end of the method; the cells keep appending to the open buffer.
        if (!VULKANMOD_BATCH) tessellator.draw();
    }

    /**
     * Draws the one built buffer twice: depth, then colour.
     *
     * <p>With the loop cut to a single iteration the method leaves the colour mask off, which
     * vanilla's second iteration would have restored. Setting it here reproduces exactly what that
     * iteration did, including the two anaglyph variants, so nothing downstream sees a mask it did
     * not expect.
     */
    @Inject(method = "renderFancyClouds", at = @At("RETURN"))
    private void vulkanmodDrawBothPasses(float tickDelta, int pass, CallbackInfo ci) {
        if (!VULKANMOD_BATCH || !vulkanmodCloudBatchOpen) {
            vulkanmodCloudBatchOpen = false;
            return;
        }
        vulkanmodCloudBatchOpen = false;

        Tessellator tessellator = Tessellator.getInstance();

        // Depth only, the mask the single iteration left set.
        tessellator.draw();

        if (pass == 0) {
            GlStateManager.colorMask(false, true, true, true);
        } else if (pass == 1) {
            GlStateManager.colorMask(true, false, false, true);
        } else {
            GlStateManager.colorMask(true, true, true, true);
        }

        // Same vertices again. The draw path cancels vanilla's buffer reset, so the data is still
        // there and this costs one more draw rather than another 1536 vertices.
        tessellator.draw();
    }
}
