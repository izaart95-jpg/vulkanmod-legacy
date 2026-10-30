package net.vulkanmod.vulkan;

import net.vulkanmod.Initializer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkQueryPoolCreateInfo;

import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

public class PerformanceProfiler {

    /**
     * Off unless -Dvulkanmod.profiler=true. The fine grained counters below sit inside the
     * per-draw path, where a System.nanoTime() pair costs more than several of the operations
     * it is trying to measure, so nothing is instrumented at all in a normal run.
     */
    public static final boolean ENABLED = Boolean.getBoolean("vulkanmod.profiler");

    /** Fine grained per-draw timers. Skewing but occasionally necessary. */
    public static final boolean DETAILED = ENABLED && Boolean.getBoolean("vulkanmod.profiler.detailed");

    public static long now() { return ENABLED ? System.nanoTime() : 0L; }


    // ── Frame-level timing (ns, accumulated over report interval) ──
    public static long frameCount = 0;

    // initiateRenderPass breakdown
    public static long fenceWaitNs = 0;
    public static long acquireImageNs = 0;
    public static long resetDescriptorsNs = 0;
    public static long cmdBufferSetupNs = 0;
    public static long initiateRenderPassTotalNs = 0;

    // endRenderPass
    public static long endRenderPassNs = 0;

    // submitDraw / drawFrame
    public static long drawFrameSubmitNs = 0;
    public static long drawFramePresentNs = 0;
    public static long drawFrameTotalNs = 0;

    // Regions of the frame that had no timer at all, so their cost showed up only as the gap
    // between a parent total and the sum of its children.
    public static long framePrologueNs;
    public static long transferFenceWaitNs;
    public static long hzbPrepNs;
    public static long drawPathTotalNs;

    // ── Per-draw timing (ns, accumulated over report interval) ──
    public static long drawCallCount = 0;
    public static long drawPersistentCount = 0;

    // TessellatorMixin / VertexBufferMixin outer timing
    public static long tessellatorDrawTotalNs = 0;
    public static long vbMixinDrawTotalNs = 0;

    // calculateMVP
    public static long calculateMVPNs = 0;
    public static int mvpCacheHits = 0;
    public static int mvpCacheMisses = 0;
    public static long glGetFloatvNs = 0;
    public static long glReadbackCount = 0;

    // Chunk meshing, accumulated across the worker threads that build sections.
    public static final java.util.concurrent.atomic.AtomicLong meshBuildNs = new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong meshBuildBlocks = new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong meshSmoothNs = new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong meshSmoothBlocks = new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong chunkRebuilds = new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong shadowEntryCalls = new java.util.concurrent.atomic.AtomicLong();
    public static final java.util.concurrent.atomic.AtomicLong shadowRenderCalls = new java.util.concurrent.atomic.AtomicLong();
    public static long pendingVisible = 0;
    public static long pendingTotal = 0;
    public static final java.util.Set<String> chunkRebuildPositions =
            java.util.Collections.newSetFromMap(new java.util.concurrent.ConcurrentHashMap<>());
    public static long matrixMultiplyNs = 0;

    // Texture sync
    public static long textureSyncNs = 0;
    public static long lightmapSyncNs = 0;
    public static int textureSyncCacheHits = 0;
    public static int textureSyncGlReads = 0;

    // Pipeline lookup
    public static long pipelineLookupNs = 0;

    // Drawer.bindPipeline
    public static long bindPipelineNs = 0;
    public static int pipelineBindSkips = 0;
    public static int pipelineBindCalls = 0;

    // Drawer.uploadBindAndDraw breakdown
    public static long uploadBindAndDrawTotalNs = 0;
    public static long uboUpdateNs = 0;
    public static long uboUploadNs = 0;
    public static int uboUploadCount = 0;
    public static long dsGetOrCreateNs = 0;
    public static int dsCacheHits = 0;
    public static int dsCacheMisses = 0;
    public static long vkCmdBindDSNs = 0;
    public static long vkCmdBindVBNs = 0;
    public static long vkCmdBindIBNs = 0;
    public static long vkCmdDrawNs = 0;
    public static int vbBindSkips = 0;
    public static int vbBindCalls = 0;
    public static int ibBindSkips = 0;
    public static int ibBindCalls = 0;

    // Vertex data copy
    public static long vertexCopyNs = 0;
    public static long vertexCopyBytes = 0;

