package net.vulkanmod.vulkan.compute;

import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.Vulkan;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Records the depth pyramid build and the occlusion cull into a command buffer that runs
 * <em>before</em> the frame's render pass.
 *
 * <p>The split exists because compute cannot be dispatched inside a render pass, while the
 * indirect draw commands are only written as the pass is recorded. Submitting a separate buffer
 * ahead of the main one gets both: by submission time the CPU has finished writing the commands,
 * and the cull runs against them before the draws read them.
 */
public class HZBPass {

    private VkCommandBuffer[] commandBuffers;
    private long[] reduceSets;
    private long cullSet;
    private boolean ready;
    private int generation = -1;

    public boolean prepare(int framesInFlight) {
        HZBCulling hzb = HZBCulling.getInstance();
        if (!hzb.isReady()) return false;

        // A rebuilt pyramid can have a different number of mips, so the per-level sets have to be
        // reallocated even though the command buffers and the cull set are still good.
        if (ready && generation != hzb.getGeneration()) {
            reduceSets = new long[hzb.getMipLevels()];
            for (int i = 0; i < reduceSets.length; i++) {
                reduceSets[i] = hzb.getReducePipeline().allocateDescriptorSet();
            }
            generation = hzb.getGeneration();
        }
        if (ready) return true;

        try (MemoryStack stack = stackPush()) {
            VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.callocStack(stack);
            allocInfo.sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO);
            allocInfo.commandPool(Vulkan.getCommandPool());
            allocInfo.level(VK_COMMAND_BUFFER_LEVEL_PRIMARY);
            allocInfo.commandBufferCount(framesInFlight);

            org.lwjgl.PointerBuffer pBuffers = stack.callocPointer(framesInFlight);
            if (vkAllocateCommandBuffers(Vulkan.getDevice(), allocInfo, pBuffers) != VK_SUCCESS) {
                Initializer.LOGGER.error("[HZB] could not allocate the pre-pass command buffers");
                return false;
            }

            commandBuffers = new VkCommandBuffer[framesInFlight];
            for (int i = 0; i < framesInFlight; i++) {
                commandBuffers[i] = new VkCommandBuffer(pBuffers.get(i), Vulkan.getDevice());
            }
        }

        reduceSets = new long[hzb.getMipLevels()];
        for (int i = 0; i < reduceSets.length; i++) {
            reduceSets[i] = hzb.getReducePipeline().allocateDescriptorSet();
        }
        cullSet = hzb.getCullPipeline().allocateDescriptorSet();

