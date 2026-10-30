package net.vulkanmod.vulkan.memory;

import net.vulkanmod.vulkan.util.VUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

import static net.vulkanmod.vulkan.Vulkan.deviceProperties;
import static net.vulkanmod.vulkan.Vulkan.getSwapChainImages;
import static net.vulkanmod.vulkan.memory.MemoryManager.Copy;
import static net.vulkanmod.vulkan.util.VUtil.align;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT;

public class UniformBuffers {

    private int bufferSize;
    private int usedBytes;

    private List<UniformBuffer> uniformBuffers;

    private final static int minOffset = (int) deviceProperties.limits().minUniformBufferOffsetAlignment();
    private final int imagesSize = getSwapChainImages().size();

    public UniformBuffers(int size) {
        createUniformBuffers(size);
    }

    private void createUniformBuffers(int size) {
        this.bufferSize = size;

        uniformBuffers = new ArrayList<>(imagesSize);

        for(int i = 0; i < imagesSize; ++i) {
            uniformBuffers.add(new UniformBuffer(this.bufferSize));
        }
    }

    public void uploadUBO(ByteBuffer buffer, int offset, int frame)  {
        int size = buffer.remaining();
        int alignedSize = align(size, minOffset);
        if (alignedSize > this.bufferSize - this.usedBytes) {
            resizeBuffer((int) ((this.bufferSize + alignedSize) * 2));
        }

        uniformBuffers.get(frame).uploadUBO(buffer, offset);
        usedBytes += alignedSize;
    }

    private void resizeBuffer(int newSize) {

        for (UniformBuffer uniformBuffer : uniformBuffers) {
            uniformBuffer.resizeBuffer(newSize);
        }

        this.bufferSize = newSize;

        // Cached descriptor sets point at the old VkBuffer handles.
        net.vulkanmod.vulkan.Pipeline.requestDescriptorInvalidation();
    }

    public void reset() {
        usedBytes = 0;
    }

    public int getUsedBytes() {
        return usedBytes;
    }

    public long getId(int i) {
        return uniformBuffers.get(i).getId();
    }

    public static int getMinOffset() {
        return minOffset;
    }

    static class UniformBuffer extends Buffer {
        // Cached mapped ByteBuffer view — avoids creating a new view per upload
        private ByteBuffer mappedView;

        protected UniformBuffer(int size) {
            super(VK_BUFFER_USAGE_UNIFORM_BUFFER_BIT, MemoryTypes.HOST_MEM);
            this.createBuffer(size);
            updateMappedView();
        }

        private void updateMappedView() {
            if (this.data != null) {
                this.mappedView = this.data.getByteBuffer(0, (int) bufferSize);
            }
        }

        public void uploadUBO(ByteBuffer buffer, int offset) {
            ByteBuffer dst = this.mappedView;
            // The cached view keeps the limit of the previous upload. Clear it
            // before positioning, otherwise the second draw of a frame fails
            // with "newPosition > limit" once the offset moves past that size.
            dst.clear();
            dst.position(offset);
            int size = buffer.remaining();
            dst.limit(offset + size);
            org.lwjgl.system.MemoryUtil.memCopy(buffer, dst);
            buffer.limit(buffer.capacity()).rewind();
        }

        private void resizeBuffer(int newSize) {
            MemoryManager.addToFreeable(this);
            createBuffer(newSize);
            updateMappedView();
        }
    }

}