    // Push constants
    public static long pushConstantNs = 0;
    public static int pushConstantCount = 0;

    // Memory
    public static int vbResizeCount = 0;
    public static int ubResizeCount = 0;
    public static int dsPoolResizeCount = 0;


    // ── GPU timing (vkCmdWriteTimestamp) ──
    //
    // Two timestamps per in-flight frame, straddling the render pass. Results are read one
    // full frame late so the query never stalls the CPU waiting for the GPU. This is the only
    // way to answer the question that decides every optimisation: CPU bound or GPU bound?
    private static long queryPool = VK_NULL_HANDLE;
    private static int queryFrames = 0;
    private static float timestampPeriodNs = 0.0f;
    private static boolean[] queryWritten;

    public static long gpuTimeNs = 0;
    public static int gpuSamples = 0;

    public static void initGpuTimers(int framesInFlight) {
        if (!ENABLED || queryPool != VK_NULL_HANDLE) return;

        timestampPeriodNs = Vulkan.deviceProperties.limits().timestampPeriod();
        if (timestampPeriodNs <= 0.0f) {
            Initializer.LOGGER.warn("[Profiler] Device reports no timestamp support; GPU timing disabled");
            return;
        }

        queryFrames = framesInFlight;
        queryWritten = new boolean[framesInFlight];

        try (MemoryStack stack = stackPush()) {
            VkQueryPoolCreateInfo info = VkQueryPoolCreateInfo.callocStack(stack);
            info.sType(VK_STRUCTURE_TYPE_QUERY_POOL_CREATE_INFO);
            info.queryType(VK_QUERY_TYPE_TIMESTAMP);
            info.queryCount(framesInFlight * 2);

            LongBuffer pPool = stack.callocLong(1);
            if (vkCreateQueryPool(Vulkan.getDevice(), info, null, pPool) != VK_SUCCESS) {
                Initializer.LOGGER.warn("[Profiler] Failed to create timestamp query pool");
                return;
            }
            queryPool = pPool.get(0);
        }
        Initializer.LOGGER.info("[Profiler] GPU timing enabled (timestampPeriod={}ns)", timestampPeriodNs);
    }

