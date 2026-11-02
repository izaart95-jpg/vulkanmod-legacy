package net.vulkanmod.mixin.render;

import com.mojang.blaze3d.platform.GlStateManager;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.VertexBuffer;
import net.minecraft.client.sound.PassiveBeeSoundInstance;
import net.minecraft.client.world.BuiltChunk;
import net.minecraft.util.math.BlockPos;
import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.Drawer;
import net.vulkanmod.vulkan.Pipeline;
import net.vulkanmod.vulkan.PipelineManager;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.render.ChunkDrawable;
import net.vulkanmod.vulkan.texture.GLTextureCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

/**
 * The 1.8.9 VBO chunk renderer still does a full legacy-GL setup for every visible section:
 * push/pop a matrix, translate, multiply a near-identity matrix, bind the array buffer and
 * install four client vertex pointers -- and then {@code VertexBuffer.draw} is intercepted, so
 * none of that state is ever consumed by a GL draw.
 *
 * <p>On a GL driver those calls are cheap; under GL4ES, where every one of them is translated or
 * synchronised, they are not. This mixin iterates the already-culled helper list directly and
 * appends each section to the existing indirect terrain batch, which is what the Vulkan
 * renderer actually consumes.
 */
@Mixin(PassiveBeeSoundInstance.class)
public abstract class PassiveBeeSoundInstanceMixin {

    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void vulkanmod$renderBatched(RenderLayer layer, CallbackInfo ci) {
        if (!Initializer.vulkanReady) return;

        AbstractChunkRenderManagerAccess access = (AbstractChunkRenderManagerAccess) (Object) this;
        if (!access.vulkanmod$isRenderReady()) return;
        if (!VRenderSystem.isCameraMvpValid()) return;

        Drawer drawer = Drawer.getInstance();
        if (!drawer.isRenderPassActive() || !drawer.isBatchingAvailable()) return;

        List<BuiltChunk> chunks = access.vulkanmod$getHelpers();
        int count = chunks.size();
        if (count == 0) {
            vulkanmod$finishLayer(chunks, ci);
            return;
        }

        int layerIndex = layer.ordinal();

        // Decide the path for the whole layer before drawing anything. Falling back halfway
        // through would draw the already-batched sections a second time in the vanilla loop.
        for (int i = 0; i < count; i++) {
            VertexBuffer buffer = chunks.get(i).method_10165(layerIndex);
            if (buffer == null) return;
            if (((ChunkDrawable) buffer).vulkanmod$chunkDrawState() == ChunkDrawable.STATE_NOT_CHUNK) {
                return;
            }
        }

        Pipeline pipeline = layer == RenderLayer.SOLID
                ? PipelineManager.getInstance().getCompressedTerrainOpaquePipeline()
                : PipelineManager.getInstance().getCompressedTerrainPipeline();
        if (pipeline == null) return;

        // Layer-wide state. The old path repeated these checks for every section.
        GLTextureCache.syncBoundTexture();
        if (pipeline.getSamplers().size() > 1) {
            GLTextureCache.syncLightmapTexture();
        }
        Drawer.setBoundPipeline(pipeline);

        // Install the camera MVP in the UBO the terrain pipeline reads. The old per-chunk path
        // did this as a side effect of prepareChunkDraw(); batching only supplies the per-section
        // translation, so without this the whole layer is drawn with a stale matrix.
        if (!VRenderSystem.prepareChunkDraw()) return;
        // Batched draws carry the translation in the instance buffer. Clear the push constant
        // prepareChunkDraw() just computed (it is zero for the camera matrix, but be explicit).
        VRenderSystem.clearChunkOffset();

        double vx = access.vulkanmod$getViewX();
        double vy = access.vulkanmod$getViewY();
        double vz = access.vulkanmod$getViewZ();

        for (int i = 0; i < count; i++) {
            BuiltChunk chunk = chunks.get(i);
            ChunkDrawable drawable = (ChunkDrawable) chunk.method_10165(layerIndex);
            if (drawable.vulkanmod$chunkDrawState() != ChunkDrawable.STATE_DRAWABLE) {
                // An empty section renders nothing on either path.
                continue;
            }

            BlockPos pos = chunk.getPos();
            drawable.vulkanmod$drawChunk(
                    (float) (pos.getX() - vx),
                    (float) (pos.getY() - vy),
                    (float) (pos.getZ() - vz));
        }

        vulkanmod$finishLayer(chunks, ci);
    }

    @Unique
    private static void vulkanmod$finishLayer(List<BuiltChunk> chunks, CallbackInfo ci) {
        // Reproduce the tail of the vanilla method. The fast path never bound a vertex buffer,
        // so the glBindBuffer(0) is unnecessary -- and on Android that call crosses gl4es.
        // clearColor() only updates Minecraft's cached colour state, which later draws read.
        GlStateManager.clearColor();
        chunks.clear();
        ci.cancel();
    }
}
