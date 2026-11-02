package net.vulkanmod.mixin.render;

import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormatElement;

import java.util.List;
import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.Drawer;
import net.vulkanmod.vulkan.Pipeline;
import net.vulkanmod.vulkan.PipelineManager;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.texture.GLTextureCache;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;

@Mixin(net.minecraft.client.render.VertexBuffer.class)
public class VertexBufferMixin implements net.vulkanmod.vulkan.render.ChunkDrawable {

    @Shadow private int size;
    @Shadow private VertexFormat format;

    // Persistent VBO: uploaded once in onData(), reused every frame in onDraw().
    // Eliminates per-frame vertex copy which is the #1 bottleneck in dense block areas.
    @Unique private net.vulkanmod.vulkan.memory.VertexBuffer persistentVB;
    @Unique private int persistentVertexCount;

    // Slot inside the shared chunk arena. When non-null the mesh lives in the arena and this
    // chunk can join the batched (indirect multi-draw) terrain pass; persistentVB is then unused.
    @Unique private net.vulkanmod.vulkan.memory.ChunkArena.Slot arenaSlot;
    @Unique private net.vulkanmod.vulkan.memory.ChunkArena arena;
    /** True when this buffer holds the 16-byte compressed terrain layout. */
    @Unique private boolean compressed;

    // Reusable off-heap upload buffer — avoids ByteBuffer.allocateDirect per chunk upload
    @Unique private ByteBuffer uploadBuf;
    @Unique private int uploadBufCapacity;

    // Fallback capture for pre-Vulkan init data calls
    @Unique private ByteBuffer capturedVertexData;
    @Unique private int capturedVertexCount;

    @Inject(method = "data", at = @At("HEAD"), cancellable = true)
    private void onData(ByteBuffer buffer, CallbackInfo ci) {
        if (buffer == null || buffer.remaining() <= 0) return;

        int dataSize = buffer.remaining();
        int vertexSize = this.format != null ? this.format.getVertexSize() : 0;
        if (vertexSize <= 0) return;

        int vertexCount = dataSize / vertexSize;
        int pos = buffer.position();

        // Before Vulkan is ready, just capture raw data (no persistent VBO possible)
        if (!Initializer.vulkanReady) {
            capturedVertexCount = vertexCount;
            if (capturedVertexData == null || capturedVertexData.capacity() < dataSize) {
                capturedVertexData = ByteBuffer.allocateDirect(dataSize).order(ByteOrder.nativeOrder());
            }
            capturedVertexData.clear();
            capturedVertexData.put(buffer);
            capturedVertexData.flip();
            buffer.position(pos);
            return;
        }

        // Direct upload. Face-direction culling used to live here: quads were classified by
        // facing on a worker thread, reordered into per-direction runs, and whole directions
        // were skipped based on where the camera sat relative to the section. It is gone because
        // the visibility test it relied on is only sound for flat, axis-aligned faces, and
        // Minecraft's meshes are full of geometry that is not.
        int oldPos2 = buffer.position();
        buffer.position(pos);
        int savedLimit = buffer.limit();
        buffer.limit(pos + dataSize);

        // Compression happens here, on the upload, where the buffer and the vertex count that
        // describes it are unambiguously paired. Repacking on the chunk worker and parking the
        // result for collection was faster on paper but keyed the parked mesh by its ByteBuffer;
        // BufferBuilders are pooled, so one chunk's mesh got collected for another's upload and
        // smeared torn geometry and wrong textures across the world.
        ByteBuffer copy;
        int uploadSize;
        if (resolveOffsets(this.format, vertexSize)) {
            compressed = true;
            uploadSize = vertexCount * COMPRESSED_STRIDE;
            copy = getUploadBuffer(uploadSize);
            compressVertices(buffer, pos, vertexCount, vertexSize, copy, 0);
            copy.position(0).limit(uploadSize);
        } else {
            compressed = false;
            uploadSize = dataSize;
            copy = getUploadBuffer(dataSize);
            copy.put(buffer);
            copy.flip();
        }

        buffer.limit(savedLimit);
        buffer.position(oldPos2);

        uploadToPersistentVB(copy, uploadSize);
        persistentVertexCount = vertexCount;

        buffer.position(pos);

        // The mesh now lives in the Vulkan arena. Letting the original run would bind this
        // VertexBuffer and glBufferData the whole mesh through gl4es on Android, where every
        // GL call crosses the JNI/GLES translation layer -- and drawing is intercepted, so the
        // resulting GL buffer is never read. Keep the vanilla size field in sync because other
        // code still uses it as the section's vertex count.
        this.size = vertexCount;
        ci.cancel();
    }

