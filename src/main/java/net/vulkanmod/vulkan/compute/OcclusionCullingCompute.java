package net.vulkanmod.vulkan.compute;

import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.FrustumCuller;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.memory.MemoryManager;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * GPU-driven occlusion culling using Vulkan compute shaders.
 *
 * Each frame:
 * 1. Upload chunk AABB data to an SSBO (section positions + bounds)
 * 2. Dispatch compute shader that tests each AABB against:
 *    - Frustum planes (extracted from VP matrix)
 *    - Previous frame's depth buffer (hierarchical-Z / HiZ)
 * 3. Read back visibility flags (1 bit per chunk section)
 * 4. Use visibility flags to skip invisible sections in renderLayer()
 *
 * This eliminates CPU-side frustum/occlusion checks entirely.
 */
public class OcclusionCullingCompute {
    // NOT WIRED UP. addSection() has no callers, so sectionCount is always 0 and a dispatch
    // would cull nothing. init() is no longer called either -- it was allocating compute
    // pipelines and buffers every session for a subsystem that never ran.
    //
    // Chunk occlusion currently comes from Minecraft's own visibility graph
    // (ChunkAssemblyHelper.isVisibleThrough, walked by WorldRenderer.setupTerrain), which is
    // what fills visibleChunks. Reviving this class only pays off as a hierarchical-Z pass,
    // and that needs the render pass to stop discarding depth -- see the note in
    // Vulkan.createRenderPass about storeOp.

    private static OcclusionCullingCompute INSTANCE;

    private static final VkDevice device = Vulkan.getDevice();
    private static final int MAX_SECTIONS = 16384; // 16K sections max

    // SSBO: chunk section AABBs (vec4 min + vec4 max per section = 32 bytes)
    private long aabbBuffer;
    private long aabbAllocation;
    private ByteBuffer aabbMapped;

    // SSBO: visibility results (1 uint per section, 0 or 1)
    private long visibilityBuffer;
    private long visibilityAllocation;
    private ByteBuffer visibilityMapped;

    // Push constants: VP matrix (64 bytes) + camera pos (16 bytes) + section count (4 bytes)
    private static final int PUSH_CONSTANT_SIZE = 84;
    private final ByteBuffer pushConstantBuffer = MemoryUtil.memAlloc(PUSH_CONSTANT_SIZE);

    private ComputePipeline computePipeline;
    private long descriptorSet;

    private int sectionCount = 0;
    private boolean initialized = false;

    // Visibility result array — read back from GPU
    private final int[] visibilityFlags = new int[MAX_SECTIONS];

    // Section key mapping: index → (sectionX, sectionY, sectionZ)
    private final int[] sectionKeys = new int[MAX_SECTIONS * 3];

    private OcclusionCullingCompute() {}

