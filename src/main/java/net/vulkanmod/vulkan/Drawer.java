package net.vulkanmod.vulkan;

import net.minecraft.client.render.VertexFormat;
import net.vulkanmod.vulkan.FrustumCuller;
import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.memory.*;
import net.vulkanmod.vulkan.render.EntityInstanceRenderer;
import net.vulkanmod.vulkan.shader.PushConstant;
import net.vulkanmod.vulkan.texture.VTextureSelector;
import net.vulkanmod.vulkan.texture.VulkanImage;
import net.vulkanmod.vulkan.util.VUtil;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import net.vulkanmod.vulkan.shader.UBO;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import static net.vulkanmod.vulkan.Vulkan.*;
import static org.lwjgl.glfw.GLFW.glfwGetFramebufferSize;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.*;

public class Drawer {
    private static Drawer INSTANCE;

    private static VkDevice device;
    private static List<VkCommandBuffer> commandBuffers;

    // Use ArrayList + identity check instead of HashSet — avoids hash computation per draw.
    // Typical frame has 2-5 pipelines, so linear scan is faster than hashing.
    private static ArrayList<Pipeline> usedPipelines = new ArrayList<>(8);

    // Bound pipeline management (replaces RenderSystem.getShader() from 1.18.2)
    private static Pipeline currentBoundPipeline;
    
    // Per-layer caching: avoid redundant pipeline binds and UBO uploads
    private static Pipeline lastBoundPipelineHandle;
    private static long lastBoundPipelineId;
    private static boolean layerStateValid = false;
    // Reusable PipelineState — eliminates allocation per draw
    private static final Pipeline.PipelineState cachedPipelineState = new Pipeline.PipelineState(
            Pipeline.DEFAULT_BLEND_STATE, Pipeline.DEFAULT_DEPTH_STATE, Pipeline.DEFAULT_LOGICOP_STATE, Pipeline.DEFAULT_COLORMASK);

    // Redundant UBO upload / descriptor bind elimination.
    //
    // Every chunk in a terrain layer now produces identical UBO contents (the camera MVP; the
    // per-chunk translation travels as a push constant), so the upload and the descriptor bind
    // only have to happen once for the whole layer instead of once per chunk. The comparison is
    // a memcmp over ~64-96 bytes, far cheaper than the mapped-memory write plus driver call it
    // avoids.
    // The previous draw's UBO contents, kept off heap so the comparison against the live buffer
    // is a direct-to-direct read: both sides are then plain loads the JIT compiles to one
    // instruction each, instead of a bounds checked ByteBuffer.get per byte on one side and an
    // array load on the other.
    private static ByteBuffer lastUboSnapshot = MemoryUtil.memAlloc(1024);
    private static int lastUboByteCount = -1;
    private static final int[] lastUboOffsets = new int[8];
    private static Pipeline lastUboPipeline;
    private static long lastBoundDS = 0;

    // Redundant buffer bind elimination
    private static long lastBoundVB = 0;
    private static long lastBoundVBOffset = -1;
    private static long lastBoundIB = 0;

    // Pre-allocated native buffers for per-draw Vulkan commands — eliminates stackPush() per draw
    private static final IntBuffer drawDynOffsets = MemoryUtil.memAllocInt(8);
    private static final LongBuffer drawDS = MemoryUtil.memAllocLong(1);
    private static final LongBuffer drawVBId = MemoryUtil.memAllocLong(1);
    private static final LongBuffer drawVBOffset = MemoryUtil.memAllocLong(1);

    // ── Batched terrain: one indirect multi-draw per layer ──
    //
    // Every chunk mesh lives in a shared ChunkArena, so a single vertex bind covers the whole
    // batch and each command picks its mesh with vertexOffset. The per-chunk translation cannot
    // ride in a push constant (those cannot change between indirect commands), so it goes in an
    // instance-rate binding addressed by firstInstance.
    private static final int INDIRECT_CMD_SIZE = 20; // VkDrawIndexedIndirectCommand = 5 * 4 bytes
    private static final int MAX_INDIRECT_CMDS = 16384;

    /** Batching is on by default; -Dvulkanmod.mdi=false falls back to one draw per chunk. */
    public static final boolean MDI_ENABLED = !"false".equalsIgnoreCase(System.getProperty("vulkanmod.mdi"));

    private IndirectBuffer[] indirectBuffers;   // per frame in flight
    private IndirectBuffer[] instanceBuffers;   // per frame in flight
    private IndirectBuffer zeroInstanceBuffer;  // 16 zero bytes, bound for non-batched draws

    private final net.vulkanmod.vulkan.compute.HZBPass hzbPass = new net.vulkanmod.vulkan.compute.HZBPass();

    private int mdiCmdCount = 0;
    private VertexBuffer mdiArena = null;
    private Pipeline mdiPipeline = null;
    private long mdiIndexBuffer = 0;
    private long mdiIndexOffset = 0;
    // Captured when the batch opens. The batch is emitted later, by which time the caller that
    // triggered the flush may already have bound its own textures and blend state -- so the
    // batch has to carry its own and restore them around the flush.
    private long mdiPipelineHandle = 0;
    private VulkanImage mdiBoundTex = null;
    private VulkanImage mdiLightTex = null;
    // Frozen copy of the UBO contents at the moment the batch opened.
    //
    // The batch is emitted later, and re-running ubo.update() at that point would read whatever
    // MVP / fog / colour modulator the caller that triggered the flush has since installed --
    // which drew the entire terrain with the tessellator's matrix. Snapshotting the bytes makes
    // the flush independent of live VRenderSystem state instead of relying on remembering to
    // save and restore each individual piece of it.
    private ByteBuffer mdiUboSnapshot;
    private final int[] mdiUboOffsets = new int[8];
    private final int[] mdiUboSizes = new int[8];
    private int mdiUboCount = 0;

    // Write cursor into this frame's indirect/instance buffers, and the slice the open batch
    // owns. Several batches are emitted per frame (one per layer, plus whatever splits them),
    // and vkCmdDrawIndexedIndirect only *records* a pointer -- the GPU reads the commands when
    // the frame executes. Every batch therefore needs its own region: writing each one from
    // index 0 let a later batch overwrite the commands of an earlier one that had already been
    // recorded but not yet run.
    private int mdiFrameCursor = 0;
    private int mdiBatchBase = 0;

    public static long indirectFlushCount = 0;
    public static long indirectCmdTotal = 0;

    // --- Entity draw batch accumulation ---
    // Batches all cuboid faces within a single ModelPart into one draw call.
    // Reduces per-entity draws from ~72 (6 faces × 12 parts) to ~12 (1 per part).
    private static boolean batchActive = false;
    private static ByteBuffer batchBuf;
    private static int batchBufCap = 0;
    private static int batchVtxCount = 0;
    private static int batchVtxSize = 0;
    private static VertexFormat batchFormat;
    private static Pipeline batchPipeline;
    private static int batchDrawMode = 7;
    private static boolean batchPipelineReady = false;

    private VertexBuffer[] vertexBuffers;
    private AutoIndexBuffer quadsIndexBuffer;
    private AutoIndexBuffer triangleFanIndexBuffer;
    private AutoIndexBuffer triangleStripIndexBuffer;
    private AutoIndexBuffer trianglesIndexBuffer;
    private UniformBuffers uniformBuffers;

    /**
     * -Dvulkanmod.legacysubmit=true restores the pre-measurement command buffer handling: an
     * explicit vkResetCommandBuffer and no ONE_TIME_SUBMIT flag. It exists so the two can be
     * compared from one build in one session, which is the only way the comparison means
     * anything -- two builds run minutes apart landed in scenes with three times the GPU load.
     */
    private static final boolean LEGACY_SUBMIT = Boolean.getBoolean("vulkanmod.legacysubmit");

    private static int MAX_FRAMES_IN_FLIGHT;
    private ArrayList<Long> imageAvailableSemaphores;
    private ArrayList<Long> renderFinishedSemaphores;
    private ArrayList<Long> inFlightFences;

    private static int currentFrame = 0;
    private int imageIndex = 0;
    /** Swapchain image the previous frame rendered into; its depth attachment feeds the HZB. */
    private int prevImageIndex = -1;
    private final int commandBuffersCount = getSwapChainFramebuffers().size();
    private boolean[] activeCommandBuffers = new boolean[getSwapChainFramebuffers().size()];

    private static int currentIndex = 0;