    @Unique private static final int COMPRESSED_STRIDE = 16;
    @Unique private static final float POS_SCALE = 2048.0f;
    @Unique private static final float POS_OFFSET = -4.0f;

    // Byte offsets of the four elements inside Minecraft's vertex, resolved per format.
    // 1.8.9 hands us the same set of elements in more than one order
    // (POSITION,COLOR,UV,UV2 and POSITION,UV,COLOR,UV2), so these cannot be hard-coded:
    // assuming one order silently reinterpreted colour bytes as coordinates and drew garbage.
    @Unique private int offPos = -1, offColor = -1, offUV0 = -1, offUV2 = -1;

    @Unique
    private boolean resolveOffsets(VertexFormat format, int vertexSize) {
        if (!net.vulkanmod.vulkan.PipelineManager.COMPRESS_TERRAIN) return false;
        if (format == null || vertexSize != 28) return false;

        offPos = offColor = offUV0 = offUV2 = -1;
        int offset = 0;
        for (VertexFormatElement element : format.getElements()) {
            VertexFormatElement.Type type = element.getType();
            if (type == VertexFormatElement.Type.POSITION
                    && element.getFormat() == VertexFormatElement.Format.FLOAT) {
                offPos = offset;
            } else if (type == VertexFormatElement.Type.COLOR) {
                offColor = offset;
            } else if (type == VertexFormatElement.Type.UV) {
                if (element.getFormat() == VertexFormatElement.Format.FLOAT) offUV0 = offset;
                else if (element.getFormat() == VertexFormatElement.Format.SHORT) offUV2 = offset;
            }
            offset += element.getSize();
        }
        return offPos >= 0 && offColor >= 0 && offUV0 >= 0 && offUV2 >= 0;
    }

    @Unique
    private void compressVertices(ByteBuffer src, int base, int vertexCount,
                                  int srcStride, ByteBuffer dst, int dstBase) {
        for (int i = 0; i < vertexCount; i++) {
            int s = base + i * srcStride;
            int d = dstBase + i * COMPRESSED_STRIDE;

            // Position: chunk-local floats to fixed point. The bias keeps geometry that pokes
            // slightly outside the section (fences, fluids) inside the signed 16-bit range.
            dst.putShort(d,     packPos(src.getFloat(s + offPos)));
            dst.putShort(d + 2, packPos(src.getFloat(s + offPos + 4)));
            dst.putShort(d + 4, packPos(src.getFloat(s + offPos + 8)));

            // Lightmap: two shorts holding values up to 240; the low byte of each is enough.
            int block = src.getShort(s + offUV2) & 0xFF;
            int sky = src.getShort(s + offUV2 + 2) & 0xFF;
            dst.putShort(d + 6, (short) ((sky << 8) | block));

            // UV0: normalised into uint16.
            dst.putShort(d + 8,  packUV(src.getFloat(s + offUV0)));
            dst.putShort(d + 10, packUV(src.getFloat(s + offUV0 + 4)));

            // Colour passes through untouched.
            dst.putInt(d + 12, src.getInt(s + offColor));
        }
    }

    @Unique
    private boolean uploadCompressed(ByteBuffer src, int srcBase, int vertexCount,
                                     int srcStride, int uploadSize) {
        if (!Drawer.getInstance().isBatchingAvailable()) return false;

        arena = net.vulkanmod.vulkan.memory.ChunkArena.forStride(COMPRESSED_STRIDE);
        net.vulkanmod.vulkan.memory.ChunkArena.Slot slot = arena.allocate(arenaSlot, vertexCount);
        if (slot == null) return false;
        arenaSlot = slot;

        if (persistentVB != null) {
            net.vulkanmod.vulkan.memory.MemoryManager.addToFreeable(persistentVB);
            persistentVB = null;
        }

        return arena.uploadGenerated(slot, uploadSize,
                (dst, base) -> compressVertices(src, srcBase, vertexCount, srcStride, dst, base));
    }