    public static OcclusionCullingCompute getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new OcclusionCullingCompute();
        }
        return INSTANCE;
    }

    /**
     * Initialize compute resources. Must be called after Vulkan device is ready.
     */
    public void init() {
        if (initialized) return;

        try {
            createBuffers();
            createPipeline();
            createDescriptorSet();
            initialized = true;
            Initializer.LOGGER.info("OcclusionCullingCompute: GPU occlusion culling initialized");
        } catch (Exception e) {
            Initializer.LOGGER.warn("OcclusionCullingCompute: Failed to initialize, falling back to CPU culling: " + e.getMessage());
            initialized = false;
        }
    }

    private void createBuffers() {
        try (MemoryStack stack = stackPush()) {
            // AABB buffer (input): 32 bytes per section (2 x vec4)
            int aabbSize = MAX_SECTIONS * 32;
            LongBuffer pBuffer = stack.mallocLong(1);
            PointerBuffer pAlloc = stack.mallocPointer(1);

            MemoryManager.createBuffer(aabbSize,
                    VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                    pBuffer, pAlloc);
            aabbBuffer = pBuffer.get(0);
            aabbAllocation = pAlloc.get(0);
            PointerBuffer pData = MemoryManager.Map(aabbAllocation);
            aabbMapped = pData.getByteBuffer(0, aabbSize);
            MemoryUtil.memFree(pData);

            // Visibility buffer (output): 4 bytes per section (1 uint)
            int visSize = MAX_SECTIONS * 4;
            pBuffer = stack.mallocLong(1);
            pAlloc = stack.mallocPointer(1);

            MemoryManager.createBuffer(visSize,
                    VK_BUFFER_USAGE_STORAGE_BUFFER_BIT,
                    VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT,
                    pBuffer, pAlloc);
            visibilityBuffer = pBuffer.get(0);
            visibilityAllocation = pAlloc.get(0);
            pData = MemoryManager.Map(visibilityAllocation);
            visibilityMapped = pData.getByteBuffer(0, visSize);
            MemoryUtil.memFree(pData);
        }
    }

    private void createPipeline() {
        ComputePipeline.DescriptorBinding[] bindings = {
                new ComputePipeline.DescriptorBinding(0, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER), // AABBs
                new ComputePipeline.DescriptorBinding(1, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER), // Visibility
        };
        computePipeline = new ComputePipeline(
                "core/occlusion_cull.comp",
                bindings,
                PUSH_CONSTANT_SIZE,
                4
        );
    }

    private void createDescriptorSet() {
        descriptorSet = computePipeline.allocateDescriptorSet();

        // Write buffer descriptors
        try (MemoryStack stack = stackPush()) {
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.callocStack(2, stack);

            // AABB buffer
            VkDescriptorBufferInfo.Buffer aabbInfo = VkDescriptorBufferInfo.callocStack(1, stack);
            aabbInfo.buffer(aabbBuffer);
            aabbInfo.offset(0);
            aabbInfo.range(MAX_SECTIONS * 32L);

            writes.get(0).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET);
            writes.get(0).dstSet(descriptorSet);
            writes.get(0).dstBinding(0);
            writes.get(0).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER);
            writes.get(0).pBufferInfo(aabbInfo);

            // Visibility buffer
            VkDescriptorBufferInfo.Buffer visInfo = VkDescriptorBufferInfo.callocStack(1, stack);
            visInfo.buffer(visibilityBuffer);
            visInfo.offset(0);
            visInfo.range(MAX_SECTIONS * 4L);

            writes.get(1).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET);
            writes.get(1).dstSet(descriptorSet);
            writes.get(1).dstBinding(1);
            writes.get(1).descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER);
            writes.get(1).pBufferInfo(visInfo);

            vkUpdateDescriptorSets(device, writes, null);
        }
    }

    /**
     * Clear and begin building the section list for this frame.
     */
    public void beginFrame() {
        if (!initialized) return;
        sectionCount = 0;
    }

    /**
     * Add a chunk section to be visibility-tested on the GPU.
     * Returns the index that can be used to query visibility after dispatch.
     */
    public int addSection(int sectionX, int sectionY, int sectionZ) {
        if (!initialized || sectionCount >= MAX_SECTIONS) return -1;

        int idx = sectionCount;
        sectionKeys[idx * 3] = sectionX;
        sectionKeys[idx * 3 + 1] = sectionY;
        sectionKeys[idx * 3 + 2] = sectionZ;

        // Write AABB to mapped buffer: min(x,y,z,pad), max(x,y,z,pad)
        float minX = sectionX << 4;
        float minY = sectionY << 4;
        float minZ = sectionZ << 4;
        float maxX = minX + 16;
        float maxY = minY + 16;
        float maxZ = minZ + 16;

        int offset = idx * 32;
        aabbMapped.putFloat(offset, minX);
        aabbMapped.putFloat(offset + 4, minY);
        aabbMapped.putFloat(offset + 8, minZ);
        aabbMapped.putFloat(offset + 12, 0.0f); // padding
        aabbMapped.putFloat(offset + 16, maxX);
        aabbMapped.putFloat(offset + 20, maxY);
        aabbMapped.putFloat(offset + 24, maxZ);
        aabbMapped.putFloat(offset + 28, 0.0f); // padding

        sectionCount++;
        return idx;
    }

    /**
     * Dispatch the compute shader to test all registered sections.
     * Must be called outside of a render pass.
     *
     * @param commandBuffer The command buffer to record into
     * @param vpMatrix      The View-Projection matrix (16 floats, column-major)
     * @param cameraX       Camera X position
     * @param cameraY       Camera Y position
     * @param cameraZ       Camera Z position
     */
    public void dispatch(VkCommandBuffer commandBuffer, float[] vpMatrix,
                         float cameraX, float cameraY, float cameraZ) {
        if (!initialized || sectionCount == 0) return;

        // Bind compute pipeline
        vkCmdBindPipeline(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE, computePipeline.getPipeline());

        // Bind descriptor set
        try (MemoryStack stack = stackPush()) {
            vkCmdBindDescriptorSets(commandBuffer, VK_PIPELINE_BIND_POINT_COMPUTE,
                    computePipeline.getPipelineLayout(), 0,
                    stack.longs(descriptorSet), null);
        }

        // Push constants: VP matrix + camera pos + section count
        pushConstantBuffer.clear();
        for (int i = 0; i < 16; i++) {
            pushConstantBuffer.putFloat(vpMatrix[i]);
        }
        pushConstantBuffer.putFloat(cameraX);
        pushConstantBuffer.putFloat(cameraY);
        pushConstantBuffer.putFloat(cameraZ);
        pushConstantBuffer.putFloat(0.0f); // padding
        pushConstantBuffer.putInt(sectionCount);
        pushConstantBuffer.flip();

        vkCmdPushConstants(commandBuffer, computePipeline.getPipelineLayout(),
                VK_SHADER_STAGE_COMPUTE_BIT, 0, pushConstantBuffer);

        // Memory barrier: ensure AABB writes are visible to compute shader
        try (MemoryStack stack = stackPush()) {
            VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.callocStack(1, stack);
            barrier.sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER);
            barrier.srcAccessMask(VK_ACCESS_HOST_WRITE_BIT);
            barrier.dstAccessMask(VK_ACCESS_SHADER_READ_BIT);
            vkCmdPipelineBarrier(commandBuffer,
                    VK_PIPELINE_STAGE_HOST_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    0, barrier, null, null);
        }

        // Dispatch: 64 threads per workgroup
        int workgroups = (sectionCount + 63) / 64;
        vkCmdDispatch(commandBuffer, workgroups, 1, 1);

        // Memory barrier: ensure compute writes are visible to host read
        try (MemoryStack stack = stackPush()) {
            VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.callocStack(1, stack);
            barrier.sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER);
            barrier.srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT);
            barrier.dstAccessMask(VK_ACCESS_HOST_READ_BIT);
            vkCmdPipelineBarrier(commandBuffer,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_HOST_BIT,
                    0, barrier, null, null);
        }
    }

    /**
     * Read back visibility results after compute dispatch has completed.
     * Call after the command buffer containing the dispatch has been submitted and waited on.
     */
    public void readResults() {
        if (!initialized || sectionCount == 0) return;

        IntBuffer results = visibilityMapped.asIntBuffer();
        for (int i = 0; i < sectionCount; i++) {
            visibilityFlags[i] = results.get(i);
        }
    }

    /**
     * Check if a section is visible according to GPU culling results.
     * Falls back to CPU frustum culling if GPU culling is not available.
     */
    public boolean isSectionVisible(int sectionX, int sectionY, int sectionZ) {
        if (!initialized || sectionCount == 0) {
            // Fallback to CPU culling
            return FrustumCuller.isSectionVisible(sectionX, sectionY, sectionZ);
        }

        // Linear scan for section index — sections are added in order so
        // this could be optimized with a hashmap if needed
        for (int i = 0; i < sectionCount; i++) {
            if (sectionKeys[i * 3] == sectionX &&
                sectionKeys[i * 3 + 1] == sectionY &&
                sectionKeys[i * 3 + 2] == sectionZ) {
                return visibilityFlags[i] != 0;
            }
        }

        // Section not in GPU test list — fallback to CPU
        return FrustumCuller.isSectionVisible(sectionX, sectionY, sectionZ);
    }

    /**
     * Check visibility by pre-assigned index (faster than coordinate lookup).
     */
    public boolean isSectionVisibleByIndex(int index) {
        if (!initialized || index < 0 || index >= sectionCount) return true;
        return visibilityFlags[index] != 0;
    }

    public boolean isInitialized() { return initialized; }
    public int getSectionCount() { return sectionCount; }

    public void cleanUp() {
        if (!initialized) return;
        computePipeline.cleanUp();
        MemoryManager.freeBuffer(aabbBuffer, aabbAllocation);
        MemoryManager.freeBuffer(visibilityBuffer, visibilityAllocation);
        MemoryUtil.memFree(pushConstantBuffer);
        initialized = false;
    }
}
