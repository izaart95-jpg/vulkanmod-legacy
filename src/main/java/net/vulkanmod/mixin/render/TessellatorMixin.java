package net.vulkanmod.mixin.render;

import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.Drawer;
import net.vulkanmod.vulkan.Pipeline;
import net.vulkanmod.vulkan.PipelineManager;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.texture.GLTextureCache;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;

@Mixin(Tessellator.class)
public class TessellatorMixin {

    @Shadow
    private BufferBuilder buffer;

    @Inject(method = "draw", at = @At("HEAD"), cancellable = true)
    private void onDraw(CallbackInfo ci) {
        if (!Initializer.vulkanReady) return;

        Drawer drawer = Drawer.getInstance();
        if (!drawer.isRenderPassActive()) {
            endBufferSafe();
            ci.cancel();
            return;
        }

        int vertexCount = this.buffer.getVertexCount();
        if (vertexCount <= 0) {
            endBufferSafe();
            ci.cancel();
            return;
        }

        int drawMode = this.buffer.getDrawMode();
        // 7/6/5/4 are the triangle topologies; 3/2/1 are LINE_STRIP / LINE_LOOP / LINES, which
        // Minecraft uses for the block selection outline, hitboxes and debug lines. Letting those
        // "fall through to GL" meant they were never drawn at all, since GL renders to a hidden
        // 1x1 window.
        boolean lineMode = drawMode == 1 || drawMode == 2 || drawMode == 3;
        if (!lineMode && drawMode != 7 && drawMode != 6 && drawMode != 5 && drawMode != 4) {
            return; // fall through to GL
        }

        VertexFormat format = this.buffer.getFormat();
        int vertexSize = format.getVertexSize();

        // --- Entity draw batching ---
        // Batching reduces GL reads from ~72/entity (per cuboid) to ~12 (per ModelPart).
        if (Drawer.isBatching() && !lineMode) {
            if (!Drawer.isBatchPipelineReady()) {
                Pipeline pipeline = PipelineManager.getInstance().getPipelineForFormat(format);
                if (pipeline == null) {
                    endBufferSafe();
                    ci.cancel();
                    return;
                }

                VRenderSystem.calculateMVPWithGLSync();

                int samplerCount = pipeline.getSamplers().size();
                if (samplerCount > 0) {
                    GLTextureCache.syncBoundTexture();
                    if (samplerCount > 1) {
                        GLTextureCache.syncLightmapTexture();
                    }
                }

                Drawer.setupBatchPipeline(pipeline, format, drawMode);
            }

            ByteBuffer byteBuffer = this.buffer.getByteBuffer();
            int oldPos = byteBuffer.position();
            int oldLimit = byteBuffer.limit();
            byteBuffer.position(0);
            byteBuffer.limit(vertexCount * vertexSize);

            Drawer.accumulateBatch(byteBuffer, vertexCount);

            byteBuffer.limit(oldLimit);
            byteBuffer.position(oldPos);

            endBufferSafe();
            ci.cancel();
            return;
        }

        Pipeline pipeline = PipelineManager.getInstance().getPipelineForFormat(format);
        if (pipeline == null) {
            endBufferSafe();
            ci.cancel();
            return;
        }

        VRenderSystem.calculateMVPWithGLSync();

        int samplerCount = pipeline.getSamplers().size();
        if (samplerCount > 0) {
            GLTextureCache.syncBoundTexture();
            if (samplerCount > 1) {
                GLTextureCache.syncLightmapTexture();
            }
        }

        Drawer.setBoundPipeline(pipeline);

        ByteBuffer byteBuffer = this.buffer.getByteBuffer();

        // Direct buffer slice — no duplicate() allocation
        int oldPos = byteBuffer.position();
        int oldLimit = byteBuffer.limit();
        byteBuffer.position(0);
        byteBuffer.limit(vertexCount * vertexSize);

        drawer.draw(byteBuffer, drawMode, format, vertexCount);

        // Restore buffer state
        byteBuffer.limit(oldLimit);
        byteBuffer.position(oldPos);

        endBufferSafe();
        ci.cancel();
    }

    private void endBufferSafe() {
        try {
            this.buffer.end();
        } catch (Exception e) {
            // Buffer may not be in building state
        }
    }
}
