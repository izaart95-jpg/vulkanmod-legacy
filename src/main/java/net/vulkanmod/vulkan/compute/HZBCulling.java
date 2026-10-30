package net.vulkanmod.vulkan.compute;

import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.memory.MemoryManager;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Hierarchical-Z occlusion culling for the batched terrain pass.
 *
 * <p>Each frame a depth pyramid is reduced from the previous frame's depth attachment, and a
 * compute pass tests every chunk in the indirect batch against it, zeroing the instanceCount of
 * the ones fully hidden. The decision never comes back to the CPU: the GPU rewrites its own draw
 * commands, so there is no readback and no stall.
 *
 * <p>This catches what Minecraft's visibility graph cannot -- terrain behind a hill, or behind
 * structures that span chunk boundaries -- because that graph only reasons about connectivity
 * between sections, not about what is actually in front of what.
 *
 * <p>Two consequences of reading last frame's depth are handled deliberately: geometry that
 * became visible this frame can be culled for one frame (mitigated by testing the chunk's
 * nearest corner and by never culling anything straddling the near plane), and the very first
 * frame has no depth at all, so culling is skipped until a pyramid exists.
 */
public class HZBCulling {

    /**
     * Off by default; -Dvulkanmod.hzb=true turns it on.
     *
     * <p>It renders correctly, but measured back to back on the same world and window it is a
     * loss: 94-110 FPS and 5.2-6.4 ms of GPU time with it off, against 52-79 FPS and 6.7-8.5 ms
     * with it on, and the gap widens as the view fills in. The depth pyramid costs about 1.5-2 ms
     * of GPU per frame and recovers less than that, because 1.8.9's visibility graph has already
     * discarded most of the hidden terrain before the cull ever sees it.
     *
     * <p>The reduction is what dominates: level zero alone is one invocation per pyramid texel
     * with four samples each. Starting the pyramid a level or two lower is the obvious thing to
     * try before writing this approach off.
     */
    public static final boolean ENABLED = Boolean.getBoolean("vulkanmod.hzb");

    private static HZBCulling INSTANCE;

    public static HZBCulling getInstance() {
        if (INSTANCE == null) INSTANCE = new HZBCulling();
        return INSTANCE;
    }

    private ComputePipeline reducePipeline;
    private ComputePipeline cullPipeline;

    private long pyramidImage;
    private long pyramidMemory;
    private long[] pyramidMipViews;
    private long pyramidFullView;
    private long pyramidSampler;
    private long depthSampler;

    private int width, height, mipLevels;
    private boolean initialized;
    private boolean failed;
    private boolean pyramidValid;
    /** Bumped whenever the pyramid is rebuilt, so the pass can reallocate its descriptor sets. */
    private int generation;

    public static long culledChunks = 0;
    public static long testedChunks = 0;

    public boolean isReady() {
        return ENABLED && initialized && !failed;
    }

