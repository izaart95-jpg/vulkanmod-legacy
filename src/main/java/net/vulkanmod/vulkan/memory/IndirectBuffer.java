package net.vulkanmod.vulkan.memory;

import java.nio.ByteBuffer;

import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT;
import static org.lwjgl.vulkan.VK10.VK_BUFFER_USAGE_VERTEX_BUFFER_BIT;

/**
 * Host-visible scratch buffer for VkDrawIndexedIndirectCommand records, plus the instance-rate
 * chunk offsets they address. Both are written every frame by the CPU and read by the GPU in
 * the same frame, so host memory is the right home: a staging round trip would cost more than
 * the draw calls the batching is removing.
 */
public class IndirectBuffer extends Buffer {

    public IndirectBuffer(int size) {
        // Doubles as the instance-rate vertex binding for the chunk offsets.
        super(VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT | VK_BUFFER_USAGE_VERTEX_BUFFER_BIT,
                MemoryTypes.HOST_MEM);
        this.createBuffer(size);
    }

    public ByteBuffer map() {
        return this.mappedBuf;
    }
}