        generation = hzb.getGeneration();
        ready = true;
        return true;
    }

    /**
     * @return the command buffer to submit before the frame, or null when there is nothing to do.
     */
    public VkCommandBuffer record(int frame, long prevDepthImage, long prevDepthView,
                                  long indirectBuffer, long instanceBuffer,
                                  int commandCount, ByteBuffer viewProj) {
        HZBCulling hzb = HZBCulling.getInstance();
        if (!ready || !hzb.isReady() || commandCount <= 0) return null;

        VkCommandBuffer cmd = commandBuffers[frame];

        try (MemoryStack stack = stackPush()) {
            VkCommandBufferBeginInfo begin = VkCommandBufferBeginInfo.callocStack(stack);
            begin.sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);
            begin.flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
            vkResetCommandBuffer(cmd, 0);
            vkBeginCommandBuffer(cmd, begin);

            // Depth from the frame before this one: readable while this frame writes its own,
            // because the attachments are per frame.
            depthBarrier(stack, cmd, prevDepthImage,
                    VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL,
                    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL);

            buildPyramid(stack, cmd, hzb, prevDepthView);

            depthBarrier(stack, cmd, prevDepthImage,
                    VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
                    VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);

            if (hzb.isPyramidValid()) {
                dispatchCull(stack, cmd, hzb, indirectBuffer, instanceBuffer, commandCount, viewProj);
            }
            hzb.markPyramidValid();

            vkEndCommandBuffer(cmd);
        }
        return cmd;
    }

    private void buildPyramid(MemoryStack stack, VkCommandBuffer cmd, HZBCulling hzb, long depthView) {
        ComputePipeline pipeline = hzb.getReducePipeline();
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline.getPipeline());

        int w = hzb.getWidth(), h = hzb.getHeight();
        long srcView = depthView;
        long srcSampler = hzb.getDepthSampler();

        for (int level = 0; level < hzb.getMipLevels(); level++) {
            int dstW = Math.max(1, w >> level), dstH = Math.max(1, h >> level);

            writeReduceSet(stack, reduceSets[level], srcView, srcSampler, hzb.getMipViews()[level], level == 0);

            vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline.getPipelineLayout(),
                    0, stack.longs(reduceSets[level]), null);

            ByteBuffer pc = stack.malloc(12);
            pc.putInt(0, dstW).putInt(4, dstH).putInt(8, level == 0 ? 1 : 0);
            vkCmdPushConstants(cmd, pipeline.getPipelineLayout(), VK_SHADER_STAGE_COMPUTE_BIT, 0, pc);

            vkCmdDispatch(cmd, (dstW + 7) / 8, (dstH + 7) / 8, 1);

            // Each level reads what the previous one just wrote.
            memoryBarrier(stack, cmd);

            srcView = hzb.getMipViews()[level];
            srcSampler = hzb.getPyramidSampler();
        }
    }

    private void writeReduceSet(MemoryStack stack, long set, long srcView, long srcSampler, long dstView,
                                boolean srcIsDepth) {
        VkDescriptorImageInfo.Buffer src = VkDescriptorImageInfo.callocStack(1, stack);
        // Level zero reads the depth attachment, which the barrier above put into
        // SHADER_READ_ONLY_OPTIMAL; every later level reads a pyramid mip, which stays in GENERAL.
        src.imageLayout(srcIsDepth ? VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL
                                   : VK_IMAGE_LAYOUT_GENERAL);
        src.imageView(srcView);
        src.sampler(srcSampler);

        VkDescriptorImageInfo.Buffer dst = VkDescriptorImageInfo.callocStack(1, stack);
        dst.imageLayout(VK_IMAGE_LAYOUT_GENERAL);
        dst.imageView(dstView);

        VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.callocStack(2, stack);
        writes.get(0).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET).dstSet(set).dstBinding(0)
              .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).pImageInfo(src);
        writes.get(1).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET).dstSet(set).dstBinding(1)
              .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_IMAGE).descriptorCount(1).pImageInfo(dst);

        vkUpdateDescriptorSets(Vulkan.getDevice(), writes, null);
    }

    private void dispatchCull(MemoryStack stack, VkCommandBuffer cmd, HZBCulling hzb,
                              long indirectBuffer, long instanceBuffer, int commandCount,
                              ByteBuffer viewProj) {
        ComputePipeline pipeline = hzb.getCullPipeline();

        VkDescriptorBufferInfo.Buffer cmds = VkDescriptorBufferInfo.callocStack(1, stack);
        cmds.buffer(indirectBuffer).offset(0).range(VK_WHOLE_SIZE);
        VkDescriptorBufferInfo.Buffer offsets = VkDescriptorBufferInfo.callocStack(1, stack);
        offsets.buffer(instanceBuffer).offset(0).range(VK_WHOLE_SIZE);

        VkDescriptorImageInfo.Buffer pyramid = VkDescriptorImageInfo.callocStack(1, stack);
        pyramid.imageLayout(VK_IMAGE_LAYOUT_GENERAL);
        pyramid.imageView(hzb.getPyramidView());
        pyramid.sampler(hzb.getPyramidSampler());

        VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.callocStack(3, stack);
        writes.get(0).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET).dstSet(cullSet).dstBinding(0)
              .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).pBufferInfo(cmds);
        writes.get(1).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET).dstSet(cullSet).dstBinding(1)
              .descriptorType(VK_DESCRIPTOR_TYPE_STORAGE_BUFFER).descriptorCount(1).pBufferInfo(offsets);
        writes.get(2).sType(VK_STRUCTURE_TYPE_WRITE_DESCRIPTOR_SET).dstSet(cullSet).dstBinding(2)
              .descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER).descriptorCount(1).pImageInfo(pyramid);
        vkUpdateDescriptorSets(Vulkan.getDevice(), writes, null);

        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline.getPipeline());
        vkCmdBindDescriptorSets(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline.getPipelineLayout(),
                0, stack.longs(cullSet), null);

        ByteBuffer pc = stack.malloc(80);
        for (int i = 0; i < 16; i++) pc.putFloat(i * 4, viewProj.getFloat(i * 4));
        pc.putInt(64, hzb.getWidth()).putInt(68, hzb.getHeight());
        pc.putInt(72, commandCount).putInt(76, hzb.getMipLevels());
        vkCmdPushConstants(cmd, pipeline.getPipelineLayout(), VK_SHADER_STAGE_COMPUTE_BIT, 0, pc);

        vkCmdDispatch(cmd, (commandCount + 63) / 64, 1, 1);

        // The indirect draws in the main command buffer must see the rewritten commands.
        VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.callocStack(1, stack);
        barrier.sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER);
        barrier.srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT);
        barrier.dstAccessMask(VK_ACCESS_INDIRECT_COMMAND_READ_BIT);
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_PIPELINE_STAGE_DRAW_INDIRECT_BIT, 0, barrier, null, null);
    }

    private void memoryBarrier(MemoryStack stack, VkCommandBuffer cmd) {
        VkMemoryBarrier.Buffer barrier = VkMemoryBarrier.callocStack(1, stack);
        barrier.sType(VK_STRUCTURE_TYPE_MEMORY_BARRIER);
        barrier.srcAccessMask(VK_ACCESS_SHADER_WRITE_BIT);
        barrier.dstAccessMask(VK_ACCESS_SHADER_READ_BIT);
        vkCmdPipelineBarrier(cmd, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT, 0, barrier, null, null);
    }

    private void depthBarrier(MemoryStack stack, VkCommandBuffer cmd, long image,
                              int oldLayout, int newLayout) {
        VkImageMemoryBarrier.Buffer barrier = VkImageMemoryBarrier.callocStack(1, stack);
        barrier.sType(VK_STRUCTURE_TYPE_IMAGE_MEMORY_BARRIER);
        barrier.oldLayout(oldLayout);
        barrier.newLayout(newLayout);
        barrier.srcQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
        barrier.dstQueueFamilyIndex(VK_QUEUE_FAMILY_IGNORED);
        barrier.image(image);
        barrier.subresourceRange().aspectMask(VK_IMAGE_ASPECT_DEPTH_BIT);
        barrier.subresourceRange().baseMipLevel(0);
        barrier.subresourceRange().levelCount(1);
        barrier.subresourceRange().baseArrayLayer(0);
        barrier.subresourceRange().layerCount(1);
        barrier.srcAccessMask(VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);
        barrier.dstAccessMask(VK_ACCESS_SHADER_READ_BIT);

        vkCmdPipelineBarrier(cmd,
                VK_PIPELINE_STAGE_LATE_FRAGMENT_TESTS_BIT, VK_PIPELINE_STAGE_COMPUTE_SHADER_BIT,
                0, null, null, barrier);
    }
}