    public void init(int screenWidth, int screenHeight) {
        if (!ENABLED || initialized || failed) return;

        try {
            // Rounded up, not down: a pyramid smaller than the screen covers only a corner of
            // it, and every chunk projecting outside that corner gets tested against depth that
            // does not describe it.
            // Half resolution: occlusion is a coarse test, and the reduction is conservative, so
            // the extra precision of a full-size pyramid buys nothing while costing memory.
            this.width = Math.max(2, nextPowerOfTwo(Math.max(screenWidth, 2)) >> 1);
            this.height = Math.max(2, nextPowerOfTwo(Math.max(screenHeight, 2)) >> 1);
            this.mipLevels = Math.max(1, 32 - Integer.numberOfLeadingZeros(Math.max(width, height)));

            createPyramid();
            createSamplers();

            if (reducePipeline != null) {
                initialized = true;
                Initializer.LOGGER.info("[HZB] pyramid rebuilt: screen {}x{}, pyramid {}x{}, {} levels",
                        screenWidth, screenHeight, width, height, mipLevels);
                return;
            }

            reducePipeline = new ComputePipeline("core/hzb_reduce.comp",
                    new ComputePipeline.DescriptorBinding[] {
                            new ComputePipeline.DescriptorBinding(0, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER),
                            new ComputePipeline.DescriptorBinding(1, VK_DESCRIPTOR_TYPE_STORAGE_IMAGE)
                    }, 12, 64);

            cullPipeline = new ComputePipeline("core/hzb_cull.comp",
                    new ComputePipeline.DescriptorBinding[] {
                            new ComputePipeline.DescriptorBinding(0, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER),
                            new ComputePipeline.DescriptorBinding(1, VK_DESCRIPTOR_TYPE_STORAGE_BUFFER),
                            new ComputePipeline.DescriptorBinding(2, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    }, 80, 16);

            initialized = true;
            Initializer.LOGGER.info("[HZB] occlusion culling ready: screen {}x{}, pyramid {}x{}, {} levels",
                    screenWidth, screenHeight, width, height, mipLevels);
        } catch (Exception e) {
            failed = true;
            Initializer.LOGGER.error("[HZB] disabled: setup failed", e);
        }
    }

    private static int nextPowerOfTwo(int v) {
        int p = Integer.highestOneBit(v);
        return p == v ? p : p << 1;
    }

    /** Rebuilds the pyramid for a new swapchain size. */
    public void resize(int screenWidth, int screenHeight) {
        if (!ENABLED || failed) return;
        if (initialized && nextPowerOfTwo(Math.max(screenWidth, 2)) == width
                && nextPowerOfTwo(Math.max(screenHeight, 2)) == height) return;

        if (initialized) {
            // The pyramid is rebuilt; the compute pipelines are size-independent and are kept.
            // Descriptor sets are rewritten every frame, so they pick up the new views on their
            // own -- only their count matters, and that is what generation tracks.
            VkDevice device = Vulkan.getDevice();
            vkDeviceWaitIdle(device);
            for (long view : pyramidMipViews) vkDestroyImageView(device, view, null);
            vkDestroyImageView(device, pyramidFullView, null);
            vkDestroySampler(device, pyramidSampler, null);
            vkDestroySampler(device, depthSampler, null);
            MemoryManager.freeImage(pyramidImage, pyramidMemory);
            initialized = false;
            pyramidValid = false;
            generation++;
        }
        init(screenWidth, screenHeight);
    }

    private void createPyramid() {
        try (MemoryStack stack = stackPush()) {
            LongBuffer pImage = stack.callocLong(1);
            PointerBuffer pAlloc = stack.callocPointer(1);

            MemoryManager.createImage(width, height, mipLevels,
                    VK_FORMAT_R32_SFLOAT, VK_IMAGE_TILING_OPTIMAL,
                    VK_IMAGE_USAGE_STORAGE_BIT | VK_IMAGE_USAGE_SAMPLED_BIT,
                    VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT, pImage, pAlloc);

            pyramidImage = pImage.get(0);
            pyramidMemory = pAlloc.get(0);
        }

        pyramidMipViews = new long[mipLevels];
        for (int i = 0; i < mipLevels; i++) {
            pyramidMipViews[i] = createView(i, 1);
        }
        pyramidFullView = createView(0, mipLevels);

        // Storage images must be in GENERAL before the first dispatch writes them.
        VkCommandBuffer cmd = Vulkan.beginImmediateCmd();
        transition(cmd, VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_GENERAL, 0, mipLevels);
        Vulkan.endImmediateCmd();
    }

    private long createView(int baseMip, int count) {
        try (MemoryStack stack = stackPush()) {
            VkImageViewCreateInfo info = VkImageViewCreateInfo.callocStack(stack);
            info.sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO);
            info.image(pyramidImage);
            info.viewType(VK_IMAGE_VIEW_TYPE_2D);
            info.format(VK_FORMAT_R32_SFLOAT);
            info.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
            info.subresourceRange().baseMipLevel(baseMip);
            info.subresourceRange().levelCount(count);
            info.subresourceRange().baseArrayLayer(0);
            info.subresourceRange().layerCount(1);

            LongBuffer pView = stack.callocLong(1);
            if (vkCreateImageView(Vulkan.getDevice(), info, null, pView) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create HZB image view");
            }
            return pView.get(0);
        }
    }

