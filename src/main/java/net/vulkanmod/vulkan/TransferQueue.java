package net.vulkanmod.vulkan;

import net.vulkanmod.vulkan.util.VUtil;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;

import static net.vulkanmod.vulkan.Vulkan.findQueueFamilies;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

public class TransferQueue {
    /**
     * Resolved lazily: this class must not capture the device at class-load time,
     * since it may be loaded before {@link Vulkan#initVulkan} has created it.
     */
    private static VkDevice device;

    private static long commandPool;
    private static final List<CommandBuffer> commandBuffers = new ArrayList<>();
    private static final List<Long> fences = new ArrayList<>();
    private static int current = 0;

    private static CommandBuffer currentCmdBuffer;

    /**
     * True between {@link #startRecording()} and {@link #endRecording()}, even when no command is
     * recorded yet. Opening the command buffer eagerly cost one empty vkQueueSubmit (and its
     * fence reset) every frame whose uploads all took the synchronous path -- which is most
     * frames after the world has loaded. The buffer is now created on the first batched upload.
     */
    private static boolean recordingOpen;

    private static VkDevice device() {
        VkDevice d = device;
        if (d == null) {
            d = Vulkan.getDevice();
            if (d == null) throw new IllegalStateException("TransferQueue used before the Vulkan device was created");
            device = d;
            createCommandPool();
        }
        return d;
    }

    private static void createCommandPool() {

        try(MemoryStack stack = stackPush()) {

            Vulkan.QueueFamilyIndices queueFamilyIndices = findQueueFamilies(device.getPhysicalDevice());

            VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.callocStack(stack);
            poolInfo.sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO);
            poolInfo.queueFamilyIndex(queueFamilyIndices.graphicsFamily);
            poolInfo.flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT);

            LongBuffer pCommandPool = stack.callocLong(1);

            if (vkCreateCommandPool(device, poolInfo, null, pCommandPool) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create command pool");
            }

