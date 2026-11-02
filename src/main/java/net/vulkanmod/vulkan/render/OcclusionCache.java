package net.vulkanmod.vulkan.render;

/**
 * Version counter for client block data that the terrain visibility graph reads.
 *
 * <p>{@code WorldRenderer.getOpenChunkFaces} flood-fills a 16x16x16 section every time the
 * camera moves, to find out which section faces the player can see through. On a moving camera
 * that is thousands of block lookups and boxed queue entries per frame, and every one of them
 * runs on the client thread. The result only changes when a block inside the section changes,
 * so it is cached and invalidated from {@code ChunkSection} writes instead.
 *
 * <p>The counter only has to be monotonic, not perfectly synchronized: a lost increment can at
 * most keep one cached face set alive until the next block edit.
 */
public final class OcclusionCache {

    /**
     * {@code Direction.values()} clones its backing array on every call. The visibility BFS
     * calls it once per visited section, several hundred times a frame while moving, so keep
     * one read-only copy. Never mutate this array.
     */
    public static final net.minecraft.util.math.Direction[] DIRECTION_VALUES =
            net.minecraft.util.math.Direction.values();

    private static volatile int version;

    private OcclusionCache() {
    }

    public static void invalidate() {
        ++version;
    }

    public static int version() {
        return version;
    }
}
