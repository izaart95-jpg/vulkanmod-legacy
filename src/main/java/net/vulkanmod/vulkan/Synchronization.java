package net.vulkanmod.vulkan;

import net.vulkanmod.vulkan.util.VUtil;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkDevice;

import java.nio.LongBuffer;

import static org.lwjgl.vulkan.VK10.*;

public class Synchronization {
    private static final int allocSize = 10000;
    private static final LongBuffer fences = MemoryUtil.memAllocLong(allocSize);
    private static int idx = 0;

    // Auto-flush threshold: flush when buffer reaches 90% capacity to prevent overflow
    private static final int FLUSH_THRESHOLD = (int)(allocSize * 0.9);

    // Pre-allocated arrays — eliminates new long[] per frame
    private static final int BATCH_SIZE = 50;
    private static final long[] batchArray = new long[BATCH_SIZE];
    private static long[] remainderArray = new long[0]; // sized to match the exact remainder

    public synchronized static void addFence(long fence) {
        // VK_NULL_HANDLE (and the legacy -1 "nothing submitted" marker) must never reach
        // vkWaitForFences: the driver dereferences the handle and crashes.
        if (fence == VK_NULL_HANDLE || fence == -1L) return;

        // Auto-flush if buffer is near capacity to prevent IndexOutOfBoundsException
        if (idx >= FLUSH_THRESHOLD) {
            waitFences();
        }
        // Safety check: if somehow still at capacity after flush, skip this fence
        if (idx >= allocSize) {
            return;
        }
        fences.put(idx, fence);
        idx++;
    }

    public synchronized static void waitFences() {
        if(idx == 0) {
            TransferQueue.resetCurrent();
            return;
        }

        VkDevice device = Vulkan.getDevice();
        if (device == null) {
            idx = 0;
            return;
        }

        int i;
        for (i = 0; i + BATCH_SIZE <= idx; i += BATCH_SIZE) {
            fences.position(i);
            fences.get(batchArray, 0, BATCH_SIZE);
            vkWaitForFences(device, batchArray, true, VUtil.UINT64_MAX);
        }
        int remaining = idx - i;
        if(remaining > 0) {
            // LWJGL derives fenceCount from the array length, so the array must be exactly
            // `remaining` long — a longer one would pass trailing VK_NULL_HANDLEs to the driver,
            // which radv dereferences unconditionally (SIGSEGV in vk_common_WaitForFences).
            if (remainderArray.length != remaining) {
                remainderArray = new long[remaining];
            }
            fences.position(i);
            fences.get(remainderArray, 0, remaining);
            vkWaitForFences(device, remainderArray, true, VUtil.UINT64_MAX);
        }

        fences.position(0);
        idx = 0;

        TransferQueue.resetCurrent();
    }
}
