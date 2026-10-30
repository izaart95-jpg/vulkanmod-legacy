package net.vulkanmod.vulkan.memory;

import net.vulkanmod.Initializer;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Device-local storage for chunk meshes, split into fixed-size regions.
 *
 * <p>An indirect multi-draw cannot rebind anything between its commands, so every chunk in one
 * batch has to live in the same buffer and pick its mesh through {@code vertexOffset}. That is
 * why meshes are pooled rather than each owning a buffer.
 *
 * <p>Storage grows by adding regions, never by reallocating. The previous design was one buffer
 * that grew 1.5x with a full GPU copy of the old contents; at render distance 12 that meant six
 * growths during world load and, on the last of them, copying 212 MB in the middle of a frame --
 * which showed up as a multi-second stall. Regions cap the cost of growing at "allocate one more
 * buffer" and cap the waste at one partly-filled region, instead of up to a third of the total.
 *
 * <p>The batcher flushes when the region changes, so a layer spanning N regions costs N indirect
 * draws rather than one. With regions this size that is a handful, against the several hundred
 * individual draws the batching replaced.
 */
public class ChunkArena {

    /** One arena per vertex stride -- meshes of different formats cannot share a binding. */
    private static final Map<Integer, ChunkArena> ARENAS = new HashMap<>();

    /** Bytes per region. Big enough that a layer rarely spans many, small enough to not overshoot. */
    private static final int REGION_BYTES = 32 * 1024 * 1024;
    /** Frames a freed slot is quarantined before reuse, so in-flight draws finish reading it. */
    private static final int RETIRE_FRAMES = 3;

    /**
     * Region compaction. OFF by default -- it still corrupts geometry.
     *
     * <p>Quarantining the vacated range is not enough. The destination is computed by walking the
     * live slots and summing their capacities from zero, so it can land on top of a range that is
     * still in quarantine -- memory the GPU is reading for a frame in flight. The copy then
     * overwrites another chunk's vertices mid-frame, which is what put stretched planes and
     * floating geometry back on screen.
     *
     * <p>Making this safe needs the destination picked from ranges that are free <em>and</em> out
     * of quarantine, not from a running offset. Until then it stays off; the cost is a larger
     * arena, not a broken frame.
     */
    private static final boolean COMPACTION = Boolean.getBoolean("vulkanmod.arenacompaction");

    public static ChunkArena forStride(int stride) {
        ChunkArena arena = ARENAS.get(stride);
        if (arena == null) {
            arena = new ChunkArena(stride);
            ARENAS.put(stride, arena);
        }
        return arena;
    }

    public static void cleanUpAll() {
        for (ChunkArena arena : ARENAS.values()) {
            for (Region region : arena.regions) {
                MemoryManager.addToFreeable(region.buffer);
            }
            arena.regions.clear();
        }
        ARENAS.clear();
    }

    /** Advances the quarantine clock; call once per frame. */
    public static void tickAll() {
        for (ChunkArena arena : ARENAS.values()) arena.tick();
    }

    private static class Range {
        int start;  // in vertices, within the region
        int length;
        Range(int start, int length) { this.start = start; this.length = length; }
    }

    private static class Region {
        final VertexBuffer buffer;
        final int capacityVertices;
        final List<Range> freeList = new ArrayList<>();
        /** Live slots, kept ordered by baseVertex so compaction can walk them. */
        final List<Slot> slots = new ArrayList<>();
        int freeVertices;

        Region(int stride, int capacityVertices) {
            this.capacityVertices = capacityVertices;
            this.buffer = new VertexBuffer(capacityVertices * stride, MemoryTypes.GPU_MEM);
            this.freeList.add(new Range(0, capacityVertices));
            this.freeVertices = capacityVertices;
        }
    }

    public static class Slot {
        final Region region;
        /** Index of the first vertex within the region: the value to pass as vertexOffset. */
        public int baseVertex;
        public final int vertexCapacity;
        /** Vertices actually written; may be less than the capacity after a smaller rebuild. */
        public int vertexCount;

        Slot(Region region, int baseVertex, int vertexCapacity) {
            this.region = region;
            this.baseVertex = baseVertex;
            this.vertexCapacity = vertexCapacity;
        }