    /** Reads back the previous result for this frame slot, then restarts the pair. */
    public static void beginGpuFrame(VkCommandBuffer cmd, int frame) {
        if (queryPool == VK_NULL_HANDLE) return;

        if (queryWritten[frame]) {
            try (MemoryStack stack = stackPush()) {
                LongBuffer results = stack.callocLong(2);
                // No WAIT bit: if the pair is not ready we simply skip this sample.
                int r = vkGetQueryPoolResults(Vulkan.getDevice(), queryPool, frame * 2, 2,
                        results, Long.BYTES, VK_QUERY_RESULT_64_BIT);
                if (r == VK_SUCCESS) {
                    long delta = results.get(1) - results.get(0);
                    if (delta > 0) {
                        gpuTimeNs += (long) (delta * timestampPeriodNs);
                        gpuSamples++;
                    }
                }
            }
        }

        vkCmdResetQueryPool(cmd, queryPool, frame * 2, 2);
        vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, queryPool, frame * 2);
        queryWritten[frame] = true;
    }

    public static void endGpuFrame(VkCommandBuffer cmd, int frame) {
        if (queryPool == VK_NULL_HANDLE) return;
        vkCmdWriteTimestamp(cmd, VK_PIPELINE_STAGE_BOTTOM_OF_PIPE_BIT, queryPool, frame * 2 + 1);
    }

    public static void cleanUpGpuTimers() {
        if (queryPool != VK_NULL_HANDLE) {
            vkDestroyQueryPool(Vulkan.getDevice(), queryPool, null);
            queryPool = VK_NULL_HANDLE;
        }
    }

    // ── Reporting ──
    private static long lastReportTime = System.nanoTime();
    private static final long REPORT_INTERVAL_NS = 5_000_000_000L; // 5 seconds

    public static void endFrame() {
        frameCount++;
        long now = System.nanoTime();
        if (now - lastReportTime >= REPORT_INTERVAL_NS) {
            report(now - lastReportTime);
            reset();
            lastReportTime = now;
        }
    }

    private static void report(long intervalNs) {
        if (frameCount == 0) return;

        double intervalSec = intervalNs / 1_000_000_000.0;
        double fps = frameCount / intervalSec;
        long fc = frameCount;
        long dc = drawCallCount;

        // Per-frame averages in microseconds
        double avgFrame_us = (initiateRenderPassTotalNs + endRenderPassNs + drawFrameTotalNs) / 1000.0 / fc;

        StringBuilder sb = new StringBuilder(2048);
        sb.append("\n╔══════════════════════════════════════════════════════════════════╗\n");
        sb.append(String.format("║  VULKANMOD PROFILER — %.1fs interval, %d frames, %.1f FPS         \n", intervalSec, fc, fps));
        sb.append("╠══════════════════════════════════════════════════════════════════╣\n");

        // Frame lifecycle
        sb.append("║ FRAME LIFECYCLE (avg per frame, µs):\n");
        sb.append(String.format("║   initiateRenderPass: %8.1f µs\n", initiateRenderPassTotalNs / 1000.0 / fc));
        sb.append(String.format("║     ├─ prologue:      %8.1f µs\n", framePrologueNs / 1000.0 / fc));
        sb.append(String.format("║     ├─ transferWait:  %8.1f µs\n", transferFenceWaitNs / 1000.0 / fc));
        sb.append(String.format("║     ├─ fenceWait:     %8.1f µs\n", fenceWaitNs / 1000.0 / fc));
        sb.append(String.format("║     ├─ acquireImage:  %8.1f µs\n", acquireImageNs / 1000.0 / fc));
        sb.append(String.format("║     ├─ resetDescPool: %8.1f µs\n", resetDescriptorsNs / 1000.0 / fc));
        sb.append(String.format("║     └─ cmdBufSetup:   %8.1f µs\n", cmdBufferSetupNs / 1000.0 / fc));
        sb.append(String.format("║   endRenderPass:      %8.1f µs\n", endRenderPassNs / 1000.0 / fc));
        sb.append(String.format("║   drawFrame (submit): %8.1f µs\n", drawFrameTotalNs / 1000.0 / fc));
        sb.append(String.format("║     ├─ hzbPrep:       %8.1f µs\n", hzbPrepNs / 1000.0 / fc));
        sb.append(String.format("║     ├─ queueSubmit:   %8.1f µs\n", drawFrameSubmitNs / 1000.0 / fc));
        sb.append(String.format("║     └─ present:       %8.1f µs\n", drawFramePresentNs / 1000.0 / fc));
        sb.append(String.format("║   TOTAL frame overhead:%7.1f µs\n", avgFrame_us));
        // The whole per-draw path, from the vertex copy to vkCmdDrawIndexed. This is the cost that
        // scales with draw count rather than with geometry, so it is what dominates a light scene.
        sb.append(String.format("║   DRAW PATH (all draws): %6.1f µs/frame  (%.3f µs/draw)\n",
                drawPathTotalNs / 1000.0 / fc,
                dc > 0 ? drawPathTotalNs / 1000.0 / dc : 0.0));
        sb.append(String.format("║     └─ uniforms+DS:   %8.1f µs/frame  (%.3f µs/draw)\n",
                uploadBindAndDrawTotalNs / 1000.0 / fc,
                dc > 0 ? uploadBindAndDrawTotalNs / 1000.0 / dc : 0.0));
        sb.append(String.format("║     └─ vertexCopy:    %8.1f µs/frame  (%.1f KB/frame)\n",
                vertexCopyNs / 1000.0 / fc, vertexCopyBytes / 1024.0 / fc));

        // Draw calls
        double drawsPerFrame = dc > 0 ? (double) dc / fc : 0;
        sb.append("╠──────────────────────────────────────────────────────────────────╣\n");
        sb.append(String.format("║ DRAW CALLS: %d total (%.1f/frame), %d persistent\n", dc, drawsPerFrame, drawPersistentCount));
        sb.append(String.format("║   tessellator draws: %.1f µs avg/frame\n", tessellatorDrawTotalNs / 1000.0 / fc));
        sb.append(String.format("║   GL MATRIX READBACK: %s (%d reads/frame, %.1f us/frame)\n",
                net.vulkanmod.vulkan.VRenderSystem.isSoftwareStackTrusted()
                        ? "software stack trusted -- 1 validation read per frame"
                        : "FALLBACK ACTIVE -- stack diverged, reading per chunk",
                glReadbackCount / Math.max(1, fc), glGetFloatvNs / 1000.0 / fc));
        if (Drawer.indirectFlushCount > 0) {
            sb.append(String.format("║   BATCHED TERRAIN: %.1f indirect flushes/frame, %.1f chunks/flush\n",
                    (double) Drawer.indirectFlushCount / fc,
                    (double) Drawer.indirectCmdTotal / Drawer.indirectFlushCount));
            sb.append(String.format("║     -> %.0f chunk draws collapsed into %.1f draw calls per frame\n",
                    (double) Drawer.indirectCmdTotal / fc, (double) Drawer.indirectFlushCount / fc));
        }
        sb.append(String.format("║   VB mixin draws:    %.1f µs avg/frame\n", vbMixinDrawTotalNs / 1000.0 / fc));
        long mbNs = meshBuildNs.get(), mbBlocks = meshBuildBlocks.get();
        if (mbBlocks > 0) {
            sb.append(String.format("║   CHUNK MESHING (worker threads): %.1f ms total, %d blocks, %.0f ns/block\n",
                    mbNs / 1e6, mbBlocks, (double) mbNs / mbBlocks));
            sb.append(String.format("║     -> %.1f µs of worker time per rendered frame\n", mbNs / 1000.0 / fc));
            long smNs = meshSmoothNs.get(), smBlocks = meshSmoothBlocks.get();
            long flNs = mbNs - smNs, flBlocks = mbBlocks - smBlocks;
            if (smBlocks > 0) sb.append(String.format("║     smooth (AO): %6d blocks, %.0f ns/block (%.0f%% of meshing)\n",
                    smBlocks, (double) smNs / smBlocks, 100.0 * smNs / mbNs));
            if (flBlocks > 0) sb.append(String.format("║     flat:        %6d blocks, %.0f ns/block\n",
                    flBlocks, (double) flNs / flBlocks));
            long rb = chunkRebuilds.get(); int distinct = chunkRebuildPositions.size();
            if (rb > 0) sb.append(String.format("║     REBUILDS: %d total over %d distinct sections (%.2fx each)\n",
                    rb, distinct, distinct > 0 ? (double) rb / distinct : 0.0));
            sb.append(String.format("║     SHADOWS: %s -- entry calls %d, render calls %d\n",
                    net.vulkanmod.config.VulkanModConfig.SHADOWS.value ? "ON" : "OFF",
                    shadowEntryCalls.get(), shadowRenderCalls.get()));
            if (pendingTotal > 0) sb.append(String.format("║     PENDING QUEUE: %.1f%% of queued sections are on-screen (%d of %d)\n",
                    100.0 * pendingVisible / pendingTotal, pendingVisible, pendingTotal));
        }

        if (dc > 0) {
            // Per-draw breakdown
            sb.append("╠──────────────────────────────────────────────────────────────────╣\n");
            sb.append("║ PER-DRAW BREAKDOWN (avg per draw call, µs):\n");
            sb.append(String.format("║   calculateMVP:      %8.3f µs  (hits:%d misses:%d = %.0f%% skip)\n",
                    calculateMVPNs / 1000.0 / dc, mvpCacheHits, mvpCacheMisses,
                    (mvpCacheHits + mvpCacheMisses) > 0 ? 100.0 * mvpCacheHits / (mvpCacheHits + mvpCacheMisses) : 0));
            sb.append(String.format("║     ├─ glGetFloatv:  %8.3f µs\n", glGetFloatvNs / 1000.0 / dc));
            sb.append(String.format("║     └─ matMul+remap: %8.3f µs\n", matrixMultiplyNs / 1000.0 / dc));
            sb.append(String.format("║   textureSync:       %8.3f µs  (cacheHit:%d glRead:%d)\n",
                    textureSyncNs / 1000.0 / dc, textureSyncCacheHits, textureSyncGlReads));
            sb.append(String.format("║   lightmapSync:      %8.3f µs\n", lightmapSyncNs / 1000.0 / dc));
            sb.append(String.format("║   pipelineLookup:    %8.3f µs\n", pipelineLookupNs / 1000.0 / dc));
            sb.append(String.format("║   bindPipeline:      %8.3f µs  (bind:%d skip:%d)\n",
                    bindPipelineNs / 1000.0 / dc, pipelineBindCalls, pipelineBindSkips));
            sb.append(String.format("║   vertexCopy:        %8.3f µs  (%.1f KB/frame)\n",
                    vertexCopyNs / 1000.0 / dc, vertexCopyBytes / 1024.0 / fc));
            sb.append(String.format("║   uploadBindAndDraw: %8.3f µs  TOTAL\n", uploadBindAndDrawTotalNs / 1000.0 / dc));
            sb.append(String.format("║     ├─ uboUpdate:    %8.3f µs  (%d uploads)\n", uboUpdateNs / 1000.0 / dc, uboUploadCount));
            sb.append(String.format("║     ├─ uboUpload:    %8.3f µs\n", uboUploadNs / 1000.0 / dc));
            sb.append(String.format("║     ├─ dsGetOrCreate:%8.3f µs  (hit:%d miss:%d = %.0f%% reuse)\n",
                    dsGetOrCreateNs / 1000.0 / dc, dsCacheHits, dsCacheMisses,
                    (dsCacheHits + dsCacheMisses) > 0 ? 100.0 * dsCacheHits / (dsCacheHits + dsCacheMisses) : 0));
            sb.append(String.format("║     ├─ cmdBindDS:    %8.3f µs\n", vkCmdBindDSNs / 1000.0 / dc));
            sb.append(String.format("║     ├─ cmdBindVB:    %8.3f µs  (bind:%d skip:%d)\n",
                    vkCmdBindVBNs / 1000.0 / dc, vbBindCalls, vbBindSkips));
            sb.append(String.format("║     ├─ cmdBindIB:    %8.3f µs  (bind:%d skip:%d)\n",
                    vkCmdBindIBNs / 1000.0 / dc, ibBindCalls, ibBindSkips));
            sb.append(String.format("║     └─ cmdDraw:      %8.3f µs\n", vkCmdDrawNs / 1000.0 / dc));
            sb.append(String.format("║   pushConstants:     %8.3f µs  (%d calls)\n", pushConstantNs / 1000.0 / dc, pushConstantCount));
        }

        // Memory events
        if (vbResizeCount > 0 || ubResizeCount > 0 || dsPoolResizeCount > 0) {
            sb.append("╠──────────────────────────────────────────────────────────────────╣\n");
            sb.append(String.format("║ RESIZES: VB=%d  UB=%d  DSPool=%d\n", vbResizeCount, ubResizeCount, dsPoolResizeCount));
        }

        // Time budget summary
        if (dc > 0) {
            sb.append("╠──────────────────────────────────────────────────────────────────╣\n");
            sb.append("║ TIME BUDGET PER FRAME (µs):\n");
            double mvpTotal = calculateMVPNs / 1000.0 / fc;
            double texTotal = (textureSyncNs + lightmapSyncNs) / 1000.0 / fc;
            double pipeLookup = pipelineLookupNs / 1000.0 / fc;
            double pipeBind = bindPipelineNs / 1000.0 / fc;
            double vtxCopy = vertexCopyNs / 1000.0 / fc;
            double ubdTotal = uploadBindAndDrawTotalNs / 1000.0 / fc;
            double frameOverhead = avgFrame_us;
            double pushC = pushConstantNs / 1000.0 / fc;
            double allDrawWork = mvpTotal + texTotal + pipeLookup + pipeBind + vtxCopy + ubdTotal + pushC;
            sb.append(String.format("║   calculateMVP:      %8.1f µs  (%4.1f%%)\n", mvpTotal, 100 * mvpTotal / (allDrawWork + frameOverhead)));
            sb.append(String.format("║   textureSync:       %8.1f µs  (%4.1f%%)\n", texTotal, 100 * texTotal / (allDrawWork + frameOverhead)));
            sb.append(String.format("║   pipelineLookup:    %8.1f µs  (%4.1f%%)\n", pipeLookup, 100 * pipeLookup / (allDrawWork + frameOverhead)));
            sb.append(String.format("║   bindPipeline:      %8.1f µs  (%4.1f%%)\n", pipeBind, 100 * pipeBind / (allDrawWork + frameOverhead)));
            sb.append(String.format("║   vertexCopy:        %8.1f µs  (%4.1f%%)\n", vtxCopy, 100 * vtxCopy / (allDrawWork + frameOverhead)));
            sb.append(String.format("║   uploadBindDraw:    %8.1f µs  (%4.1f%%)\n", ubdTotal, 100 * ubdTotal / (allDrawWork + frameOverhead)));
            sb.append(String.format("║   pushConstants:     %8.1f µs  (%4.1f%%)\n", pushC, 100 * pushC / (allDrawWork + frameOverhead)));
            sb.append(String.format("║   frameOverhead:     %8.1f µs  (%4.1f%%)\n", frameOverhead, 100 * frameOverhead / (allDrawWork + frameOverhead)));
            sb.append(String.format("║   ─────────────────────────────\n"));
            sb.append(String.format("║   TOTAL CPU/frame:   %8.1f µs  (budget@60fps: 16666µs)\n", allDrawWork + frameOverhead));
        }

        // The question that matters: is the frame limited by this thread or by the GPU?
        sb.append("╠──────────────────────────────────────────────────────────────────╣\n");
        if (gpuSamples > 0) {
            double gpuMsAvg = gpuTimeNs / 1_000_000.0 / gpuSamples;
            double frameMsAvg = 1000.0 / fps;
            double cpuMsAvg = avgFrame_us / 1000.0;
            sb.append(String.format("║ GPU TIME:  %8.2f ms/frame  (%d samples)\n", gpuMsAvg, gpuSamples));
            sb.append(String.format("║ WALL TIME: %8.2f ms/frame\n", frameMsAvg));
            String verdict;
            if (gpuMsAvg > frameMsAvg * 0.85) verdict = "GPU BOUND - shrink work per pixel/vertex";
            else if (cpuMsAvg > frameMsAvg * 0.5) verdict = "CPU BOUND - shrink draw calls / per-draw work";
            else verdict = "neither saturated - likely vsync or a stall elsewhere";
            sb.append(String.format("║ VERDICT:   %s\n", verdict));
        } else {
            sb.append("║ GPU TIME:  unavailable (no timestamp samples)\n");
        }

        String samples = ClientThreadSampler.report(14);
        if (!samples.isEmpty()) {
            sb.append("╠──────────────────────────────────────────────────────────────────╣\n");
            sb.append(samples);
        }

        sb.append("╚══════════════════════════════════════════════════════════════════╝");

        Initializer.LOGGER.info(sb.toString());
    }

    private static void reset() {
        frameCount = 0;
        fenceWaitNs = 0; acquireImageNs = 0; resetDescriptorsNs = 0; cmdBufferSetupNs = 0; initiateRenderPassTotalNs = 0;
        framePrologueNs = 0; transferFenceWaitNs = 0; hzbPrepNs = 0; drawPathTotalNs = 0;
        endRenderPassNs = 0;
        drawFrameSubmitNs = 0; drawFramePresentNs = 0; drawFrameTotalNs = 0;
        drawCallCount = 0; drawPersistentCount = 0;
        tessellatorDrawTotalNs = 0; vbMixinDrawTotalNs = 0;
        calculateMVPNs = 0; mvpCacheHits = 0; mvpCacheMisses = 0; glGetFloatvNs = 0; matrixMultiplyNs = 0;
        meshBuildNs.set(0); meshBuildBlocks.set(0);
        meshSmoothNs.set(0); meshSmoothBlocks.set(0);
        chunkRebuilds.set(0); chunkRebuildPositions.clear();
        pendingVisible = 0; pendingTotal = 0;
        shadowEntryCalls.set(0); shadowRenderCalls.set(0);
        textureSyncNs = 0; lightmapSyncNs = 0; textureSyncCacheHits = 0; textureSyncGlReads = 0;
        pipelineLookupNs = 0;
        bindPipelineNs = 0; pipelineBindSkips = 0; pipelineBindCalls = 0;
        uploadBindAndDrawTotalNs = 0; uboUpdateNs = 0; uboUploadNs = 0; uboUploadCount = 0;
        dsGetOrCreateNs = 0; dsCacheHits = 0; dsCacheMisses = 0;
        vkCmdBindDSNs = 0; vkCmdBindVBNs = 0; vkCmdBindIBNs = 0; vkCmdDrawNs = 0;
        vbBindSkips = 0; vbBindCalls = 0; ibBindSkips = 0; ibBindCalls = 0;
        vertexCopyNs = 0; vertexCopyBytes = 0;
        pushConstantNs = 0; pushConstantCount = 0;
        vbResizeCount = 0; ubResizeCount = 0; dsPoolResizeCount = 0;
        gpuTimeNs = 0; gpuSamples = 0;
        Drawer.indirectFlushCount = 0; Drawer.indirectCmdTotal = 0;
    }
}