    private void createSamplers() {
        pyramidSampler = createSampler(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
        depthSampler = createSampler(VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE);
    }

    private long createSampler(int addressMode) {
        try (MemoryStack stack = stackPush()) {
            VkSamplerCreateInfo info = VkSamplerCreateInfo.callocStack(stack);
            info.sType(VK_STRUCTURE_TYPE_SAMPLER_CREATE_INFO);
            // Nearest everywhere: the reduction is already conservative and interpolating depth
            // between texels would make it optimistic, which would cull visible geometry.
            info.magFilter(VK_FILTER_NEAREST);
            info.minFilter(VK_FILTER_NEAREST);
            info.mipmapMode(VK_SAMPLER_MIPMAP_MODE_NEAREST);
            info.addressModeU(addressMode);
            info.addressModeV(addressMode);
            info.addressModeW(addressMode);
            info.minLod(0.0f);
            info.maxLod(mipLevels);
            info.borderColor(VK_BORDER_COLOR_FLOAT_OPAQUE_WHITE);

            LongBuffer pSampler = stack.callocLong(1);
            if (vkCreateSampler(Vulkan.getDevice(), info, null, pSampler) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create HZB sampler");
            }
            return pSampler.get(0);
        }
    }

    private void transition(VkCommandBuffer cmd, int oldLayout, int newLayout, int baseMip, int count) {
        try (MemoryStack stack = stackPush()) {
            VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.callocStack(1, stack);
            barrier.sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER);
            barrier.oldLayout(oldLayout);
            barrier.newLayout(newLayout);
            barrier.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
            barrier.dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
            barrier.image(pyramidImage);
            barrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT);
            barrier.subresourceRange().baseMipLevel(baseMip);
            barrier.subresourceRange().levelCount(count);
            barrier.subresourceRange().baseArrayLayer(0);
            barrier.subresourceRange().layerCount(1);
            barrier.srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT);
            barrier.dstAccessMask(VK_ACCESS_SHADER_READ_BIT);

            vkCmdPipelineBarrier(cmd,
                    VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                    0, null, null, barrier);
        }
    }

    public long getPyramidView() { return pyramidFullView; }
    public long getPyramidSampler() { return pyramidSampler; }
    public long getDepthSampler() { return depthSampler; }
    public int getWidth() { return width; }
    public int getHeight() { return height; }
    public int getMipLevels() { return mipLevels; }
    public boolean isPyramidValid() { return pyramidValid; }
    public int getGeneration() { return generation; }
    public void markPyramidValid() { pyramidValid = true; }
    public void invalidatePyramid() { pyramidValid = false; }

    public ComputePipeline getReducePipeline() { return reducePipeline; }
    public ComputePipeline getCullPipeline() { return cullPipeline; }
    public long[] getMipViews() { return pyramidMipViews; }

    public void cleanUp() {
        if (!initialized) return;
        VkDevice device = Vulkan.getDevice();
        for (long view : pyramidMipViews) vkDestroyImageView(device, view, null);
        vkDestroyImageView(device, pyramidFullView, null);
        vkDestroySampler(device, pyramidSampler, null);
        vkDestroySampler(device, depthSampler, null);
        MemoryManager.freeImage(pyramidImage, pyramidMemory);
        if (reducePipeline != null) reducePipeline.cleanUp();
        if (cullPipeline != null) cullPipeline.cleanUp();
        initialized = false;
    }
}
