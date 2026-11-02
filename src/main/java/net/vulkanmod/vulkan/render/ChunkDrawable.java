package net.vulkanmod.vulkan.render;

/**
 * Implemented by {@code VertexBufferMixin}. Lets the chunk-render-manager fast path ask a
 * Minecraft {@code VertexBuffer} whether it is already resident in the Vulkan chunk arena and,
 * if so, append it to the terrain batch without going through any of the legacy GL calls that
 * vanilla performs for every section (client pointers, matrix push/pop, glBindBuffer, ...).
 */
public interface ChunkDrawable {

    int STATE_NOT_CHUNK = 0;
    int STATE_EMPTY = 1;
    int STATE_DRAWABLE = 2;

    /**
     * {@link #STATE_EMPTY} when the buffer holds no geometry (nothing to draw either way),
     * {@link #STATE_DRAWABLE} when {@link #vulkanmod$drawChunk} can append it to the batch,
     * and {@link #STATE_NOT_CHUNK} when this buffer needs the vanilla path.
     */
    int vulkanmod$chunkDrawState();

    /**
     * Appends this section to the current terrain batch. The camera-relative chunk offset is
     * supplied by the caller, which already knows the chunk position and the view position.
     */
    void vulkanmod$drawChunk(float offsetX, float offsetY, float offsetZ);
}