    public static Pipeline.BlendState currentBlendState = Pipeline.DEFAULT_BLEND_STATE;
    public static Pipeline.DepthState currentDepthState = Pipeline.DEFAULT_DEPTH_STATE;
    public static Pipeline.LogicOpState currentLogicOpState = Pipeline.DEFAULT_LOGICOP_STATE;
    public static Pipeline.ColorMask currentColorMask = Pipeline.DEFAULT_COLORMASK;

    public static boolean shouldRecreate = false;
    public static boolean rebuild = false;
    public static boolean skipRendering = false;
    private boolean renderPassStarted = false;

    public Drawer() {
        this(4000000, 400000);
    }

    public Drawer(int VBOSize, int UBOSize) {
        device = Vulkan.getDevice();
        MAX_FRAMES_IN_FLIGHT = getSwapChainImages().size();
        vertexBuffers = new VertexBuffer[MAX_FRAMES_IN_FLIGHT];
        for (int i = 0; i < MAX_FRAMES_IN_FLIGHT; ++i) {
            vertexBuffers[i] = new VertexBuffer(VBOSize, MemoryTypes.HOST_MEM);
        }

        uniformBuffers = new UniformBuffers(UBOSize);
        quadsIndexBuffer = new AutoIndexBuffer(100000, AutoIndexBuffer.DrawType.QUADS);
        triangleFanIndexBuffer = new AutoIndexBuffer(1000, AutoIndexBuffer.DrawType.TRIANGLE_FAN);
        triangleStripIndexBuffer = new AutoIndexBuffer(1000, AutoIndexBuffer.DrawType.TRIANGLE_STRIP);
        trianglesIndexBuffer = new AutoIndexBuffer(10000, AutoIndexBuffer.DrawType.TRIANGLES);

        // Always allocated, even when batching is off: the terrain pipeline declares the
        // instance-rate binding unconditionally (it comes from the shader JSON), and a draw with
        // binding 1 left unbound reads undefined vertex data.
        zeroInstanceBuffer = new IndirectBuffer(Pipeline.CHUNK_OFFSET_STRIDE);
        ByteBuffer z = zeroInstanceBuffer.map();
        for (int i = 0; i < Pipeline.CHUNK_OFFSET_STRIDE; ++i) z.put(i, (byte) 0);

        if (MDI_ENABLED && Vulkan.supportsIndirectTerrain()) {
            indirectBuffers = new IndirectBuffer[MAX_FRAMES_IN_FLIGHT];
            instanceBuffers = new IndirectBuffer[MAX_FRAMES_IN_FLIGHT];
            for (int i = 0; i < MAX_FRAMES_IN_FLIGHT; ++i) {
                indirectBuffers[i] = new IndirectBuffer(INDIRECT_CMD_SIZE * MAX_INDIRECT_CMDS);
                instanceBuffers[i] = new IndirectBuffer(Pipeline.CHUNK_OFFSET_STRIDE * MAX_INDIRECT_CMDS);
            }
            Initializer.LOGGER.info("[Drawer] Batched terrain enabled (multi-draw indirect)");
        } else if (MDI_ENABLED) {
            Initializer.LOGGER.info("[Drawer] Batched terrain unavailable: device lacks multiDrawIndirect / drawIndirectFirstInstance");
        }

        createSyncObjects();

        PerformanceProfiler.initGpuTimers(MAX_FRAMES_IN_FLIGHT);

        if (net.vulkanmod.vulkan.compute.HZBCulling.ENABLED) {
            // resize(), not init(): the extent here is the real one, while the extent at startup
            // is the default window size. A pyramid built for that covered a corner of the screen.
            net.vulkanmod.vulkan.compute.HZBCulling.getInstance()
                    .resize(getSwapchainExtent().width(), getSwapchainExtent().height());
            hzbPass.prepare(MAX_FRAMES_IN_FLIGHT);
        }

        // boolean[] elements default to false in Java — no initialization needed

        this.allocateCommandBuffers();
    }

    public static void setBoundPipeline(Pipeline pipeline) {
        currentBoundPipeline = pipeline;
    }

    public static Pipeline getBoundPipeline() {
        return currentBoundPipeline;
    }

    public boolean isRenderPassActive() {
        return renderPassStarted && !skipRendering;
    }

    public static void invalidateDescriptorSetCache() {
        // No-op: dynamic UBO offsets handle per-draw UBO changes,
        // DS cache is per-frame and cleared at resetDescriptorPool time
    }

    public static void invalidateLayerCache() {
        layerStateValid = false;
        lastBoundPipelineHandle = null;
        lastBoundPipelineId = 0;
        lastBoundVB = 0;
        lastBoundVBOffset = -1;
        lastBoundIB = 0;
        lastUboByteCount = -1;
        lastUboPipeline = null;
        lastBoundDS = 0;
    }

