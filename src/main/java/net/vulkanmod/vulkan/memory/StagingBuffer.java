package net.vulkanmod.vulkan.memory;

import net.vulkanmod.vulkan.util.VUtil;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static net.vulkanmod.vulkan.memory.MemoryManager.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

public class StagingBuffer extends Buffer{

    public StagingBuffer(int bufferSize) {
        super(VK_BUFFER_USAGE_TRANSFER_SRC_BIT, MemoryTypes.HOST_MEM);
        this.usedBytes = 0;
        this.offset = 0;

        this.createStagingBuffer(bufferSize);
    }

    //TODO: use createBuffer instead
    private void createStagingBuffer(int bufferSize) {
        this.createBuffer(bufferSize);
    }

    public void copyBuffer(int size, ByteBuffer byteBuffer) {

        if(size > this.bufferSize - this.usedBytes) {
            resizeBuffer((int)(this.bufferSize + size) * 2);
        }

        // Use cached mapped buffer to avoid getByteBuffer() allocation per copy
        ByteBuffer dst = this.mappedBuf;
        if (dst != null) {
            VUtil.memcpy(dst, byteBuffer, this.usedBytes);
        } else {
            Copy(this.data,
                    (data) -> VUtil.memcpy(data.getByteBuffer(0, (int) this.bufferSize), byteBuffer, this.usedBytes)
            );
        }

        offset = usedBytes;
        usedBytes += size;
    }

    /**
     * Reserves {@code size} bytes and returns their offset, for callers that can generate their
     * data straight into mapped memory. Saves building the data in a scratch buffer and then
     * memcpy'ing it in -- for chunk meshes that is a second full pass over the whole mesh.
     *
     * @return the byte offset to write at, or -1 when the buffer is not host mapped.
     */
    public int reserve(int size) {
        if (size > this.bufferSize - this.usedBytes) {
            resizeBuffer((int) (this.bufferSize + size) * 2);
        }
        if (this.mappedBuf == null) return -1;

        offset = usedBytes;
        usedBytes += size;
        return (int) offset;
    }

    public ByteBuffer getMapped() {
        return this.mappedBuf;
    }

    private void resizeBuffer(int newSize) {
        //TODO
        MemoryManager.addToFreeable(this);
        createStagingBuffer(newSize);

        System.out.println("resized staging buffer to: " + newSize);
    }

    public void reset() {
        usedBytes = 0;
    }

    public long getOffset() {
        return offset;
    }

    public long getBufferId() {
        return id;
    }
}
