package net.vulkanmod.mixin.perf;

import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.world.BuiltChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Rebuilds the sections you can see before the ones you cannot.
 *
 * <p>updateChunks drains chunksToRebuild under a time budget and stops as soon as the budget runs
 * out or the builder's queue fills. chunksToRebuild is a HashSet, so the order it drains in is
 * whatever the hash gives -- a section behind the camera routinely gets sent to the mesher ahead
 * of one in front of it, and the one in front then waits for a later frame. With world generation
 * feeding the set continuously that is a steady stream of work spent off-screen while visible
 * terrain is still missing.
 *
 * <p>Nothing is skipped or dropped: the same set drains in the same frames, only visible sections
 * go first. Deferring invisible work outright would leave holes when you turn around, so the
 * ordering is a priority, not a filter.
 *
 * <p>Visibility comes from visibleChunks, which setupTerrain already computed this frame, so this
 * costs one pass over the pending set and no extra culling work.
 */
@Mixin(WorldRenderer.class)
public class ChunkRebuildPriorityMixin {

    // Raw: the element type, WorldRenderer$ChunkInfo, is package private.
    @Shadow private List<?> visibleChunks;

    // Reused across frames: updateChunks runs every frame and the pending set can hold thousands
    // of sections while a world is generating.
    //
    // Allocated on first use rather than in a field initialiser: mixin does not carry instance
    // field initialisers over to the target class, so these arrived as null and crashed.
    @Unique private List<BuiltChunk> vulkanmodVisibleFirst;
    @Unique private List<BuiltChunk> vulkanmodRest;
    @Unique private Set<BuiltChunk> vulkanmodVisibleSet;
    @Unique private int vulkanmodLastDrainTick = Integer.MIN_VALUE;

    // Set by the iterator redirect and read by the nanoTime redirect a few instructions later in
    // the same call, so it never has to survive across a frame.
    @Unique private boolean vulkanmodBatchFrame = false;

    /** -Dvulkanmod.norebuildpriority=true restores vanilla's hash order, for A/B measurement. */
    @Unique private static final boolean VULKANMOD_PRIORITY =
            !Boolean.getBoolean("vulkanmod.norebuildpriority");

    @Redirect(method = "updateChunks",
            at = @At(value = "INVOKE", target = "Ljava/util/Set;iterator()Ljava/util/Iterator;"))
    private Iterator<BuiltChunk> vulkanmodVisibleFirstIterator(Set<BuiltChunk> pending) {
        // Lazy chunk loading: hand back nothing until enough ticks have passed, which leaves the
        // queue intact and simply skips this frame's drain. Meshing is the most expensive work
        // the game does while terrain streams in, so spacing it out steadies the frame rate at
        // the cost of terrain appearing more slowly.
        int every = net.vulkanmod.config.VulkanModConfig.lazyChunkLoading.ticks;
        vulkanmodBatchFrame = false;
        if (every > 0) {
            int now = net.vulkanmod.config.VulkanModConfig.tickCounter;
            if (now - vulkanmodLastDrainTick < every) return java.util.Collections.emptyIterator();
            vulkanmodLastDrainTick = now;
            vulkanmodBatchFrame = true;
        }

        if (!VULKANMOD_PRIORITY
                || !net.vulkanmod.config.VulkanModConfig.REBUILD_PRIORITY.value) {
            return pending.iterator();
        }

        if (vulkanmodVisibleFirst == null) {
            vulkanmodVisibleFirst = new ArrayList<>();
            vulkanmodRest = new ArrayList<>();
            vulkanmodVisibleSet = Collections.newSetFromMap(new java.util.IdentityHashMap<>());
        }

        vulkanmodVisibleSet.clear();
        List<?> visible = this.visibleChunks;
        if (visible != null) {
            for (int i = 0; i < visible.size(); i++) {
                Object info = visible.get(i);
                if (info != null) vulkanmodVisibleSet.add(((ChunkInfoAccessor) info).vulkanmodGetChunk());
            }
        }

        vulkanmodVisibleFirst.clear();
        vulkanmodRest.clear();
        for (BuiltChunk chunk : pending) {
            if (vulkanmodVisibleSet.contains(chunk)) vulkanmodVisibleFirst.add(chunk);
            else vulkanmodRest.add(chunk);
        }
        if (net.vulkanmod.vulkan.PerformanceProfiler.ENABLED) {
            net.vulkanmod.vulkan.PerformanceProfiler.pendingVisible += vulkanmodVisibleFirst.size();
            net.vulkanmod.vulkan.PerformanceProfiler.pendingTotal += vulkanmodVisibleFirst.size() + vulkanmodRest.size();
        }

        vulkanmodVisibleFirst.addAll(vulkanmodRest);

        return new net.vulkanmod.vulkan.render.VisibleFirstIterator(vulkanmodVisibleFirst, pending);
    }

    /**
     * Lifts vanilla's per frame time budget on the frames a batch is due.
     *
     * <p>The setting is the spacing between batches, not a rate per section. Without this the two
     * interact the wrong way round: vanilla stops the drain as soon as finishTimeNano passes, which
     * is a couple of sections, so "every 30 ticks" meant a couple of sections every 30 ticks and
     * terrain crawled in one piece at a time.
     *
     * <p>Returning 0 keeps {@code finishTimeNano - nanoTime()} positive, so the loop runs until the
     * pending set is empty or ChunkBuilder.send reports its queue full. That queue is what bounds
     * the batch, which is the bound that was wanted: as many sections as the builder will take,
     * then nothing until the next batch.
     */
    @Redirect(method = "updateChunks",
            at = @At(value = "INVOKE", target = "Ljava/lang/System;nanoTime()J"))
    private long vulkanmodBatchBudget() {
        return vulkanmodBatchFrame ? 0L : System.nanoTime();
    }
}