    /**
     * Uploads the pipeline's UBOs and binds its descriptor set, skipping both when nothing has
     * changed since the previous draw. Returns with drawDynOffsets holding the offsets to use.
     */
    private void bindUniformsAndDescriptors(Pipeline pipeline) {
        final long tBind = PerformanceProfiler.now();
        VkCommandBuffer commandBuffer = commandBuffers.get(imageIndex);

        List<UBO> ubos = pipeline.getUBOs();
        int uboCount = ubos.size();

        // Refresh the CPU-side contents first; this is pure arithmetic, no driver involvement.
        int totalBytes = 0;
        for (int i = 0; i < uboCount; i++) {
            UBO ubo = ubos.get(i);
            ubo.update();
            totalBytes += ubo.getBuffer().remaining();
        }

        boolean reuse = pipeline == lastUboPipeline && lastUboByteCount == totalBytes
                && uboCount <= lastUboOffsets.length && totalBytes <= lastUboSnapshot.capacity();
        if (reuse) {
            int p = 0;
            for (int i = 0; i < uboCount && reuse; i++) {
                ByteBuffer buf = ubos.get(i).getBuffer();
                int n = buf.remaining();
                reuse = sameBytes(buf, buf.position(), lastUboSnapshot, p, n);
                p += n;
            }
        }

        if (reuse) {
            for (int i = 0; i < uboCount; i++) drawDynOffsets.put(i, lastUboOffsets[i]);
        } else {
            if (lastUboSnapshot.capacity() < totalBytes) {
                MemoryUtil.memFree(lastUboSnapshot);
                lastUboSnapshot = MemoryUtil.memAlloc(Math.max(1024, totalBytes));
            }
            long snapshotAddress = MemoryUtil.memAddress0(lastUboSnapshot);
            int p = 0;
            for (int i = 0; i < uboCount; i++) {
                UBO ubo = ubos.get(i);
                ByteBuffer buf = ubo.getBuffer();
                int n = buf.remaining();
                MemoryUtil.memCopy(MemoryUtil.memAddress(buf), snapshotAddress + p, n);
                p += n;

                int dynamicOffset = uniformBuffers.getUsedBytes();
                drawDynOffsets.put(i, dynamicOffset);
                if (i < lastUboOffsets.length) lastUboOffsets[i] = dynamicOffset;
                uniformBuffers.uploadUBO(buf, dynamicOffset, imageIndex);
            }
            lastUboByteCount = totalBytes;
            lastUboPipeline = pipeline;
        }

        long descriptorSet = pipeline.getOrCreateDescriptorSet(imageIndex, uniformBuffers);

        // The descriptor set carries the UBO base and the samplers; the per-draw offset comes in
        // as a dynamic offset, so the bind can be skipped only when both are unchanged.
        if (descriptorSet == lastBoundDS && reuse) return;

        drawDS.put(0, descriptorSet);
        IntBuffer pDynOffsets = null;
        if (uboCount > 0) {
            drawDynOffsets.limit(uboCount);
            pDynOffsets = drawDynOffsets;
        }
        vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS,
                pipeline.getLayout(), 0, drawDS, pDynOffsets);
        if (uboCount > 0) {
            drawDynOffsets.limit(drawDynOffsets.capacity());
        }
        lastBoundDS = descriptorSet;
        if (PerformanceProfiler.ENABLED) {
            PerformanceProfiler.uploadBindAndDrawTotalNs += PerformanceProfiler.now() - tBind;
        }
    }

    /**
     * Whether {@code n} bytes of two direct buffers are equal, comparing eight at a time.
     *
     * <p>This runs once per uniform block per draw call, so with a few hundred draws a frame the
     * per byte version was thousands of bounds checked accessor calls per frame. Uniform blocks are
     * std140, so their size is a multiple of four and the tail is at most one int.
     */
    private static boolean sameBytes(ByteBuffer a, int aOffset, ByteBuffer b, int bOffset, int n) {
        int i = 0;
        for (; i + 8 <= n; i += 8) {
            if (a.getLong(aOffset + i) != b.getLong(bOffset + i)) return false;
        }
        for (; i + 4 <= n; i += 4) {
            if (a.getInt(aOffset + i) != b.getInt(bOffset + i)) return false;
        }
        for (; i < n; i++) {
            if (a.get(aOffset + i) != b.get(bOffset + i)) return false;
        }
        return true;
    }

    /**
     * Draw from a persistent Vulkan VertexBuffer (no per-frame vertex copy).
     * Like VulkanMod-dev's buildDrawBatchesDirect: buffer already in GPU memory.
     */
    public void drawPersistent(VertexBuffer persistentVB, int vertexCount, int drawMode, VertexFormat vertexFormat) {
        assertCommandBufferState();
        if (vertexCount <= 0) return;
        flushTerrainBatch();

        AutoIndexBuffer autoIndexBuffer;
        if (drawMode == 7) {
            autoIndexBuffer = this.quadsIndexBuffer;
        } else if (drawMode == 6) {
            autoIndexBuffer = this.triangleFanIndexBuffer;
        } else if (drawMode == 5) {
            autoIndexBuffer = this.triangleStripIndexBuffer;
        } else if (drawMode == 4) {
            autoIndexBuffer = this.trianglesIndexBuffer;
        } else {
            throw new RuntimeException("unknown drawType");
        }

        autoIndexBuffer.checkCapacity(vertexCount);

        Pipeline boundPipeline = currentBoundPipeline;
        if (boundPipeline == null) throw new RuntimeException("No pipeline bound");

        // Per-layer caching: reuse static PipelineState to avoid allocation
        cachedPipelineState.update(currentBlendState, VRenderSystem.getDepthState(), currentLogicOpState, VRenderSystem.getColorMask());
        long pipelineHandle = boundPipeline.getHandle(cachedPipelineState);
        if (pipelineHandle != lastBoundPipelineId || boundPipeline != lastBoundPipelineHandle) {
            VkCommandBuffer commandBuffer = commandBuffers.get(imageIndex);
            vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, pipelineHandle);
            trackPipeline(boundPipeline);
            lastBoundPipelineHandle = boundPipeline;
            lastBoundPipelineId = pipelineHandle;
        }

        int indexCount;
        if (drawMode == 7) indexCount = vertexCount * 3 / 2;
        else if (drawMode == 6 || drawMode == 5) indexCount = (vertexCount - 2) * 3;
        else if (drawMode == 4) indexCount = vertexCount;
        else throw new RuntimeException("unknown drawMode: " + drawMode);

        uploadBindAndDraw(boundPipeline, persistentVB, autoIndexBuffer.getIndexBuffer(), indexCount);
        if (PerformanceProfiler.ENABLED) { PerformanceProfiler.drawCallCount++; PerformanceProfiler.drawPersistentCount++; }
        currentIndex++;
    }

    public void draw(ByteBuffer buffer, int drawMode, VertexFormat vertexFormat, int vertexCount) {
        assertCommandBufferState();

        if (!(vertexCount > 0)) return;
        flushTerrainBatch();

        AutoIndexBuffer autoIndexBuffer;
        if (drawMode == 7) {
            autoIndexBuffer = this.quadsIndexBuffer;
        } else if (drawMode == 6) {
            autoIndexBuffer = this.triangleFanIndexBuffer;
        } else if (drawMode == 5) {
            autoIndexBuffer = this.triangleStripIndexBuffer;
        } else if (drawMode == 4) {
            autoIndexBuffer = this.trianglesIndexBuffer;
        } else if (drawMode == 1 || drawMode == 2 || drawMode == 3) {
            // GL_LINES / GL_LINE_LOOP / GL_LINE_STRIP. These were falling through to the
            // exception below and never drawing, which is why the block selection outline was
            // missing. Lines need no index buffer and their own pipeline topology.
            drawLines(buffer, drawMode, vertexFormat, vertexCount);
            currentIndex++;
            return;
        } else {
            throw new RuntimeException("unknown drawType");
        }

        autoIndexBuffer.checkCapacity(vertexCount);

        drawAutoIndexed(buffer, this.vertexBuffers[currentFrame], autoIndexBuffer.getIndexBuffer(), drawMode, vertexFormat, vertexCount);
        currentIndex++;
    }

    /**
     * Non-indexed draw for line topologies.
     *
     * <p>GL_LINE_LOOP has no Vulkan equivalent, so it is drawn as a strip; the closing segment is
     * lost, which for a box outline is invisible because Minecraft emits the loop closed anyway.
     */
    private void drawLines(ByteBuffer buffer, int drawMode, VertexFormat vertexFormat, int vertexCount) {
        Pipeline pipeline = currentBoundPipeline;
        if (pipeline == null) throw new RuntimeException("No pipeline bound");

        int topology = (drawMode == 1) ? VK_PRIMITIVE_TOPOLOGY_LINE_LIST : VK_PRIMITIVE_TOPOLOGY_LINE_STRIP;

        VertexBuffer vertexBuffer = this.vertexBuffers[currentFrame];
        vertexBuffer.copyToVertexBuffer(vertexFormat.getVertexSize(), vertexCount, buffer);

        VkCommandBuffer commandBuffer = commandBuffers.get(imageIndex);

        cachedPipelineState.update(currentBlendState, VRenderSystem.getDepthState(),
                currentLogicOpState, VRenderSystem.getColorMask(), topology);
        long handle = pipeline.getHandle(cachedPipelineState);
        if (handle != lastBoundPipelineId || pipeline != lastBoundPipelineHandle) {
            vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, handle);
            trackPipeline(pipeline);
            lastBoundPipelineHandle = pipeline;
            lastBoundPipelineId = handle;
        }

        // Declared dynamic for line pipelines. Clamped to 1.0 when the device lacks wideLines,
        // where anything else is a validation error.
        vkCmdSetLineWidth(commandBuffer, Vulkan.supportsWideLines()
                ? Math.max(1.0f, VRenderSystem.getShaderLineWidth()) : 1.0f);

        bindUniformsAndDescriptors(pipeline);
        pushChunkOffsetIfNeeded(pipeline, commandBuffer);
        bindZeroInstanceBuffer(pipeline, commandBuffer);

        long vbId = vertexBuffer.getId();
        long vbOffset = vertexBuffer.getOffset();
        drawVBId.put(0, vbId);
        drawVBOffset.put(0, vbOffset);
        vkCmdBindVertexBuffers(commandBuffer, 0, drawVBId, drawVBOffset);
        lastBoundVB = vbId;
        lastBoundVBOffset = vbOffset;

        vkCmdDraw(commandBuffer, vertexCount, 1, 0, 0);
        if (PerformanceProfiler.ENABLED) PerformanceProfiler.drawCallCount++;
    }

    public void draw(VertexBuffer vertexBuffer, IndexBuffer indexBuffer, int indexCount, int drawMode) {
        assertCommandBufferState();

        if (!(indexCount > 0)) return;

        draw(vertexBuffer, indexBuffer, indexCount);
        currentIndex++;
    }

    public void submitDraw() {
        if (skipRendering) return;
        if (!renderPassStarted) return;
        renderPassStarted = false;
        drawFrame();
    }

    public void initiateRenderPass() {
        // The frame's one window system query. Everything else that needs the size reads the
        // cached value, including the shader uniform that used to ask GLFW per draw.
        VRenderSystem.refreshScreenSize();
        skipRendering = VRenderSystem.getScreenWidth() == 0 && VRenderSystem.getScreenHeight() == 0;

        if (skipRendering) return;

        final long tPassStart = PerformanceProfiler.now();

        final long tFence = PerformanceProfiler.now();
        vkWaitForFences(device, inFlightFences.get(currentFrame), true, VUtil.UINT64_MAX);
        if (PerformanceProfiler.ENABLED) PerformanceProfiler.fenceWaitNs += PerformanceProfiler.now() - tFence;

        // Baseline for the frame: no chunk offset, and no camera matrix until setupTerrain
        // establishes one (a menu frame never renders terrain at all).
        // Started from here because this runs on the client thread, so no plumbing is needed to
        // find out which thread that is.
        if (ClientThreadSampler.ENABLED) ClientThreadSampler.start(Thread.currentThread());

        final long tPrologue = PerformanceProfiler.now();
        VRenderSystem.beginFrame();
        net.vulkanmod.vulkan.texture.SmartAnimations.beginFrame();
        VRenderSystem.clearChunkOffset();
        VRenderSystem.invalidateCameraMvp();
        invalidateLayerCache();
        ChunkArena.tickAll();
        mdiCmdCount = 0;
        mdiFrameCursor = 0;

        // Apply any deferred descriptor-set invalidation (texture swap, UBO resize) now that
        // we are between frames.
        Pipeline.processPendingDescriptorInvalidation();

        // Retire the transfer command buffers/fences submitted last frame. Without this the
        // TransferQueue pool only recycles on swap chain recreation, so a session leaks one
        // VkCommandBuffer + VkFence per texture upload until the driver runs out of handles.
        if (PerformanceProfiler.ENABLED) {
            PerformanceProfiler.framePrologueNs += PerformanceProfiler.now() - tPrologue;
        }

        // Blocks on last frame's texture and buffer transfers. Timed separately because a stall
        // here is the GPU holding the client thread up, not work the client thread is doing.
        final long tTransfer = PerformanceProfiler.now();
        Synchronization.waitFences();
        if (PerformanceProfiler.ENABLED) {
            PerformanceProfiler.transferFenceWaitNs += PerformanceProfiler.now() - tTransfer;
        }

        // Everything uploaded during this frame goes into one command buffer, closed and submitted
        // just before the frame is submitted. Opened after waitFences, which recycles the pool.
        TransferQueue.startRecording();

        // Acquire swap chain image BEFORE recording commands
        final long tAcquire = PerformanceProfiler.now();
        try (MemoryStack stack = stackPush()) {
            IntBuffer pImageIndex = stack.mallocInt(1);
            int vkResult = vkAcquireNextImageKHR(device, Vulkan.getSwapChain(), VUtil.UINT64_MAX,
                    imageAvailableSemaphores.get(currentFrame), VK_NULL_HANDLE, pImageIndex);

            if (vkResult == VK_ERROR_OUT_OF_DATE_KHR || vkResult == VK_SUBOPTIMAL_KHR || shouldRecreate) {
                shouldRecreate = false;
                recreateSwapChain();
                skipRendering = true;
                return;
            } else if (vkResult != VK_SUCCESS) {
                throw new RuntimeException("Cannot get image: " + vkResult);
            }
            imageIndex = pImageIndex.get(0);
        }
        if (PerformanceProfiler.ENABLED) PerformanceProfiler.acquireImageNs += PerformanceProfiler.now() - tAcquire;

        MemoryManager.freeBuffers();

        final long tDesc = PerformanceProfiler.now();
        resetDescriptors();
        if (PerformanceProfiler.ENABLED) PerformanceProfiler.resetDescriptorsNs += PerformanceProfiler.now() - tDesc;

        final long tCmdSetup = PerformanceProfiler.now();
        // Normally no explicit reset: the pool is created with RESET_COMMAND_BUFFER_BIT, so
        // vkBeginCommandBuffer on a buffer in the executable state resets it implicitly. Calling
        // vkResetCommandBuffer first made the driver do that work twice every frame.
        if (LEGACY_SUBMIT) vkResetCommandBuffer(commandBuffers.get(imageIndex), 0);

        try (MemoryStack stack = stackPush()) {
            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.callocStack(stack);
            beginInfo.sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);
            // This buffer is re-recorded from scratch and submitted exactly once every frame,
            // which is precisely what ONE_TIME_SUBMIT declares. Without it the driver has to
            // assume the recording may be submitted again, so it cannot recycle the command
            // memory it just consumed: the cost showed up as 96 us in vkBeginCommandBuffer and
            // 128 us in vkQueueSubmit per frame, both of which are fixed costs that do not care
            // how much is in the scene.
            if (!LEGACY_SUBMIT) beginInfo.flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);

            VkRenderPassBeginInfo renderPassInfo = VkRenderPassBeginInfo.callocStack(stack);
            renderPassInfo.sType(VK_STRUCTURE_TYPE_RENDER_PASS_BEGIN_INFO);
            renderPassInfo.renderPass(getRenderPass());

            VkRect2D renderArea = VkRect2D.callocStack(stack);
            renderArea.offset(VkOffset2D.callocStack(stack).set(0, 0));
            renderArea.extent(getSwapchainExtent());
            renderPassInfo.renderArea(renderArea);

            VkClearValue.Buffer clearValues = VkClearValue.callocStack(2, stack);
            clearValues.get(0).color().float32(stack.floats(0.0f, 0.0f, 0.0f, 1.0f));
            clearValues.get(1).depthStencil().set(1.0f, 0);
            renderPassInfo.pClearValues(clearValues);

            VkCommandBuffer commandBuffer = commandBuffers.get(imageIndex);

            int err = vkBeginCommandBuffer(commandBuffer, beginInfo);
            if (err != VK_SUCCESS) {
                throw new RuntimeException("Failed to begin recording command buffer:" + err);
            }

            PerformanceProfiler.beginGpuFrame(commandBuffer, currentFrame);

            // (The GPU occlusion compute dispatch that used to sit here was dead: nothing ever
            // called OcclusionCullingCompute.addSection(), so getSectionCount() was always 0 and
            // the guard never passed. Chunk occlusion comes from Minecraft's own visibility
            // graph in setupTerrain; see the note on OcclusionCullingCompute.)

            renderPassInfo.framebuffer(getSwapChainFramebuffers().get(imageIndex));

            vkCmdBeginRenderPass(commandBuffer, renderPassInfo, VK_SUBPASS_CONTENTS_INLINE);

            VkViewport.Buffer pViewport = Vulkan.viewport(stack);
            vkCmdSetViewport(commandBuffer, 0, pViewport);

            VkRect2D.Buffer pScissor = Vulkan.scissor(stack);
            vkCmdSetScissor(commandBuffer, 0, pScissor);

            vkCmdSetDepthBias(commandBuffer, 0.0F, 0.0F, 0.0F);

            activeCommandBuffers[imageIndex] = true;
            renderPassStarted = true;
        }
        if (PerformanceProfiler.ENABLED) {
            PerformanceProfiler.cmdBufferSetupNs += PerformanceProfiler.now() - tCmdSetup;
        }

        if (PerformanceProfiler.ENABLED) {
            PerformanceProfiler.initiateRenderPassTotalNs += PerformanceProfiler.now() - tPassStart;
        }
    }

    public void endRenderPass() {
        if (skipRendering) return;
        if (!renderPassStarted) return;

        // Emit anything still sitting in the terrain batch before the pass closes.
        flushTerrainBatch();

        // Flush entity instanced rendering before ending the pass
        EntityInstanceRenderer.getInstance().flushFrame(this);

        // Invalidate per-layer cache at end of render pass
        invalidateLayerCache();

        final long tEnd = PerformanceProfiler.now();
        VkCommandBuffer commandBuffer = commandBuffers.get(imageIndex);

        vkCmdEndRenderPass(commandBuffer);

        PerformanceProfiler.endGpuFrame(commandBuffer, currentFrame);

        int result = vkEndCommandBuffer(commandBuffer);
        if (result != VK_SUCCESS) {
            throw new RuntimeException("Failed to record command buffer:" + result);
        }

        activeCommandBuffers[imageIndex] = false;
        currentIndex = 0;

        this.vertexBuffers[currentFrame].reset();
        this.uniformBuffers.reset();
        Vulkan.getStagingBuffer(currentFrame).reset();

        if (PerformanceProfiler.ENABLED) PerformanceProfiler.endRenderPassNs += PerformanceProfiler.now() - tEnd;
    }

    private void allocateCommandBuffers() {
        commandBuffers = new ArrayList<>(commandBuffersCount);

        try (MemoryStack stack = stackPush()) {
            VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.callocStack(stack);
            allocInfo.sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO);
            allocInfo.commandPool(getCommandPool());
            allocInfo.level(VK_COMMAND_BUFFER_LEVEL_PRIMARY);
            allocInfo.commandBufferCount(commandBuffersCount);

            PointerBuffer pCommandBuffers = stack.mallocPointer(commandBuffersCount);

            if (vkAllocateCommandBuffers(device, allocInfo, pCommandBuffers) != VK_SUCCESS) {
                throw new RuntimeException("Failed to allocate command buffers");
            }

            for (int i = 0; i < commandBuffersCount; i++) {
                commandBuffers.add(new VkCommandBuffer(pCommandBuffers.get(i), device));
            }
        }
    }

    private static void trackPipeline(Pipeline p) {
        // Identity scan — typically 2-5 entries, much faster than HashSet
        for (int i = 0, n = usedPipelines.size(); i < n; i++) {
            if (usedPipelines.get(i) == p) return;
        }
        usedPipelines.add(p);
    }

    private void resetDescriptors() {
        for (int i = 0, n = usedPipelines.size(); i < n; i++) {
            usedPipelines.get(i).resetDescriptorPool(imageIndex);
        }
        usedPipelines.clear();
    }

    private void assertCommandBufferState() {
        if (!activeCommandBuffers[imageIndex]) throw new RuntimeException("CommandBuffer not active.");
    }

    private void createSyncObjects() {
        final int frameNum = getSwapChainImages().size();

        imageAvailableSemaphores = new ArrayList<>(frameNum);
        renderFinishedSemaphores = new ArrayList<>(frameNum);
        inFlightFences = new ArrayList<>(frameNum);

        try (MemoryStack stack = stackPush()) {
            VkSemaphoreCreateInfo semaphoreInfo = VkSemaphoreCreateInfo.callocStack(stack);
            semaphoreInfo.sType(VK_STRUCTURE_TYPE_SEMAPHORE_CREATE_INFO);

            VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.callocStack(stack);
            fenceInfo.sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO);
            fenceInfo.flags(VK_FENCE_CREATE_SIGNALED_BIT);

            LongBuffer pImageAvailableSemaphore = stack.mallocLong(1);
            LongBuffer pRenderFinishedSemaphore = stack.mallocLong(1);
            LongBuffer pFence = stack.mallocLong(1);

            for (int i = 0; i < frameNum; i++) {
                if (vkCreateSemaphore(device, semaphoreInfo, null, pImageAvailableSemaphore) != VK_SUCCESS
                        || vkCreateSemaphore(device, semaphoreInfo, null, pRenderFinishedSemaphore) != VK_SUCCESS
                        || vkCreateFence(device, fenceInfo, null, pFence) != VK_SUCCESS) {
                    throw new RuntimeException("Failed to create synchronization objects for the frame " + i);
                }

                imageAvailableSemaphores.add(pImageAvailableSemaphore.get(0));
                renderFinishedSemaphores.add(pRenderFinishedSemaphore.get(0));
                inFlightFences.add(pFence.get(0));
            }
        }
    }

    public static Drawer getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new Drawer();
        }
        return INSTANCE;
    }

    public static int getCurrentFrame() { return currentFrame; }

    /** @return the pre-pass command buffer for this frame, or null when culling is off. */
    private VkCommandBuffer recordOcclusionPass() {
        if (!net.vulkanmod.vulkan.compute.HZBCulling.getInstance().isReady()) return null;
        if (indirectBuffers == null || mdiFrameCursor <= 0) return null;

        // Depth attachments are picked by swapchain image, not by frame-in-flight index, so the
        // pyramid has to be reduced from the image the previous frame actually rendered into.
        // Reading by frame index sampled whichever attachment happened to share that slot.
        int prev = prevImageIndex;
        if (prev < 0 || prev >= Vulkan.getDepthImageCount() || prev == imageIndex) return null;

        java.nio.ByteBuffer cameraMvp = VRenderSystem.getCameraMvpBytes();
        if (cameraMvp == null) return null;

        return hzbPass.record(currentFrame,
                Vulkan.getDepthImage(prev), Vulkan.getDepthImageView(prev),
                indirectBuffers[currentFrame].getId(), instanceBuffers[currentFrame].getId(),
                mdiFrameCursor, cameraMvp);
    }

    private void drawFrame() {
        final long tDraw = PerformanceProfiler.now();
        try (MemoryStack stack = stackPush()) {

            VkSubmitInfo submitInfo = VkSubmitInfo.callocStack(stack);
            submitInfo.sType(VK_STRUCTURE_TYPE_SUBMIT_INFO);
            submitInfo.waitSemaphoreCount(1);
            submitInfo.pWaitSemaphores(stack.longs(imageAvailableSemaphores.get(currentFrame)));
            submitInfo.pWaitDstStageMask(stack.ints(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT));
            submitInfo.pSignalSemaphores(stack.longs(renderFinishedSemaphores.get(currentFrame)));

            // The occlusion pass has to run before the frame's indirect draws read the commands
            // it rewrites, and compute cannot be dispatched inside a render pass -- so it goes in
            // its own buffer, submitted first. By now the CPU has written every command, so the
            // cull sees the complete batch.
            // Closed before the frame's own submit so the uploads are ahead of the draws that
            // read them in the graphics queue's ordering.
            TransferQueue.endRecording();

            final long tHzb = PerformanceProfiler.now();
            hzbPass.prepare(MAX_FRAMES_IN_FLIGHT);
            VkCommandBuffer hzbCmd = recordOcclusionPass();
            prevImageIndex = imageIndex;
            if (PerformanceProfiler.ENABLED) {
                PerformanceProfiler.hzbPrepNs += PerformanceProfiler.now() - tHzb;
            }
            if (hzbCmd != null) {
                submitInfo.pCommandBuffers(stack.pointers(hzbCmd, commandBuffers.get(imageIndex)));
            } else {
                submitInfo.pCommandBuffers(stack.pointers(commandBuffers.get(imageIndex)));
            }

            vkResetFences(device, stack.longs(inFlightFences.get(currentFrame)));

            final long tSubmit = PerformanceProfiler.now();
            int vkResult;
            if ((vkResult = vkQueueSubmit(getGraphicsQueue(), submitInfo, inFlightFences.get(currentFrame))) != VK_SUCCESS) {
                vkResetFences(device, stack.longs(inFlightFences.get(currentFrame)));
                throw new RuntimeException("Failed to submit draw command buffer: " + vkResult);
            }
            if (PerformanceProfiler.ENABLED) {
                PerformanceProfiler.drawFrameSubmitNs += PerformanceProfiler.now() - tSubmit;
            }

            VkPresentInfoKHR presentInfo = VkPresentInfoKHR.callocStack(stack);
            presentInfo.sType(VK_STRUCTURE_TYPE_PRESENT_INFO_KHR);
            presentInfo.pWaitSemaphores(stack.longs(renderFinishedSemaphores.get(currentFrame)));
            presentInfo.swapchainCount(1);
            presentInfo.pSwapchains(stack.longs(getSwapChain()));
            presentInfo.pImageIndices(stack.ints(imageIndex));

            final long tPresent = PerformanceProfiler.now();
            int presentResult = vkQueuePresentKHR(getPresentQueue(), presentInfo);
            if (PerformanceProfiler.ENABLED) {
                PerformanceProfiler.drawFramePresentNs += PerformanceProfiler.now() - tPresent;
            }

            if (presentResult == VK_ERROR_OUT_OF_DATE_KHR || presentResult == VK_SUBOPTIMAL_KHR || shouldRecreate) {
                shouldRecreate = false;
                recreateSwapChain();
                return;
            } else if (presentResult != VK_SUCCESS) {
                throw new RuntimeException("Failed to present swap chain image");
            }

            currentFrame = (currentFrame + 1) % MAX_FRAMES_IN_FLIGHT;
        }

        if (PerformanceProfiler.ENABLED) {
            PerformanceProfiler.drawFrameTotalNs += PerformanceProfiler.now() - tDraw;
            PerformanceProfiler.endFrame();
        }
    }

    private void recreateSwapChain() {
        // Close any open transfer recording first: the wait below recycles the transfer pool, and
        // a buffer still being recorded into would be handed out again underneath us.
        TransferQueue.endRecording();

        // Wait only for in-flight frames instead of vkDeviceWaitIdle (which kills ALL GPU work)
        for (int i = 0; i < inFlightFences.size(); i++) {
            vkWaitForFences(device, inFlightFences.get(i), true, VUtil.UINT64_MAX);
        }
        Synchronization.waitFences();

        net.vulkanmod.vulkan.texture.GLTextureCache.handleResize();

        for (int i = 0; i < getSwapChainImages().size(); ++i) {
            vkDestroyFence(device, inFlightFences.get(i), null);
            vkDestroySemaphore(device, imageAvailableSemaphores.get(i), null);
            vkDestroySemaphore(device, renderFinishedSemaphores.get(i), null);
        }

        commandBuffers.forEach(commandBuffer -> vkResetCommandBuffer(commandBuffer, 0));

        createSyncObjects();

        PerformanceProfiler.initGpuTimers(MAX_FRAMES_IN_FLIGHT);

        if (net.vulkanmod.vulkan.compute.HZBCulling.ENABLED) {
            // resize(), not init(): the extent here is the real one, while the extent at startup
            // is the default window size. A pyramid built for that covered a corner of the screen.
            net.vulkanmod.vulkan.compute.HZBCulling.getInstance()
                    .resize(getSwapchainExtent().width(), getSwapchainExtent().height());
            hzbPass.prepare(MAX_FRAMES_IN_FLIGHT);
        }

        Vulkan.recreateSwapChain();
        currentFrame = 0;
    }

    public void rebuildInstance() {
        vkDeviceWaitIdle(device);
        Vulkan.recreateSwapChain();
        INSTANCE = new Drawer();
    }

    public AutoIndexBuffer getQuadsIndexBuffer() {
        return quadsIndexBuffer;
    }

    public AutoIndexBuffer getTriangleFanIndexBuffer() {
        return triangleFanIndexBuffer;
    }

    private void draw(VertexBuffer vertexBuffer, IndexBuffer indexBuffer, int indexCount) {
        Pipeline boundPipeline = currentBoundPipeline;
        if (boundPipeline == null) throw new RuntimeException("No pipeline bound");
        trackPipeline(boundPipeline);
        bindPipeline(boundPipeline);

        uploadBindAndDraw(boundPipeline, vertexBuffer, indexBuffer, indexCount);
    }

    private void drawAutoIndexed(ByteBuffer buffer, VertexBuffer vertexBuffer, IndexBuffer indexBuffer, int drawMode, VertexFormat vertexFormat, int vertexCount) {
        final long tPath = PerformanceProfiler.now();
        final long tCopy = PerformanceProfiler.now();
        vertexBuffer.copyToVertexBuffer(vertexFormat.getVertexSize(), vertexCount, buffer);
        if (PerformanceProfiler.ENABLED) {
            PerformanceProfiler.vertexCopyNs += PerformanceProfiler.now() - tCopy;
            PerformanceProfiler.vertexCopyBytes += (long) vertexFormat.getVertexSize() * vertexCount;
        }

        Pipeline boundPipeline = currentBoundPipeline;
        if (boundPipeline == null) throw new RuntimeException("No pipeline bound");
        trackPipeline(boundPipeline);
        bindPipeline(boundPipeline);

        int indexCount;
        if (drawMode == 7) indexCount = vertexCount * 3 / 2;
        else if (drawMode == 6 || drawMode == 5) indexCount = (vertexCount - 2) * 3;
        else if (drawMode == 4) indexCount = vertexCount;
        else throw new RuntimeException("unknown drawMode: " + drawMode);

        uploadBindAndDraw(boundPipeline, vertexBuffer, indexBuffer, indexCount);
        if (PerformanceProfiler.ENABLED) {
            PerformanceProfiler.drawPathTotalNs += PerformanceProfiler.now() - tPath;
        }
    }


    /**
     * Batched chunk draw. Appends one indirect command and returns without touching the command
     * buffer; the whole layer is emitted by {@link #flushTerrainBatch()}.
     */
    public void drawChunkBatched(ChunkArena.Slot slot, int vertexCount, VertexFormat vertexFormat,
                                 float offsetX, float offsetY, float offsetZ) {
        assertCommandBufferState();
        if (vertexCount <= 0) return;

        Pipeline pipeline = currentBoundPipeline;
        if (pipeline == null) throw new RuntimeException("No pipeline bound");

        quadsIndexBuffer.checkCapacity(vertexCount);
        IndexBuffer ib = quadsIndexBuffer.getIndexBuffer();

        if (!appendToBatch(pipeline, slot.getBuffer(), ib, vertexCount / 4 * 6, 0, slot.baseVertex,
                offsetX, offsetY, offsetZ)) {
            drawArenaDirect(pipeline, slot.getBuffer(), ib, vertexCount / 4 * 6, slot.baseVertex);
        }
        currentIndex++;
    }

    /**
     * Draws one chunk straight out of the arena, no batching. Used when the batch cannot take
     * the draw (frame command storage exhausted) and when the caller is outside a world render
     * pass, where the offset rides in the push constant instead of the instance binding.
     */
    public void drawChunkFromArena(ChunkArena.Slot slot, int vertexCount) {
        assertCommandBufferState();
        if (vertexCount <= 0) return;
        flushTerrainBatch();

        Pipeline pipeline = currentBoundPipeline;
        if (pipeline == null) throw new RuntimeException("No pipeline bound");

        quadsIndexBuffer.checkCapacity(vertexCount);
        drawArenaDirect(pipeline, slot.getBuffer(), quadsIndexBuffer.getIndexBuffer(),
                vertexCount / 4 * 6, slot.baseVertex);
        currentIndex++;
    }

    private void drawArenaDirect(Pipeline pipeline, VertexBuffer arenaBuffer, IndexBuffer ib,
                                 int indexCount, int baseVertex) {
        if (indexCount <= 0) return;
        VkCommandBuffer commandBuffer = commandBuffers.get(imageIndex);

        bindPipeline(pipeline);
        bindUniformsAndDescriptors(pipeline);
        pushChunkOffsetIfNeeded(pipeline, commandBuffer);
        bindZeroInstanceBuffer(pipeline, commandBuffer);

        long arenaId = arenaBuffer.getId();
        if (arenaId != lastBoundVB || lastBoundVBOffset != 0) {
            drawVBId.put(0, arenaId);
            drawVBOffset.put(0, 0L);
            vkCmdBindVertexBuffers(commandBuffer, 0, drawVBId, drawVBOffset);
            lastBoundVB = arenaId;
            lastBoundVBOffset = 0;
        }

        if (ib.getId() != lastBoundIB) {
            vkCmdBindIndexBuffer(commandBuffer, ib.getId(), ib.getOffset(), VK_INDEX_TYPE_UINT16);
            lastBoundIB = ib.getId();
        }

        vkCmdDrawIndexed(commandBuffer, indexCount, 1, 0, baseVertex, 0);
        if (PerformanceProfiler.ENABLED) PerformanceProfiler.drawCallCount++;
    }

    /** True when this draw can join the batched terrain pass. */
    public boolean isBatchingAvailable() {
        return indirectBuffers != null;
    }

    /**
     * Appends one chunk to the terrain batch. Returns false when it cannot be batched (batch
     * full, different arena/pipeline/index buffer), in which case the caller draws directly.
     *
     * <p>The batch is flushed lazily -- on a state change, on any non-batched draw, and at the
     * end of the render pass -- so an entire render layer normally collapses into a single
     * vkCmdDrawIndexedIndirect.
     */
    private boolean appendToBatch(Pipeline pipeline, VertexBuffer arenaBuffer, IndexBuffer indexBuffer,
                                  int indexCount, int firstIndex, int baseVertex,
                                  float offsetX, float offsetY, float offsetZ) {
        if (indirectBuffers == null || indexCount <= 0 || arenaBuffer == null) return false;

        // The pipeline handle encodes blend/depth/colour-mask state, so comparing handles also
        // catches a state change mid-layer.
        cachedPipelineState.update(currentBlendState, VRenderSystem.getDepthState(),
                currentLogicOpState, VRenderSystem.getColorMask());
        long handle = pipeline.getHandle(cachedPipelineState);
        VulkanImage boundTex = VTextureSelector.getBoundTexture();
        VulkanImage lightTex = VTextureSelector.getLightTexture();

        if (mdiCmdCount > 0
                && (arenaBuffer != mdiArena || pipeline != mdiPipeline || handle != mdiPipelineHandle
                    || boundTex != mdiBoundTex || lightTex != mdiLightTex
                    || indexBuffer.getId() != mdiIndexBuffer || indexBuffer.getOffset() != mdiIndexOffset)) {
            flushTerrainBatch();
        }

        if (mdiFrameCursor >= MAX_INDIRECT_CMDS) {
            // Frame's command storage exhausted; the caller falls back to a direct draw.
            return false;
        }

        if (mdiCmdCount == 0) {
            mdiBatchBase = mdiFrameCursor;
            mdiArena = arenaBuffer;
            mdiPipeline = pipeline;
            mdiPipelineHandle = handle;
            mdiBoundTex = boundTex;
            mdiLightTex = lightTex;
            mdiIndexBuffer = indexBuffer.getId();
            mdiIndexOffset = indexBuffer.getOffset();
            snapshotUbos(pipeline);
        }

        int i = mdiFrameCursor;

        ByteBuffer cmds = indirectBuffers[currentFrame].map();
        int base = i * INDIRECT_CMD_SIZE;
        cmds.putInt(base, indexCount);      // indexCount
        cmds.putInt(base + 4, 1);           // instanceCount
        cmds.putInt(base + 8, firstIndex);  // firstIndex
        cmds.putInt(base + 12, baseVertex); // vertexOffset
        cmds.putInt(base + 16, i - mdiBatchBase); // firstInstance, relative to this batch's slice

        ByteBuffer inst = instanceBuffers[currentFrame].map();
        int ibase = i * Pipeline.CHUNK_OFFSET_STRIDE;
        inst.putFloat(ibase, offsetX);
        inst.putFloat(ibase + 4, offsetY);
        inst.putFloat(ibase + 8, offsetZ);
        inst.putFloat(ibase + 12, 0.0f);

        mdiCmdCount++;
        mdiFrameCursor++;
        return true;
    }

    /** Freezes the pipeline's current UBO contents for later replay by the flush. */
    private void snapshotUbos(Pipeline pipeline) {
        List<UBO> ubos = pipeline.getUBOs();
        mdiUboCount = Math.min(ubos.size(), mdiUboOffsets.length);

        int total = 0;
        for (int i = 0; i < mdiUboCount; i++) {
            UBO ubo = ubos.get(i);
            ubo.update();
            mdiUboOffsets[i] = total;
            mdiUboSizes[i] = ubo.getBuffer().remaining();
            total += mdiUboSizes[i];
        }

        if (mdiUboSnapshot == null || mdiUboSnapshot.capacity() < total) {
            if (mdiUboSnapshot != null) MemoryUtil.memFree(mdiUboSnapshot);
            mdiUboSnapshot = MemoryUtil.memAlloc(Math.max(total, 256));
        }

        for (int i = 0; i < mdiUboCount; i++) {
            ByteBuffer src = ubos.get(i).getBuffer();
            int srcPos = src.position();
            for (int j = 0; j < mdiUboSizes[i]; j++) {
                mdiUboSnapshot.put(mdiUboOffsets[i] + j, src.get(srcPos + j));
            }
        }
    }

    /** Emits the accumulated terrain batch as a single indirect multi-draw. */
    public void flushTerrainBatch() {
        if (mdiCmdCount == 0) return;

        int count = mdiCmdCount;
        Pipeline pipeline = mdiPipeline;
        VertexBuffer arenaBuffer = mdiArena;
        long handle = mdiPipelineHandle;

        // Reset first: the binds below must not recurse back into a flush.
        mdiCmdCount = 0;
        mdiArena = null;
        mdiPipeline = null;

        VkCommandBuffer commandBuffer = commandBuffers.get(imageIndex);

        // Restore the texture bindings this batch was recorded against; whoever triggered the
        // flush may already have bound theirs.
        VulkanImage callerTex = VTextureSelector.getBoundTexture();
        VulkanImage callerLight = VTextureSelector.getLightTexture();
        VTextureSelector.bindTexture(mdiBoundTex);
        VTextureSelector.setLightTexture(mdiLightTex);

        // Likewise the bound pipeline. Callers select their pipeline *before* calling into a
        // draw method, and the draw method flushes on entry -- so clobbering this leaves the
        // caller's geometry to be drawn with the terrain pipeline: wrong attribute layout, wrong
        // samplers, garbage texture coordinates.
        Pipeline callerPipeline = currentBoundPipeline;

        setBoundPipeline(pipeline);
        // Bind the exact handle captured at batch time rather than re-deriving it from the
        // current blend/depth state, which may have moved on.
        if (handle != lastBoundPipelineId || pipeline != lastBoundPipelineHandle) {
            vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, handle);
            trackPipeline(pipeline);
            lastBoundPipelineHandle = pipeline;
            lastBoundPipelineId = handle;
        }
        // Replay the frozen UBO bytes rather than calling ubo.update(), which would pick up
        // whatever state the caller that triggered this flush has installed.
        for (int i = 0; i < mdiUboCount; i++) {
            int dynamicOffset = uniformBuffers.getUsedBytes();
            drawDynOffsets.put(i, dynamicOffset);
            mdiUboSnapshot.limit(mdiUboOffsets[i] + mdiUboSizes[i]).position(mdiUboOffsets[i]);
            uniformBuffers.uploadUBO(mdiUboSnapshot, dynamicOffset, imageIndex);
        }
        mdiUboSnapshot.clear();

        long descriptorSet = pipeline.getOrCreateDescriptorSet(imageIndex, uniformBuffers);
        drawDS.put(0, descriptorSet);
        IntBuffer pDynOffsets = null;
        if (mdiUboCount > 0) {
            drawDynOffsets.limit(mdiUboCount);
            pDynOffsets = drawDynOffsets;
        }
        vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS,
                pipeline.getLayout(), 0, drawDS, pDynOffsets);
        if (mdiUboCount > 0) {
            drawDynOffsets.limit(drawDynOffsets.capacity());
        }
        lastBoundDS = descriptorSet;

        // The redundancy cache tracks what ubo.update() last produced; this upload bypassed it.
        lastUboByteCount = -1;
        lastUboPipeline = null;

        // Zero the push constant: in the batched path the offset comes from the instance
        // binding, and the two are summed in the shader.
        VRenderSystem.clearChunkOffset();
        pushChunkOffsetIfNeeded(pipeline, commandBuffer);

        long arenaId = arenaBuffer.getId();
        drawVBId.put(0, arenaId);
        drawVBOffset.put(0, 0L);
        vkCmdBindVertexBuffers(commandBuffer, 0, drawVBId, drawVBOffset);
        lastBoundVB = arenaId;
        lastBoundVBOffset = 0;

        drawVBId.put(0, instanceBuffers[currentFrame].getId());
        drawVBOffset.put(0, (long) mdiBatchBase * Pipeline.CHUNK_OFFSET_STRIDE);
        vkCmdBindVertexBuffers(commandBuffer, 1, drawVBId, drawVBOffset);

        // Bind the live quad index buffer rather than the handle captured when the batch
        // opened: checkCapacity() may have grown it mid-batch, retiring the old VkBuffer. The
        // quad index pattern is position independent, so the grown buffer is a valid superset.
        IndexBuffer ib = quadsIndexBuffer.getIndexBuffer();
        vkCmdBindIndexBuffer(commandBuffer, ib.getId(), ib.getOffset(), VK_INDEX_TYPE_UINT16);
        lastBoundIB = ib.getId();

        vkCmdDrawIndexedIndirect(commandBuffer, indirectBuffers[currentFrame].getId(),
                (long) mdiBatchBase * INDIRECT_CMD_SIZE, count, INDIRECT_CMD_SIZE);

        VTextureSelector.bindTexture(callerTex);
        VTextureSelector.setLightTexture(callerLight);
        setBoundPipeline(callerPipeline);

        if (PerformanceProfiler.ENABLED) {
            indirectFlushCount++;
            indirectCmdTotal += count;
            PerformanceProfiler.drawCallCount++;
        }
    }

    /**
     * Binds the zero instance buffer at binding 1. Pipelines that declare the instanced chunk
     * offset require binding 1 to be valid for every draw, batched or not.
     */
    private void bindZeroInstanceBuffer(Pipeline pipeline, VkCommandBuffer commandBuffer) {
        if (zeroInstanceBuffer == null || !pipeline.hasInstancedChunkOffset()) return;
        drawVBId.put(0, zeroInstanceBuffer.getId());
        drawVBOffset.put(0, 0L);
        vkCmdBindVertexBuffers(commandBuffer, 1, drawVBId, drawVBOffset);
    }

    public void uploadAndBindUBOs(Pipeline pipeline) {
        bindUniformsAndDescriptors(pipeline);
    }

    /**
     * Pushes the current ChunkOffset for pipelines that declare it. Terrain draws set a real
     * offset via VRenderSystem.prepareChunkDraw(); everything else leaves it at (0,0,0), which
     * reproduces the old "translation folded into MVP" behaviour exactly.
     */
    private void pushChunkOffsetIfNeeded(Pipeline pipeline, VkCommandBuffer commandBuffer) {
        if (!pipeline.hasPushConstant()) return;
        PushConstant pc = pipeline.getPushConstant();
        pc.update();
        vkCmdPushConstants(commandBuffer, pipeline.getLayout(), VK_SHADER_STAGE_VERTEX_BIT, 0, pc.getBuffer());
    }

    // Combined UBO upload + vertex/index bind + draw — zero per-draw allocation.
    // Uses pre-allocated native buffers + dynamic UBO offsets + redundant bind elimination.
    private void uploadBindAndDraw(Pipeline pipeline, VertexBuffer vertexBuffer, IndexBuffer indexBuffer, int indexCount) {
        VkCommandBuffer commandBuffer = commandBuffers.get(imageIndex);

        bindUniformsAndDescriptors(pipeline);
        pushChunkOffsetIfNeeded(pipeline, commandBuffer);
        bindZeroInstanceBuffer(pipeline, commandBuffer);

        // Skip redundant vertex buffer binds
        long vbId = vertexBuffer.getId();
        long vbOffset = vertexBuffer.getOffset();
        if (vbId != lastBoundVB || vbOffset != lastBoundVBOffset) {
            drawVBId.put(0, vbId);
            drawVBOffset.put(0, vbOffset);
            vkCmdBindVertexBuffers(commandBuffer, 0, drawVBId, drawVBOffset);
            lastBoundVB = vbId;
            lastBoundVBOffset = vbOffset;
        }

        // Skip redundant index buffer binds (all QUADS share one IB — 99.9% skip)
        long ibId = indexBuffer.getId();
        if (ibId != lastBoundIB) {
            vkCmdBindIndexBuffer(commandBuffer, ibId, indexBuffer.getOffset(), VK_INDEX_TYPE_UINT16);
            lastBoundIB = ibId;
        }

        vkCmdDrawIndexed(commandBuffer, indexCount, 1, 0, 0, 0);
        if (PerformanceProfiler.ENABLED) PerformanceProfiler.drawCallCount++;
    }

    public void drawIndexed(VertexBuffer vertexBuffer, IndexBuffer indexBuffer, int indexCount) {
        flushTerrainBatch();
        try (MemoryStack stack = stackPush()) {
            VkCommandBuffer commandBuffer = commandBuffers.get(imageIndex);

            LongBuffer vertexBuffers = stack.longs(vertexBuffer.getId());
            LongBuffer offsets = stack.longs(vertexBuffer.getOffset());
            vkCmdBindVertexBuffers(commandBuffer, 0, vertexBuffers, offsets);

            vkCmdBindIndexBuffer(commandBuffer, indexBuffer.getId(), indexBuffer.getOffset(), VK_INDEX_TYPE_UINT16);
            vkCmdDrawIndexed(commandBuffer, indexCount, 1, 0, 0, 0);
        }
    }

    public void bindPipeline(Pipeline pipeline) {
        cachedPipelineState.update(currentBlendState, VRenderSystem.getDepthState(), currentLogicOpState, VRenderSystem.getColorMask());
        long handle = pipeline.getHandle(cachedPipelineState);

        // Skip bind if same pipeline+state already bound
        if (handle == lastBoundPipelineId && pipeline == lastBoundPipelineHandle) {
            return;
        }

        VkCommandBuffer commandBuffer = commandBuffers.get(imageIndex);
        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_GRAPHICS, handle);
        lastBoundPipelineHandle = pipeline;
        lastBoundPipelineId = handle;
        trackPipeline(pipeline);
    }

    public void pushConstants(Pipeline pipeline) {
        VkCommandBuffer commandBuffer = commandBuffers.get(imageIndex);

        PushConstant pushConstant = pipeline.getPushConstant();
        pushConstant.update();
        vkCmdPushConstants(commandBuffer, pipeline.getLayout(), VK_SHADER_STAGE_VERTEX_BIT, 0, pushConstant.getBuffer());
    }

    public static void setDepthBias(float units, float factor) {
        VkCommandBuffer commandBuffer = commandBuffers.get(INSTANCE.imageIndex);
        vkCmdSetDepthBias(commandBuffer, units, 0.0f, factor);
    }

    public static void clearAttachments(int v) {
        if (skipRendering) return;
        if (INSTANCE != null) INSTANCE.flushTerrainBatch();

        VkCommandBuffer commandBuffer = commandBuffers.get(INSTANCE.imageIndex);

        try (MemoryStack stack = stackPush()) {
            VkClearValue colorValue = VkClearValue.callocStack(stack);
            colorValue.color().float32(VRenderSystem.clearColor);

            VkClearValue depthValue = VkClearValue.callocStack(stack);
            depthValue.depthStencil().depth(VRenderSystem.clearDepth);

            int attachmentsCount;
            VkClearAttachment.Buffer pAttachments;
            if (v == 0x100) {
                attachmentsCount = 1;
                pAttachments = VkClearAttachment.callocStack(attachmentsCount, stack);
                VkClearAttachment clearDepth = pAttachments.get(0);
                clearDepth.aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT);
                clearDepth.clearValue(depthValue);
            } else if (v == 0x4000) {
                attachmentsCount = 1;
                pAttachments = VkClearAttachment.callocStack(attachmentsCount, stack);
                VkClearAttachment clearColor = pAttachments.get(0);
                clearColor.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
                clearColor.colorAttachment(0);
                clearColor.clearValue(colorValue);
            } else if (v == 0x4100) {
                attachmentsCount = 2;
                pAttachments = VkClearAttachment.callocStack(attachmentsCount, stack);
                VkClearAttachment clearColor = pAttachments.get(0);
                clearColor.aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
                clearColor.clearValue(colorValue);
                VkClearAttachment clearDepth = pAttachments.get(1);
                clearDepth.aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT);
                clearDepth.clearValue(depthValue);
            } else {
                throw new RuntimeException("unexpected value");
            }

            VkRect2D renderArea = VkRect2D.callocStack(stack);
            renderArea.offset(VkOffset2D.callocStack(stack).set(0, 0));
            renderArea.extent(getSwapchainExtent());

            VkClearRect.Buffer pRect = VkClearRect.callocStack(1, stack);
            pRect.get(0).rect(renderArea);
            pRect.get(0).layerCount(1);

            vkCmdClearAttachments(commandBuffer, pAttachments, pRect);
        }
    }

    public static void setViewport(int x, int y, int width, int height) {
        try (MemoryStack stack = stackPush()) {
            VkViewport.Buffer viewport = VkViewport.callocStack(1, stack);
            viewport.x(x);
            viewport.y(height + y);
            viewport.width(width);
            viewport.height(-height);
            viewport.minDepth(0.0f);
            viewport.maxDepth(1.0f);

            vkCmdSetViewport(commandBuffers.get(INSTANCE.imageIndex), 0, viewport);
        }
    }

    public static void pushDebugSection(String s) {}
    public static void popDebugSection() {}
    public static void popPushDebugSection(String s) {
        popDebugSection();
        pushDebugSection(s);
    }

    // --- Entity draw batch accumulation ---
    // All cuboid faces within a ModelPart share the same MVP, pipeline, and textures.
    // Instead of 6 separate draw calls per cuboid, we accumulate vertex data and
    // submit one draw call per ModelPart.

    public static void beginBatch() {
        batchActive = true;
        batchVtxCount = 0;
        batchPipelineReady = false;
    }

    public static boolean isBatching() {
        return batchActive;
    }

    public static boolean isBatchPipelineReady() {
        return batchPipelineReady;
    }

    public static int getBatchVtxCount() {
        return batchVtxCount;
    }

    public static void setupBatchPipeline(Pipeline pipeline, VertexFormat format, int drawMode) {
        batchPipeline = pipeline;
        batchFormat = format;
        batchDrawMode = drawMode;
        batchVtxSize = format.getVertexSize();
        batchPipelineReady = true;
    }

    public static void accumulateBatch(ByteBuffer data, int vtxCount) {
        int dataSize = vtxCount * batchVtxSize;
        int curSize = batchVtxCount * batchVtxSize;
        int needed = curSize + dataSize;

        if (batchBuf == null || needed > batchBufCap) {
            int newCap = Math.max(needed, Math.max(batchBufCap * 2, 32768));
            if (batchBuf != null) {
                // memRealloc can extend in-place — avoids copy when possible
                batchBuf.limit(batchBuf.capacity());
                batchBuf.position(0);
                batchBuf = MemoryUtil.memRealloc(batchBuf, newCap);
            } else {
                batchBuf = MemoryUtil.memAlloc(newCap);
            }
            batchBufCap = newCap;
        }

        batchBuf.position(curSize);
        batchBuf.put(data);
        batchVtxCount += vtxCount;
    }

    // Flush accumulated batch as one draw call.
    // Called at end of drawCuboids() in ModelPartMixin.
    public void flushBatch() {
        batchActive = false;
        if (!batchPipelineReady || batchVtxCount <= 0) return;
        if (!isRenderPassActive()) return;

        setBoundPipeline(batchPipeline);

        batchBuf.position(0);
        batchBuf.limit(batchVtxCount * batchVtxSize);

        draw(batchBuf, batchDrawMode, batchFormat, batchVtxCount);
        currentIndex++;
    }
}