        /** The buffer to bind for this slot. Two slots share a batch only if these match. */
        public VertexBuffer getBuffer() { return region.buffer; }
    }

    private final int stride;
    private final int regionVertices;
    private final List<Region> regions = new ArrayList<>();
    private final List<Slot> retiring = new ArrayList<>();
    private final List<Integer> retiringAt = new ArrayList<>();
    private int frameCounter = 0;
    private int liveSlots = 0;
    private long usedVertices = 0;

    private ChunkArena(int stride) {
        this.stride = stride;
        this.regionVertices = Math.max(1, REGION_BYTES / stride);
        Initializer.LOGGER.info("[ChunkArena] stride {}: {} MB regions", stride, REGION_BYTES / (1024 * 1024));
    }

    public int getStride() { return stride; }

    /**
     * Allocates room for {@code vertexCount} vertices, reusing {@code existing} when it still
     * fits and is not wastefully oversized. Returns null if no region could take it.
     */
    public Slot allocate(Slot existing, int vertexCount) {
        if (vertexCount <= 0) return existing;

        if (existing != null
                && existing.vertexCapacity >= vertexCount
                // Hand the slot back when the mesh shrank enough to be worth reclaiming, so a
                // chunk that was once dense does not keep its whole allocation forever.
                && vertexCount * 2 >= existing.vertexCapacity) {
            usedVertices += vertexCount - existing.vertexCount;
            existing.vertexCount = vertexCount;
            return existing;
        }

        if (existing != null) free(existing);

        // Modest headroom so a slightly larger rebuild still hits the reuse path above.
        int request = vertexCount + (vertexCount >> 3);
        if (request > regionVertices) {
            Initializer.LOGGER.warn("[ChunkArena] mesh of {} vertices exceeds the {} vertex region size",
                    request, regionVertices);
            return null;
        }

        Slot slot = tryAllocate(request, vertexCount);
        if (slot != null) return slot;

        // Every region is full: add one. No copying, no reallocation of what already exists.
        try {
            regions.add(new Region(stride, regionVertices));
        } catch (Exception e) {
            Initializer.LOGGER.error("[ChunkArena] could not add a region for stride {}", stride, e);
            return null;
        }
        Initializer.LOGGER.info("[ChunkArena] stride {}: {} regions ({} MB), {} live slots, {} MB of mesh data",
                stride, regions.size(), (long) regions.size() * REGION_BYTES / (1024 * 1024),
                liveSlots, usedVertices * stride / (1024 * 1024));

        return tryAllocate(request, vertexCount);
    }

    private Slot tryAllocate(int request, int vertexCount) {
        for (int r = 0; r < regions.size(); r++) {
            Region region = regions.get(r);
            List<Range> freeList = region.freeList;

            for (int i = 0; i < freeList.size(); i++) {
                Range range = freeList.get(i);
                if (range.length < request) continue;

                Slot slot = new Slot(region, range.start, request);
                slot.vertexCount = vertexCount;

                if (range.length == request) {
                    freeList.remove(i);
                } else {
                    range.start += request;
                    range.length -= request;
                }

                liveSlots++;
                usedVertices += vertexCount;
                region.freeVertices -= request;
                insertSlotOrdered(region, slot);
                return slot;
            }
        }
        return null;
    }

    private static void insertSlotOrdered(Region region, Slot slot) {
        int i = 0;
        while (i < region.slots.size() && region.slots.get(i).baseVertex < slot.baseVertex) i++;
        region.slots.add(i, slot);
    }