    @Unique
    private static short packPos(float v) {
        // Vanilla scales every section by 1.000001 around its own origin
        // (BuiltChunk.method_10169 applies the matrix captured by method_10174). That tiny
        // expansion closes the hairline cracks that otherwise appear between neighbouring
        // sections at some camera angles. Folding it in here is exact and costs nothing at
        // draw time; skipping it is only invisible until it is not.
        v = v * 1.000001f - 0.000008f;

        // (v + POS_OFFSET) * SCALE, i.e. (v - 4) * 2048. The bias is subtracted, not added:
        // adding it pushed a chunk's [0,16] range up to [8192, 40960], overflowing the signed
        // short above v = 12 and clamping everything higher, which tore the geometry apart.
        // Biasing down maps [0,16] to [-8192, 24576], with room for the overhang from fences
        // and fluids at either end.
        int i = Math.round((v + POS_OFFSET) * POS_SCALE);
        if (i > Short.MAX_VALUE) i = Short.MAX_VALUE;
        else if (i < Short.MIN_VALUE) i = Short.MIN_VALUE;
        return (short) i;
    }

    @Unique
    private static short packUV(float v) {
        int i = Math.round(v * 65535.0f);
        if (i > 65535) i = 65535;
        else if (i < 0) i = 0;
        return (short) i;
    }

    @Unique
    private ByteBuffer getUploadBuffer(int needed) {
        if (uploadBuf == null || uploadBufCapacity < needed) {
            if (uploadBuf != null) MemoryUtil.memFree(uploadBuf);
            uploadBufCapacity = Math.max(needed, uploadBufCapacity * 2);
            uploadBuf = MemoryUtil.memAlloc(uploadBufCapacity);
        }
        uploadBuf.clear();
        return uploadBuf;
    }

    @Unique
    private void uploadToPersistentVB(ByteBuffer data, int dataSize) {
        // Preferred path: suballocate out of the arena shared by every chunk of this stride, so
        // one vertex bind covers the whole terrain batch.
        int vertexSize = compressed ? COMPRESSED_STRIDE
                : (this.format != null ? this.format.getVertexSize() : 0);
        if (vertexSize > 0 && Drawer.getInstance().isBatchingAvailable()) {
            arena = net.vulkanmod.vulkan.memory.ChunkArena.forStride(vertexSize);
            net.vulkanmod.vulkan.memory.ChunkArena.Slot slot =
                    arena.allocate(arenaSlot, dataSize / vertexSize);
            if (slot != null) {
                arenaSlot = slot;
                arena.upload(slot, data);
                if (persistentVB != null) {
                    net.vulkanmod.vulkan.memory.MemoryManager.addToFreeable(persistentVB);
                    persistentVB = null;
                }
                return;
            }
            // Arena could not grow -- fall through to a private buffer for this chunk.
        }

        if (arenaSlot != null) {
            if (arena != null) arena.free(arenaSlot);
            arenaSlot = null;
        }


        // Reuse the existing allocation whenever the new mesh still fits. Chunks are rebuilt
        // constantly while walking, and reallocating here meant a vmaCreateBuffer (plus a
        // deferred free, plus VMA fragmentation) for every single rebuild -- even when the
        // mesh grew by a handful of bytes.
        if (persistentVB != null && persistentVB.getSize() < dataSize) {
            // Genuinely too small: retire it (the GPU may still be reading it this frame) and
            // grow with headroom so the next few rebuilds land in the reuse path above.
            net.vulkanmod.vulkan.memory.MemoryManager.addToFreeable(persistentVB);
            persistentVB = null;
        }

        if (persistentVB == null) {
            int capacity = dataSize + (dataSize >> 1); // 1.5x headroom
            persistentVB = new net.vulkanmod.vulkan.memory.VertexBuffer(
                    capacity, net.vulkanmod.vulkan.memory.MemoryTypes.GPU_MEM);
        }
        persistentVB.reset();
        persistentVB.uploadWholeBuffer(data);
    }

