package net.vulkanmod.vulkan.memory;

import java.nio.ByteBuffer;

import net.vulkanmod.vulkan.Drawer;
import net.vulkanmod.vulkan.Vulkan;

import static net.vulkanmod.vulkan.Vulkan.copyStagingtoLocalBuffer;
import static net.vulkanmod.vulkan.memory.MemoryManager.*;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;

public class VertexBuffer extends Buffer {

    public VertexBuffer(int size) {
        this(size, MemoryTypes.HOST_MEM);
    }

    public VertexBuffer(int size, MemoryType type) {
        super(VK_BUFFER_USAGE_VERTEX_BUFFER_BIT, type);
        createVertexBuffer(size);

    }

    //TODO: use createBuffer instead
    private void createVertexBuffer(int size) {
        this.createBuffer(size);
    }

    public void copyToVertexBuffer(long vertexSize, long vertexCount, ByteBuffer byteBuffer) {
        int bufferSize = (int) (vertexSize * vertexCount);
//        long bufferSize = byteBuffer.limit();

        //Debug vertexBuffer
//        float floats[] = new float[(int) vertexSize / 4];
//        byteBuffer.asFloatBuffer().get(floats);

        if(bufferSize > this.bufferSize - this.usedBytes) {
            resizeBuffer((this.bufferSize + bufferSize) * 2);
        }

        copyToVertexBuffer(bufferSize, byteBuffer);
        offset = usedBytes;
        usedBytes += bufferSize;

    }

    private void copyToVertexBuffer(long bufferSize, ByteBuffer byteBuffer) {
        this.type.copyToBuffer(this, bufferSize, byteBuffer);
    }

    public void uploadWholeBuffer(ByteBuffer byteBuffer) {
        int bufferSize = (int) (byteBuffer.remaining());

        if(bufferSize > this.bufferSize - this.usedBytes) {
            resizeBuffer((this.bufferSize + bufferSize) * 2);
        }

        this.type.uploadBuffer(this, byteBuffer);
    }

    private void resizeBuffer(int newSize) {
        MemoryManager.addToFreeable(this);
        createVertexBuffer(newSize);

//        System.out.println("resized vertexBuffer to: " + newSize);
    }

    public long getOffset() { return  offset; }

    /**
     * Uploads {@code data} at an arbitrary byte offset. Needed by the chunk arena, where many
     * meshes share one buffer; {@link #uploadWholeBuffer} always writes at 0.
     */
    public void uploadAt(long dstOffset, ByteBuffer data) {
        int size = data.remaining();
        if (size == 0) return;
        if (dstOffset + size > this.bufferSize) {
            throw new IllegalArgumentException("Upload of " + size + " bytes at " + dstOffset
                    + " exceeds buffer size " + this.bufferSize);
        }

        StagingBuffer stagingBuffer = Vulkan.getStagingBuffer(Drawer.getCurrentFrame());
        stagingBuffer.copyBuffer(size, data);
        Vulkan.copyStagingtoLocalBuffer(stagingBuffer.getBufferId(), stagingBuffer.getOffset(),
                this.id, dstOffset, size);
    }

    /** Writes {@code size} bytes generated directly into staging memory, skipping a copy. */
    public boolean uploadGenerated(long dstOffset, int size, Generator generator) {
        if (size <= 0) return true;
        if (dstOffset + size > this.bufferSize) return false;

        StagingBuffer stagingBuffer = Vulkan.getStagingBuffer(Drawer.getCurrentFrame());
        int stagingOffset = stagingBuffer.reserve(size);
        if (stagingOffset < 0) return false;

        generator.generate(stagingBuffer.getMapped(), stagingOffset);

        Vulkan.copyStagingtoLocalBuffer(stagingBuffer.getBufferId(), stagingOffset,
                this.id, dstOffset, size);
        return true;
    }

    /** Fills {@code size} bytes of {@code dst} starting at {@code base}. */
    public interface Generator {
        void generate(ByteBuffer dst, int base);
    }

    /** Moves a range inside this buffer. Caller guarantees the ranges do not overlap. */
    public void copyWithin(long srcOffset, long dstOffset, long size) {
        if (size <= 0) return;
        Vulkan.copyStagingtoLocalBuffer(this.id, srcOffset, this.id, dstOffset, size);
    }

    /** Copies the first {@code size} bytes of {@code src} into this buffer at offset 0. */
    public void copyFrom(VertexBuffer src, long size) {
        if (size <= 0) return;
        Vulkan.copyStagingtoLocalBuffer(src.getId(), 0L, this.id, 0L, size);
    }

}