            commandPool = pCommandPool.get(0);
        }
    }

    /**
     * The thread the open recording belongs to.
     *
     * <p>A VkCommandBuffer may only be recorded by one thread at a time, and uploads come from more
     * than one: the frame's own texture work runs on the client thread while other paths can be
     * reached from elsewhere. The shared recording is therefore handed out only to the thread that
     * opened it; anything else gets its own buffer and submits it itself, exactly as before.
     */
    private static volatile Thread recordingThread;

    /**
     * Opens a recording that collects the frame's transfers into one command buffer.
     *
     * <p>Without it every texture and buffer upload ended in its own vkQueueSubmit and its own
     * fence. Sampling the client thread put 7.1% of it inside that submit -- the cost is per
     * submit, not per byte, so a frame that touched a dozen small textures paid a dozen times.
     *
     * <p>Ordering is safe because transfers are submitted to the graphics queue, the same queue the
     * frame's draws go to, and {@link #endRecording()} runs before the frame is submitted. Work on
     * one queue executes in submission order, so the uploads have landed before any draw reads
     * them.
     */
    public static void startRecording() {
        if (recordingOpen) return;
        recordingOpen = true;
        recordingThread = Thread.currentThread();
    }

    public static void endRecording() {
        if (!recordingOpen) return;
        CommandBuffer buffer = currentCmdBuffer;
        currentCmdBuffer = null;
        recordingThread = null;
        recordingOpen = false;
        if (buffer != null) Synchronization.addFence(endCommands(buffer));
    }

    /** True while a batched recording session started by {@link #startRecording()} is open. */
    public static boolean hasRecording() {
        return recordingOpen;
    }

    /** Whether this thread may append to the shared recording. */
    private static boolean ownsRecording() {
        return recordingOpen && recordingThread == Thread.currentThread();
    }

    public static CommandBuffer getCommandBuffer() {
        if (!ownsRecording()) return beginCommands();
        if (currentCmdBuffer == null) currentCmdBuffer = beginCommands();
        return currentCmdBuffer;
    }

    public static long endIfNeeded(CommandBuffer commandBuffer) {
        // Compared by identity rather than trusting the caller: a buffer obtained before the
        // recording opened must still be submitted on its own.
        if (recordingOpen && commandBuffer == currentCmdBuffer
                && recordingThread == Thread.currentThread()) {
            return VK_NULL_HANDLE;
        }
        return endCommands(commandBuffer);
    }

    public synchronized static CommandBuffer beginCommands() {
        VkDevice device = device();

        try(MemoryStack stack = stackPush()) {

            if(current >= commandBuffers.size()) {
                CommandBuffer commandBuffer = new CommandBuffer();

                VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.callocStack(stack);
                allocInfo.sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO);
                allocInfo.level(VK_COMMAND_BUFFER_LEVEL_PRIMARY);
                allocInfo.commandPool(commandPool);
                allocInfo.commandBufferCount(1);

                // calloc, not malloc: on failure the driver leaves the output untouched and a
                // stale stack value would be used as a live Vulkan handle (native crash).
                PointerBuffer pCommandBuffer = stack.callocPointer(1);
                int result = vkAllocateCommandBuffers(device, allocInfo, pCommandBuffer);
                if (result != VK_SUCCESS || pCommandBuffer.get(0) == VK_NULL_HANDLE) {
                    throw new RuntimeException("Failed to allocate transfer command buffer: " + result);
                }

                VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.callocStack(stack);
                fenceInfo.sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO);
                fenceInfo.flags(VK_FENCE_CREATE_SIGNALED_BIT);

                LongBuffer pFence = stack.callocLong(1);
                result = vkCreateFence(device, fenceInfo, null, pFence);
                if (result != VK_SUCCESS || pFence.get(0) == VK_NULL_HANDLE) {
                    vkFreeCommandBuffers(device, commandPool, pCommandBuffer);
                    throw new RuntimeException("Failed to create transfer fence: " + result);
                }

                commandBuffer.handle = new VkCommandBuffer(pCommandBuffer.get(0), device);
                commandBuffer.fence = pFence.get(0);

                fences.add(commandBuffer.fence);
                commandBuffers.add(commandBuffer);
            }

            CommandBuffer commandBuffer = commandBuffers.get(current);

            // The buffer may still be in flight from an earlier frame; re-recording it before the
            // GPU is done is undefined behaviour. Freshly created fences are signaled, so this is
            // a no-op for new buffers.
            vkWaitForFences(device, commandBuffer.fence, true, VUtil.UINT64_MAX);

            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.callocStack(stack);
            beginInfo.sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);
            beginInfo.flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);

            vkBeginCommandBuffer(commandBuffer.handle, beginInfo);

            current++;

            return commandBuffer;
        }
    }

    public synchronized static long endCommands(CommandBuffer commandBuffer) {
        if (commandBuffer == null) return VK_NULL_HANDLE;

        VkDevice device = device();

        try(MemoryStack stack = stackPush()) {
            long fence = commandBuffer.fence;

            vkEndCommandBuffer(commandBuffer.handle);

            vkResetFences(device, fence);

            VkSubmitInfo.Buffer submitInfo = VkSubmitInfo.callocStack(1, stack);
            submitInfo.sType(VK_STRUCTURE_TYPE_SUBMIT_INFO);
            submitInfo.pCommandBuffers(stack.pointers(commandBuffer.handle));

            int result = vkQueueSubmit(Vulkan.getGraphicsQueue(), submitInfo, fence);
            if (result != VK_SUCCESS) {
                // Nothing will ever signal the fence — never hand it out to be waited on.
                vkResetFences(device, fence);
                throw new RuntimeException("Failed to submit transfer command buffer: " + result);
            }

            return fence;
        }
    }

    public static long getCommandPool() {
        return commandPool;
    }

    public void resetCommandPool() {
        vkResetCommandPool(device(), commandPool, 0);
    }

    public static void resetCurrent() {
        current = 0;
    }

    public static void cleanUp() {
        if (device == null) return;
        for(long fence : fences) {
            vkDestroyFence(device, fence, null);
        }
        fences.clear();
        commandBuffers.clear();
        current = 0;
        currentCmdBuffer = null;
    }

    public static class CommandBuffer {
        VkCommandBuffer handle;
        long fence;

        public VkCommandBuffer getHandle() {
            return handle;
        }

        public long getFence() {
            return fence;
        }
    }
}