    @Inject(method = "draw", at = @At("HEAD"), cancellable = true)
    private void onDraw(int mode, CallbackInfo ci) {
        if (!Initializer.vulkanReady) return;
        ci.cancel();

        // If persistent VBO not yet created (data was captured before Vulkan init),
        // try to upload now
        if (persistentVB == null && capturedVertexData != null && capturedVertexCount > 0) {
            int vs = this.format.getVertexSize();
            capturedVertexData.position(0);
            capturedVertexData.limit(capturedVertexCount * vs);
            uploadToPersistentVB(capturedVertexData, capturedVertexCount * vs);
            persistentVertexCount = capturedVertexCount;
            capturedVertexData = null;
        }

        if ((persistentVB == null && arenaSlot == null) || persistentVertexCount <= 0) return;

        Drawer drawer = Drawer.getInstance();
        if (!drawer.isRenderPassActive()) return;

        if (mode != 7 && mode != 6 && mode != 5 && mode != 4) return;

        // Compressed terrain has its own pipeline: the vertex layout is described by hand
        // rather than derived from Minecraft's VertexFormat.
        Pipeline pipeline = compressed
                ? PipelineManager.getInstance().getCompressedTerrainPipeline()
                : PipelineManager.getInstance().getPipelineForFormat(this.format);
        if (pipeline == null) return;

        // The GL modelview still has to be read here for every chunk.
        //
        // It looks redundant -- the chunk translation does go through GlStateManager
        // (AbstractChunkRenderManager.method_9770 calls translatef inside push/popMatrix, all
        // hooked), so in theory VRenderSystem's software stack already has it. Dropping the read
        // on that reasoning broke the view: the camera ended up inside the terrain. The read is
        // not only computing a matrix, it is *repairing* the software stack every chunk, which
        // drifts from real GL state. Keep it until the stack itself is trustworthy.
        //
        // What did change: the read now yields a 12-byte ChunkOffset push constant instead of a
        // per-chunk MVP, so every chunk in the layer shares one UBO slot and one descriptor set.
        boolean chunkOffsetPath = VRenderSystem.prepareChunkDraw();
        if (!chunkOffsetPath) {
            // Camera matrix not established yet (drawn outside a world render pass) -- fall back
            // to folding the translation into the MVP, with a zero offset so the shader agrees.
            VRenderSystem.clearChunkOffset();
            VRenderSystem.calculateMVPWithGLSync();
        }

        int samplerCount = pipeline.getSamplers().size();
        if (samplerCount > 0) {
            GLTextureCache.syncBoundTexture();
            if (samplerCount > 1) {
                GLTextureCache.syncLightmapTexture();
            }
        }

        Drawer.setBoundPipeline(pipeline);

        if (arenaSlot != null && mode == 7 && chunkOffsetPath && drawer.isBatchingAvailable()) {
            // Batched: no command is recorded here, the whole layer is emitted in one
            // vkCmdDrawIndexedIndirect when the batch is flushed.
            drawer.drawChunkBatched(arenaSlot, persistentVertexCount, this.format,
                    VRenderSystem.getChunkOffsetX(), VRenderSystem.getChunkOffsetY(),
                    VRenderSystem.getChunkOffsetZ());
        } else if (arenaSlot != null && mode == 7) {
            // In the arena but not batchable this frame (no camera matrix yet, or the frame's
            // indirect storage is full). Falling through to persistentVB would draw nothing,
            // since the arena path frees it.
            drawer.drawChunkFromArena(arenaSlot, persistentVertexCount);
        } else if (persistentVB != null) {
            drawer.drawPersistent(persistentVB, persistentVertexCount, mode, this.format);
        }

        // Leave the offset at zero so any non-terrain draw sharing this pipeline is unaffected.
        if (chunkOffsetPath) VRenderSystem.clearChunkOffset();
    }

    @Unique
    @Override
    public int vulkanmod$chunkDrawState() {
        if (persistentVertexCount <= 0) {
            // Captured data that has not reached the GPU yet must not be treated as empty:
            // the vanilla path uploads it on the first draw.
            return capturedVertexCount > 0 ? STATE_NOT_CHUNK : STATE_EMPTY;
        }
        if (arenaSlot != null && compressed) return STATE_DRAWABLE;
        return STATE_NOT_CHUNK;
    }

    @Unique
    @Override
    public void vulkanmod$drawChunk(float offsetX, float offsetY, float offsetZ) {
        if (arenaSlot == null || !compressed || persistentVertexCount <= 0) return;

        Drawer drawer = Drawer.getInstance();
        if (!drawer.isRenderPassActive() || !drawer.isBatchingAvailable()) return;

        // The caller has already selected the compressed-terrain pipeline and synced the
        // layer's textures once, so nothing here touches GL state or the texture cache.
        drawer.drawChunkBatched(arenaSlot, persistentVertexCount, this.format,
                offsetX, offsetY, offsetZ);
    }

    @Inject(method = "delete", at = @At("HEAD"))
    private void onDelete(CallbackInfo ci) {
        // Chunks are deleted constantly as the player moves. Without this the arena slot is
        // never returned and the arena grows until it can no longer be doubled.
        if (arenaSlot != null) {
            if (arena != null) arena.free(arenaSlot);
            arenaSlot = null;
        }
        if (persistentVB != null) {
            net.vulkanmod.vulkan.memory.MemoryManager.addToFreeable(persistentVB);
            persistentVB = null;
        }
        if (uploadBuf != null) {
            MemoryUtil.memFree(uploadBuf);
            uploadBuf = null;
            uploadBufCapacity = 0;
        }
        persistentVertexCount = 0;
    }




}