    /**
     * Slides one slot down into the gap in front of it, once per frame per region.
     *
     * <p>Freed slots coalesce, but a region can still end up with its live meshes spread across
     * holes that no single new mesh fits into, so the arena adds a region it did not need. This
     * is OptiFine's VboRegion approach: bound the work at one move per frame rather than
     * compacting everything at once, and only start when a region is more than a tenth wasted.
     *
     * <p>Moves are skipped when source and destination would overlap, since a buffer copy onto
     * itself is undefined; that slot is simply left for a later pass.
     */
    private void compactOne(Region region) {
        if (!COMPACTION) return;
        if (region.slots.isEmpty()) return;
        if (region.freeVertices * 10 < region.capacityVertices) return; // under 10% wasted

        int expected = 0;
        for (int i = 0; i < region.slots.size(); i++) {
            Slot slot = region.slots.get(i);
            if (slot.baseVertex == expected) {
                expected += slot.vertexCapacity;
                continue;
            }

            int gap = slot.baseVertex - expected;
            if (gap <= 0 || slot.vertexCapacity > gap) return; // would overlap; try again later

            region.buffer.copyWithin((long) slot.baseVertex * stride,
                    (long) expected * stride,
                    (long) slot.vertexCapacity * stride);

            // The vacated range goes through the same quarantine a free does. Releasing it
            // straight away handed it to the next allocation while draws recorded for the
            // frames still in flight were reading it, which tore geometry into shards.
            quarantineRange(region, slot.baseVertex, slot.vertexCapacity);
            slot.baseVertex = expected;
            takeRange(region, expected, slot.vertexCapacity);
            region.slots.remove(i);
            insertSlotOrdered(region, slot);
            return; // one move per frame
        }
    }

    /** Holds a range out of circulation until the frames that may still read it have retired. */
    private void quarantineRange(Region region, int start, int length) {
        Slot placeholder = new Slot(region, start, length);
        retiring.add(placeholder);
        retiringAt.add(frameCounter + RETIRE_FRAMES);
    }

    private void takeRange(Region region, int start, int length) {
        List<Range> freeList = region.freeList;
        for (int i = 0; i < freeList.size(); i++) {
            Range r = freeList.get(i);
            if (start < r.start || start + length > r.start + r.length) continue;

            int tailStart = start + length;
            int tailLength = r.start + r.length - tailStart;
            if (start == r.start) {
                r.start = tailStart;
                r.length = tailLength;
                if (r.length == 0) freeList.remove(i);
            } else {
                r.length = start - r.start;
                if (tailLength > 0) freeList.add(i + 1, new Range(tailStart, tailLength));
            }
            region.freeVertices -= length;
            return;
        }
    }

    /** Queues a slot for reuse once the GPU can no longer be reading it. */
    public void free(Slot slot) {
        if (slot == null) return;
        retiring.add(slot);
        retiringAt.add(frameCounter + RETIRE_FRAMES);
        liveSlots--;
        usedVertices -= slot.vertexCount;
        slot.region.slots.remove(slot);
    }

    private void tick() {
        frameCounter++;
        for (int i = 0; i < regions.size(); i++) compactOne(regions.get(i));
        for (int i = retiring.size() - 1; i >= 0; i--) {
            if (retiringAt.get(i) <= frameCounter) {
                release(retiring.remove(i));
                retiringAt.remove(i);
            }
        }
    }

    private void release(Slot slot) {
        releaseRange(slot.region, slot.baseVertex, slot.vertexCapacity);
    }

    private static void releaseRange(Region region, int start, int length) {
        List<Range> freeList = region.freeList;
        region.freeVertices += length;

        // Insert sorted, then coalesce with the neighbours so the region does not fragment.
        int i = 0;
        while (i < freeList.size() && freeList.get(i).start < start) i++;
        freeList.add(i, new Range(start, length));

        if (i + 1 < freeList.size()) {
            Range cur = freeList.get(i), next = freeList.get(i + 1);
            if (cur.start + cur.length == next.start) {
                cur.length += next.length;
                freeList.remove(i + 1);
            }
        }
        if (i > 0) {
            Range prev = freeList.get(i - 1), cur = freeList.get(i);
            if (prev.start + prev.length == cur.start) {
                prev.length += cur.length;
                freeList.remove(i);
            }
        }
    }

    public void upload(Slot slot, ByteBuffer data) {
        slot.region.buffer.uploadAt((long) slot.baseVertex * stride, data);
    }

    /** Uploads a mesh written straight into staging memory, avoiding a scratch buffer. */
    public boolean uploadGenerated(Slot slot, int byteSize, VertexBuffer.Generator generator) {
        return slot.region.buffer.uploadGenerated((long) slot.baseVertex * stride, byteSize, generator);
    }
}
