package net.vulkanmod.vulkan;

import net.vulkanmod.Initializer;
import net.vulkanmod.util.AndroidUtil;
import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.vulkan.memory.MemoryTypes;
import net.vulkanmod.vulkan.memory.StagingBuffer;
import net.vulkanmod.vulkan.util.VUtil;
import org.jetbrains.annotations.Nullable;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.vma.*;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.*;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toSet;
import static net.vulkanmod.vulkan.texture.VulkanImage.transitionImageLayout;
import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.glfw.GLFWVulkan.glfwCreateWindowSurface;
import static org.lwjgl.glfw.GLFWVulkan.glfwGetRequiredInstanceExtensions;
import static org.lwjgl.system.MemoryStack.stackGet;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.util.vma.Vma.vmaCreateAllocator;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_EXT_DEBUG_UTILS_EXTENSION_NAME;
import static org.lwjgl.vulkan.EXTDebugUtils.VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT;
import static org.lwjgl.vulkan.EXTDebugUtils.vkCreateDebugUtilsMessengerEXT;
import static org.lwjgl.vulkan.EXTDebugUtils.vkDestroyDebugUtilsMessengerEXT;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.VK_IMAGE_LAYOUT_PRESENT_SRC_KHR;
import static org.lwjgl.vulkan.KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRSwapchain.VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR;
import static org.lwjgl.vulkan.KHRSwapchain.vkCreateSwapchainKHR;
import static org.lwjgl.vulkan.KHRSwapchain.vkDestroySwapchainKHR;
import static org.lwjgl.vulkan.KHRSwapchain.vkGetSwapchainImagesKHR;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.VK_API_VERSION_1_1;
import static org.lwjgl.vulkan.VK12.VK_API_VERSION_1_2;

public class Vulkan {

    private static final int UINT32_MAX = 0xFFFFFFFF;

    // Android Vulkan drivers are only guaranteed to expose 1.0.  Asking for a
    // newer instance/allocator version can make vkCreateInstance fail even
    // though the device would render fine with the 1.0 feature set used here.
    private static final boolean ANDROID = AndroidUtil.isAndroid();
    // MJLauncher also runs the game on Android, but it hands GLFW a real
    // native window and expects Vulkan surfaces to be created through GLFW's
    // own VK_KHR_android_surface implementation.  Pojav instead returns the
    // ANativeWindow pointer from glfwCreateWindow, which is passed to
    // vkCreateAndroidSurfaceKHR directly.
    private static final boolean MJ_LAUNCHER = ANDROID && AndroidUtil.isMJLauncher();
    private static final int TARGET_API_VERSION = ANDROID ? VK_API_VERSION_1_0 : VK_API_VERSION_1_2;

    // VK_KHR_android_surface is not part of the LWJGL 3.3.1 bindings the
    // desktop jar compiles against.  The Android jar bundles the 3.3.3
    // bindings, so the call is made reflectively.  The values below are fixed
    // by the Vulkan API.
    private static final String VK_KHR_SURFACE_EXTENSION_NAME = "VK_KHR_surface";
    private static final String VK_KHR_ANDROID_SURFACE_EXTENSION_NAME = "VK_KHR_android_surface";
    private static final int VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR = 1000008000;

    public static final int INDEX_SIZE = Short.BYTES;

    private static final boolean ENABLE_VALIDATION_LAYERS = false;
//    private static final boolean ENABLE_VALIDATION_LAYERS = true;

    public static final Set<String> VALIDATION_LAYERS;
    static {
        if(ENABLE_VALIDATION_LAYERS) {
            VALIDATION_LAYERS = new HashSet<>();
            VALIDATION_LAYERS.add("VK_LAYER_KHRONOS_validation");

        } else {
            // We are not going to use it, so we don't create it
            VALIDATION_LAYERS = null;
        }
    }

    private static final Set<String> DEVICE_EXTENSIONS = Stream.of(VK_KHR_SWAPCHAIN_EXTENSION_NAME)
            .collect(toSet());



    private static int debugCallback(int messageSeverity, int messageType, long pCallbackData, long pUserData) {

        VkDebugUtilsMessengerCallbackDataEXT callbackData = VkDebugUtilsMessengerCallbackDataEXT.create(pCallbackData);

//        System.err.println("Stack dump:");
//        Thread.dumpStack();
        System.err.println("Validation layer: " + callbackData.pMessageString());

        return VK_FALSE;
    }

    private static int createDebugUtilsMessengerEXT(VkInstance instance, VkDebugUtilsMessengerCreateInfoEXT createInfo,
                                                    VkAllocationCallbacks allocationCallbacks, LongBuffer pDebugMessenger) {

        if(vkGetInstanceProcAddr(instance, "vkCreateDebugUtilsMessengerEXT") != NULL) {
            return vkCreateDebugUtilsMessengerEXT(instance, createInfo, allocationCallbacks, pDebugMessenger);
        }

        return VK_ERROR_EXTENSION_NOT_PRESENT;
    }

    private static void destroyDebugUtilsMessengerEXT(VkInstance instance, long debugMessenger, VkAllocationCallbacks allocationCallbacks) {

        if(vkGetInstanceProcAddr(instance, "vkDestroyDebugUtilsMessengerEXT") != NULL) {
            vkDestroyDebugUtilsMessengerEXT(instance, debugMessenger, allocationCallbacks);
        }

    }

    public static VkDevice getDevice() {
        return device;
    }

    public static VkPhysicalDevice getPhysicalDevice() {
        return physicalDevice;
    }

    public static long getAllocator() {
        return allocator;
    }

    static class QueueFamilyIndices {

        // We use Integer to use null as the empty value
        Integer graphicsFamily;
        Integer presentFamily;

        public boolean isComplete() {
            return graphicsFamily != null && presentFamily != null;
        }

        public int[] unique() {
            return IntStream.of(graphicsFamily, presentFamily).distinct().toArray();
        }

        public int[] array() {
            return new int[] {graphicsFamily, presentFamily};
        }
    }

    public static class SwapChainSupportDetails {

        VkSurfaceCapabilitiesKHR capabilities;
        VkSurfaceFormatKHR.Buffer formats;
        IntBuffer presentModes;

        public VkSurfaceCapabilitiesKHR getCapabilities() {
            return capabilities;
        }

    }

    public static long window;

    private static VkInstance instance;
    private static long debugMessenger;
    private static long surface;

    private static VkPhysicalDevice physicalDevice;
    private static VkDevice device;

    private static DeviceInfo deviceInfo;

    public static VkPhysicalDeviceProperties deviceProperties;
    public static VkPhysicalDeviceMemoryProperties memoryProperties;

    public static SwapChainSupportDetails swapChainSupport;

    private static VkQueue graphicsQueue;
    private static VkQueue presentQueue;

    private static final int frameQueueSize = 3; // Triple-buffered frames in flight

    private static long swapChain;
    private static List<Long> swapChainImages;
    private static int swapChainImageFormat;
    private static VkExtent2D swapChainExtent;
    private static List<Long> swapChainImageViews;
    private static List<Long> swapChainFramebuffers;

    // One depth attachment per swapchain image.
    //
    // A single shared one was a race the moment anything read it: with several frames in flight,
    // the hierarchical-Z pass would sample the same image a later frame is still writing. It went
    // unnoticed only because depth was cleared at pass start and discarded at the end, so nobody
    // ever looked at it.
    private static long[] depthImages = new long[0];
    private static long[] depthImageMemories = new long[0];
    private static long[] depthImageViews = new long[0];

    public static long getDepthImage(int frame) { return depthImages[frame % depthImages.length]; }
    public static long getDepthImageView(int frame) { return depthImageViews[frame % depthImageViews.length]; }
    public static int getDepthImageCount() { return depthImages.length; }
    public static int getDepthFormat() { return findDepthFormat(); }

    private static long renderPass;

    private static long commandPool;
    private static VkCommandBuffer immediateCmdBuffer;
    private static long immediateFence;

    private static long allocator;

    private static boolean vsync = false;

    private static StagingBuffer[] stagingBuffers;

    private static boolean multiDrawIndirectSupported = false;
    private static boolean wideLinesSupported = false;

    public static boolean supportsWideLines() { return wideLinesSupported; }
    private static boolean drawIndirectFirstInstanceSupported = false;

    /** True when the device can execute a batched terrain pass as one indirect multi-draw. */
    public static boolean supportsIndirectTerrain() {
        return multiDrawIndirectSupported && drawIndirectFirstInstanceSupported;
    }

    public static void initVulkan(long window) {
        createInstance();
        setupDebugMessenger();
        createSurface(window);
        pickPhysicalDevice();
        createLogicalDevice();
        createVma();
        MemoryTypes.createMemoryTypes();
        createCommandPool();
        allocateImmediateCmdBuffer();

        createSwapChain();
        createImageViews();
        createRenderPass();
        createDepthResources();
        createFramebuffers();

        createStagingBuffers();
    }

    private static void createStagingBuffers() {
        stagingBuffers = new StagingBuffer[getSwapChainImages().size()];

        for(int i = 0; i < stagingBuffers.length; ++i) {
            stagingBuffers[i] = new StagingBuffer(48 * 1024 * 1024);
        }
    }

    public static void recreateSwapChain() {

        Synchronization.waitFences();

//        vkDeviceWaitIdle(device);
        createSwapChain();

        swapChainFramebuffers.forEach(framebuffer -> vkDestroyFramebuffer(device, framebuffer, null));

//        swapChainImageViews.forEach(imageView -> vkDestroyImageView(device, imageView, null));

        destroyDepthResources();

//        vkDestroySwapchainKHR(device, swapChain, null);

//        createSwapChain();
        createImageViews();
        createDepthResources();
        createFramebuffers();
    }

    public static void cleanUp() {
        vkDeviceWaitIdle(device);

        // Save PSO cache to disk for faster startup next time
        Pipeline.savePipelineCacheToDisk();

        vkDestroyCommandPool(device, commandPool, null);
        vkDestroyCommandPool(device, TransferQueue.getCommandPool(), null);

        PerformanceProfiler.cleanUpGpuTimers();
        net.vulkanmod.vulkan.memory.ChunkArena.cleanUpAll();
        TransferQueue.cleanUp();

        vkDestroyDevice(device, null);
        vkDestroyInstance(instance, null);
    }

    private static void createInstance() {

        if(ENABLE_VALIDATION_LAYERS && !checkValidationLayerSupport()) {
            throw new RuntimeException("Validation requested but not supported");
        }

        try(MemoryStack stack = stackPush()) {

            // Use calloc to initialize the structs with 0s. Otherwise, the program can crash due to random values

            VkApplicationInfo appInfo = VkApplicationInfo.callocStack(stack);

            appInfo.sType(VK_STRUCTURE_TYPE_APPLICATION_INFO);
            appInfo.pApplicationName(stack.UTF8Safe("VulkanMod"));
            appInfo.applicationVersion(VK_MAKE_VERSION(1, 0, 0));
            appInfo.pEngineName(stack.UTF8Safe("No Engine"));
            appInfo.engineVersion(VK_MAKE_VERSION(1, 0, 0));
            appInfo.apiVersion(TARGET_API_VERSION);

            VkInstanceCreateInfo createInfo = VkInstanceCreateInfo.callocStack(stack);

            createInfo.sType(VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO);
            createInfo.pApplicationInfo(appInfo);
            // enabledExtensionCount is implicitly set when you call ppEnabledExtensionNames
            createInfo.ppEnabledExtensionNames(getRequiredExtensions());

            if(ENABLE_VALIDATION_LAYERS) {

                createInfo.ppEnabledLayerNames(asPointerBuffer(VALIDATION_LAYERS));

                VkDebugUtilsMessengerCreateInfoEXT debugCreateInfo = VkDebugUtilsMessengerCreateInfoEXT.callocStack(stack);
                populateDebugMessengerCreateInfo(debugCreateInfo);
                createInfo.pNext(debugCreateInfo.address());
            }

            // We need to retrieve the pointer of the created instance
            PointerBuffer instancePtr = stack.mallocPointer(1);

            if(vkCreateInstance(createInfo, null, instancePtr) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create instance");
            }

            instance = new VkInstance(instancePtr.get(0), createInfo);
        }
    }

    private static void populateDebugMessengerCreateInfo(VkDebugUtilsMessengerCreateInfoEXT debugCreateInfo) {
        debugCreateInfo.sType(VK_STRUCTURE_TYPE_DEBUG_UTILS_MESSENGER_CREATE_INFO_EXT);
        debugCreateInfo.messageSeverity(VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT);
        debugCreateInfo.messageType(VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT | VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT);
        debugCreateInfo.pfnUserCallback(Vulkan::debugCallback);
    }

    private static void setupDebugMessenger() {

        if(!ENABLE_VALIDATION_LAYERS) {
            return;
        }

        try(MemoryStack stack = stackPush()) {

            VkDebugUtilsMessengerCreateInfoEXT createInfo = VkDebugUtilsMessengerCreateInfoEXT.callocStack(stack);

            populateDebugMessengerCreateInfo(createInfo);

            LongBuffer pDebugMessenger = stack.longs(VK_NULL_HANDLE);

            if(createDebugUtilsMessengerEXT(instance, createInfo, null, pDebugMessenger) != VK_SUCCESS) {
                throw new RuntimeException("Failed to set up debug messenger");
            }

            debugMessenger = pDebugMessenger.get(0);
        }
    }

    private static void createSurface(long handle) {
        window = handle;

        if (ANDROID) {
            if (MJ_LAUNCHER) {
                // MJLauncher returns a real _GLFWwindow*, not an ANativeWindow.
                // Its native GLFW implements glfwCreateWindowSurface with
                // VK_KHR_android_surface over the surface the launcher owns.
                Initializer.LOGGER.info("Creating Vulkan surface through MJLauncher GLFW for window: " + window);
                createGlfwSurface();
            } else {
                Initializer.LOGGER.info("Creating Android Vulkan surface for native window: " + window);
                surface = createAndroidSurface(instance, window);
            }
            Initializer.LOGGER.info("Vulkan surface created successfully: " + surface);
            return;
        }

        createGlfwSurface();
    }

    /**
     * Uses GLFW's Vulkan surface helpers.  This runs on desktop and on
     * MJLauncher, which supplies its own native GLFW and, unlike Pojav, has
     * full Vulkan surface support.  Pojav replaces GLFWVulkan with a stub
     * whose classpath has no Vulkan bindings, so that launcher keeps using
     * the direct ANativeWindow path instead.
     */
    private static void createGlfwSurface() {
        try(MemoryStack stack = stackPush()) {

            LongBuffer pSurface = stack.longs(VK_NULL_HANDLE);

            Initializer.LOGGER.info("Creating Vulkan surface for window: " + window);
            int result = glfwCreateWindowSurface(instance, window, null, pSurface);
            if(result != VK_SUCCESS) {
                throw new RuntimeException("Failed to create window surface, error code: " + result);
            }

            surface = pSurface.get(0);
            Initializer.LOGGER.info("Vulkan surface created successfully: " + surface);
        }
    }

    /**
     * Creates a VkSurfaceKHR from the ANativeWindow Pojav returned from
     * glfwCreateWindow(GLFW_NO_API).
     *
     * <p>The desktop build compiles against LWJGL 3.3.1, which predates the
     * {@code VK_KHR_android_surface} bindings.  The Android jar bundles
     * LWJGL 3.3.3, so the two classes needed for the call are looked up at
     * runtime.  On desktop this method is never invoked.
     */
    private static long createAndroidSurface(VkInstance vkInstance, long nativeWindow) {
        try(MemoryStack stack = stackPush()) {
            Class<?> infoClass = Class.forName("org.lwjgl.vulkan.VkAndroidSurfaceCreateInfoKHR");
            Class<?> androidClass = Class.forName("org.lwjgl.vulkan.KHRAndroidSurface");

            Object createInfo = infoClass.getMethod("calloc", MemoryStack.class).invoke(null, stack);
            infoClass.getMethod("sType", int.class)
                    .invoke(createInfo, VK_STRUCTURE_TYPE_ANDROID_SURFACE_CREATE_INFO_KHR);
            infoClass.getMethod("window", long.class).invoke(createInfo, nativeWindow);

            LongBuffer pSurface = stack.longs(VK_NULL_HANDLE);
            int result = (Integer) androidClass.getMethod("vkCreateAndroidSurfaceKHR",
                            VkInstance.class, infoClass, VkAllocationCallbacks.class, LongBuffer.class)
                    .invoke(null, vkInstance, createInfo, null, pSurface);

            if(result != VK_SUCCESS) {
                throw new RuntimeException("Failed to create Android window surface, error code: " + result);
            }

            return pSurface.get(0);
        } catch (ReflectiveOperationException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new RuntimeException("Failed to create Android window surface", cause);
        }
    }

    private static void pickPhysicalDevice() {

        try(MemoryStack stack = stackPush()) {

            IntBuffer deviceCount = stack.ints(0);

            vkEnumeratePhysicalDevices(instance, deviceCount, null);

            if(deviceCount.get(0) == 0) {
                throw new RuntimeException("Failed to find GPUs with Vulkan support");
            }

            PointerBuffer ppPhysicalDevices = stack.mallocPointer(deviceCount.get(0));

            vkEnumeratePhysicalDevices(instance, deviceCount, ppPhysicalDevices);

            ArrayList<VkPhysicalDevice> integratedGPUs = new ArrayList<>();
            ArrayList<VkPhysicalDevice> otherDevices = new ArrayList<>();

            VkPhysicalDevice currentDevice = null;
            boolean flag = false;

            for(int i = 0; i < ppPhysicalDevices.capacity();i++) {

                currentDevice = new VkPhysicalDevice(ppPhysicalDevices.get(i), instance);

                VkPhysicalDeviceProperties deviceProperties = VkPhysicalDeviceProperties.callocStack(stack);
                vkGetPhysicalDeviceProperties(currentDevice, deviceProperties);

                if(isDeviceSuitable(currentDevice)) {
                    if(deviceProperties.deviceType() == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU){
                        flag = true;
                        break;
                    }
                    else if(deviceProperties.deviceType() == VK_PHYSICAL_DEVICE_TYPE_INTEGRATED_GPU) integratedGPUs.add(currentDevice);
                    else otherDevices.add(currentDevice);

                }
            }

            if(!flag) {
                if(!integratedGPUs.isEmpty()) currentDevice = integratedGPUs.get(0);
                else if(!otherDevices.isEmpty()) currentDevice = otherDevices.get(0);
                else {
                    Initializer.LOGGER.error(DeviceInfo.debugString(ppPhysicalDevices, DEVICE_EXTENSIONS, instance));
                    throw new RuntimeException("Failed to find a suitable GPU");
                }
            }

            physicalDevice = currentDevice;

            //Get device properties

            deviceProperties = VkPhysicalDeviceProperties.malloc();
            vkGetPhysicalDeviceProperties(physicalDevice, deviceProperties);

            memoryProperties = VkPhysicalDeviceMemoryProperties.malloc();
            vkGetPhysicalDeviceMemoryProperties(physicalDevice, memoryProperties);

            deviceInfo = new DeviceInfo(physicalDevice, deviceProperties);
        }
    }

    private static void createLogicalDevice() {

        try(MemoryStack stack = stackPush()) {

            QueueFamilyIndices indices = findQueueFamilies(physicalDevice);

            int[] uniqueQueueFamilies = indices.unique();

            VkDeviceQueueCreateInfo.Buffer queueCreateInfos = VkDeviceQueueCreateInfo.callocStack(uniqueQueueFamilies.length, stack);

            for(int i = 0;i < uniqueQueueFamilies.length;i++) {
                VkDeviceQueueCreateInfo queueCreateInfo = queueCreateInfos.get(i);
                queueCreateInfo.sType(VK_STRUCTURE_TYPE_DEVICE_QUEUE_CREATE_INFO);
                queueCreateInfo.queueFamilyIndex(uniqueQueueFamilies[i]);
                queueCreateInfo.pQueuePriorities(stack.floats(1.0f));
            }

            VkPhysicalDeviceFeatures availableFeatures = VkPhysicalDeviceFeatures.callocStack(stack);
            vkGetPhysicalDeviceFeatures(physicalDevice, availableFeatures);

            VkPhysicalDeviceFeatures deviceFeatures = VkPhysicalDeviceFeatures.callocStack(stack);
            deviceFeatures.samplerAnisotropy(true);
            if (availableFeatures.multiDrawIndirect()) {
                deviceFeatures.multiDrawIndirect(true);
                multiDrawIndirectSupported = true;
            }
            // Needed to address a per-chunk instanced attribute from an indirect command:
            // without it firstInstance must be 0 in every indirect draw.
            if (availableFeatures.drawIndirectFirstInstance()) {
                deviceFeatures.drawIndirectFirstInstance(true);
                drawIndirectFirstInstanceSupported = true;
            }
            // Needed for the block selection outline, which Minecraft draws with glLineWidth(2).
            if (availableFeatures.wideLines()) {
                deviceFeatures.wideLines(true);
                wideLinesSupported = true;
            }

            VkDeviceCreateInfo createInfo = VkDeviceCreateInfo.callocStack(stack);

            createInfo.sType(VK_STRUCTURE_TYPE_DEVICE_CREATE_INFO);
            createInfo.pQueueCreateInfos(queueCreateInfos);
            // queueCreateInfoCount is automatically set

            createInfo.pEnabledFeatures(deviceFeatures);

            createInfo.ppEnabledExtensionNames(asPointerBuffer(DEVICE_EXTENSIONS));

            if(ENABLE_VALIDATION_LAYERS) {
                createInfo.ppEnabledLayerNames(asPointerBuffer(VALIDATION_LAYERS));
            }

            PointerBuffer pDevice = stack.pointers(VK_NULL_HANDLE);

            if(vkCreateDevice(physicalDevice, createInfo, null, pDevice) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create logical device");
            }

            device = new VkDevice(pDevice.get(0), physicalDevice, createInfo);

            PointerBuffer pQueue = stack.pointers(VK_NULL_HANDLE);

            vkGetDeviceQueue(device, indices.graphicsFamily, 0, pQueue);
            graphicsQueue = new VkQueue(pQueue.get(0), device);

            vkGetDeviceQueue(device, indices.presentFamily, 0, pQueue);
            presentQueue = new VkQueue(pQueue.get(0), device);

        }
    }

    private static void createVma() {
        try(MemoryStack stack = stackPush()) {

            VmaVulkanFunctions vulkanFunctions = VmaVulkanFunctions.callocStack(stack);
            vulkanFunctions.set(instance, device);

            VmaAllocatorCreateInfo allocatorCreateInfo = VmaAllocatorCreateInfo.callocStack(stack);
            allocatorCreateInfo.physicalDevice(physicalDevice);
            allocatorCreateInfo.device(device);
            allocatorCreateInfo.pVulkanFunctions(vulkanFunctions);
            allocatorCreateInfo.instance(instance);
            allocatorCreateInfo.vulkanApiVersion(TARGET_API_VERSION);

            PointerBuffer pAllocator = stack.pointers(VK_NULL_HANDLE);

            if (vmaCreateAllocator(allocatorCreateInfo, pAllocator) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create command pool");
            }

            allocator = pAllocator.get(0);
        }
    }

    private static void createCommandPool() {

        try(MemoryStack stack = stackPush()) {

            QueueFamilyIndices queueFamilyIndices = findQueueFamilies(physicalDevice);

            VkCommandPoolCreateInfo poolInfo = VkCommandPoolCreateInfo.callocStack(stack);
            poolInfo.sType(VK_STRUCTURE_TYPE_COMMAND_POOL_CREATE_INFO);
            poolInfo.queueFamilyIndex(queueFamilyIndices.graphicsFamily);
            poolInfo.flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT);

            LongBuffer pCommandPool = stack.mallocLong(1);

            if (vkCreateCommandPool(device, poolInfo, null, pCommandPool) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create command pool");
            }

            commandPool = pCommandPool.get(0);
        }
    }

    private static void createSwapChain() {

        try(MemoryStack stack = stackPush()) {

            long oldSwapchain = swapChain != 0L ? swapChain : VK_NULL_HANDLE;

            swapChainSupport = querySwapChainSupport(physicalDevice, null);

            VkSurfaceFormatKHR surfaceFormat = chooseSwapSurfaceFormat(swapChainSupport.formats);
            int presentMode = chooseSwapPresentMode(swapChainSupport.presentModes);
            VkExtent2D extent = chooseSwapExtent(swapChainSupport.capabilities);

            // Some Android drivers require more than three swap chain images
            // (minImageCount can be 4 or higher), so never ask for fewer than
            // the surface reports and still respect maxImageCount.
            int minImageCount = Math.max(swapChainSupport.capabilities.minImageCount(), frameQueueSize);
            if(swapChainSupport.capabilities.maxImageCount() > 0 && minImageCount > swapChainSupport.capabilities.maxImageCount()) {
                minImageCount = swapChainSupport.capabilities.maxImageCount();
            }
            IntBuffer imageCount = stack.ints(minImageCount);

            VkSwapchainCreateInfoKHR createInfo = VkSwapchainCreateInfoKHR.callocStack(stack);

            createInfo.sType(VK_STRUCTURE_TYPE_SWAPCHAIN_CREATE_INFO_KHR);
            createInfo.surface(surface);

            // Image settings
            swapChainImageFormat = surfaceFormat.format();
            swapChainExtent = VkExtent2D.create().set(extent);

            createInfo.minImageCount(imageCount.get(0));
//            createInfo.imageFormat(surfaceFormat.format());
            createInfo.imageFormat(swapChainImageFormat);
            createInfo.imageColorSpace(surfaceFormat.colorSpace());
            createInfo.imageExtent(extent);
            createInfo.imageArrayLayers(1);
            createInfo.imageUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT);

            QueueFamilyIndices indices = findQueueFamilies(physicalDevice);

            if(!indices.graphicsFamily.equals(indices.presentFamily)) {
                createInfo.imageSharingMode(VK_SHARING_MODE_CONCURRENT);
                createInfo.pQueueFamilyIndices(stack.ints(indices.graphicsFamily, indices.presentFamily));
            } else {
                createInfo.imageSharingMode(VK_SHARING_MODE_EXCLUSIVE);
            }

            createInfo.preTransform(swapChainSupport.capabilities.currentTransform());
            createInfo.compositeAlpha(VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR);
            createInfo.presentMode(presentMode);
            createInfo.clipped(true);

            //long oldSwapchain = swapChain != NULL ? swapChain : VK_NULL_HANDLE;
            createInfo.oldSwapchain(oldSwapchain);

            LongBuffer pSwapChain = stack.longs(VK_NULL_HANDLE);

            if(vkCreateSwapchainKHR(device, createInfo, null, pSwapChain) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create swap chain");
            }

            if(oldSwapchain != VK_NULL_HANDLE) {
                swapChainImageViews.forEach(imageView -> vkDestroyImageView(device, imageView, null));
                vkDestroySwapchainKHR(device, swapChain, null);
            }

            swapChain = pSwapChain.get(0);

            vkGetSwapchainImagesKHR(device, swapChain, imageCount, null);

            LongBuffer pSwapchainImages = stack.mallocLong(imageCount.get(0));

            vkGetSwapchainImagesKHR(device, swapChain, imageCount, pSwapchainImages);

            swapChainImages = new ArrayList<>(imageCount.get(0));

            for(int i = 0;i < pSwapchainImages.capacity();i++) {
                swapChainImages.add(pSwapchainImages.get(i));
            }
        }
    }

    private static void createImageViews() {

        swapChainImageViews = new ArrayList<>(swapChainImages.size());

        for(long swapChainImage : swapChainImages) {
            swapChainImageViews.add(createImageView(swapChainImage, swapChainImageFormat, VK_IMAGE_ASPECT_COLOR_BIT, 1));
        }
    }

    private static void createRenderPass() {

        try(MemoryStack stack = stackPush()) {

            VkAttachmentDescription.Buffer attachments = VkAttachmentDescription.callocStack(2, stack);
            VkAttachmentReference.Buffer attachmentRefs = VkAttachmentReference.callocStack(2, stack);

            // Color attachments
            VkAttachmentDescription colorAttachment = attachments.get(0);
            colorAttachment.format(swapChainImageFormat);
            colorAttachment.samples(VK_SAMPLE_COUNT_1_BIT);
            colorAttachment.loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR);
            colorAttachment.storeOp(VK_ATTACHMENT_STORE_OP_STORE);
            colorAttachment.stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE);
            colorAttachment.stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE);
            colorAttachment.initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            colorAttachment.finalLayout(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);

            int y = attachments.get(0).samples();

            VkAttachmentReference colorAttachmentRef = attachmentRefs.get(0);
            colorAttachmentRef.attachment(0);
            colorAttachmentRef.layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);

            // Depth-Stencil attachments

            VkAttachmentDescription depthAttachment = attachments.get(1);
            depthAttachment.format(findDepthFormat());
            depthAttachment.samples(VK_SAMPLE_COUNT_1_BIT);
            depthAttachment.loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR);
            // Only kept when something reads it back: the hierarchical-Z pass reduces it into the
            // depth pyramid. With HZB off nothing does, and STORE would write the whole depth
            // image out to memory at the end of every render pass for no reader -- a full screen
            // write per frame whose cost is the same whether the scene is one block or a city.
            depthAttachment.storeOp(net.vulkanmod.vulkan.compute.HZBCulling.ENABLED
                    ? VK_ATTACHMENT_STORE_OP_STORE
                    : VK_ATTACHMENT_STORE_OP_DONT_CARE);
            depthAttachment.stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE);
            depthAttachment.stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE);
            depthAttachment.initialLayout(VK_IMAGE_LAYOUT_UNDEFINED);
            depthAttachment.finalLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);

            VkAttachmentReference depthAttachmentRef = attachmentRefs.get(1);
            depthAttachmentRef.attachment(1);
            depthAttachmentRef.layout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);

            VkSubpassDescription.Buffer subpass = VkSubpassDescription.callocStack(1, stack);
            subpass.pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS);
            subpass.colorAttachmentCount(1);
            subpass.pColorAttachments(VkAttachmentReference.callocStack(1, stack).put(0, colorAttachmentRef));
            subpass.pDepthStencilAttachment(depthAttachmentRef);

            VkSubpassDependency.Buffer dependency = VkSubpassDependency.callocStack(1, stack);
            dependency.srcSubpass(VK_SUBPASS_EXTERNAL);
            dependency.dstSubpass(0);
            dependency.srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT);
            dependency.srcAccessMask(0);
            dependency.dstStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT | VK_PIPELINE_STAGE_EARLY_FRAGMENT_TESTS_BIT);
            dependency.dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_READ_BIT | VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT | VK_ACCESS_DEPTH_STENCIL_ATTACHMENT_WRITE_BIT);

            VkRenderPassCreateInfo renderPassInfo = VkRenderPassCreateInfo.callocStack(stack);
            renderPassInfo.sType(VK_STRUCTURE_TYPE_RENDER_PASS_CREATE_INFO);
            renderPassInfo.pAttachments(attachments);
            renderPassInfo.pSubpasses(subpass);
            renderPassInfo.pDependencies(dependency);

            LongBuffer pRenderPass = stack.mallocLong(1);

            if(vkCreateRenderPass(device, renderPassInfo, null, pRenderPass) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create render pass");
            }

            renderPass = pRenderPass.get(0);
        }
    }

    private static void createDepthResources() {

        int count = swapChainImageViews.size();
        depthImages = new long[count];
        depthImageMemories = new long[count];
        depthImageViews = new long[count];

        int depthFormat = findDepthFormat();

        for (int i = 0; i < count; i++) {
            try (MemoryStack stack = stackPush()) {
                LongBuffer pDepthImage = stack.callocLong(1);
                PointerBuffer pDepthImageMemory = stack.callocPointer(1);

                // SAMPLED so the hierarchical-Z reduction can read the finished depth buffer.
                MemoryManager.createImage(
                        swapChainExtent.width(), swapChainExtent.height(), 1,
                        depthFormat,
                        VK_IMAGE_TILING_OPTIMAL,
                        // SAMPLED is only declared when the HZB is actually going to sample it.
                        // Declaring it unconditionally lets drivers fall back to an uncompressed
                        // depth layout, since a sampled image has to be readable by anything --
                        // which costs depth bandwidth on every draw of every frame.
                        net.vulkanmod.vulkan.compute.HZBCulling.ENABLED
                                ? VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT | VK_IMAGE_USAGE_SAMPLED_BIT
                                : VK_IMAGE_USAGE_DEPTH_STENCIL_ATTACHMENT_BIT,
                        VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT,
                        pDepthImage,
                        pDepthImageMemory);

                depthImages[i] = pDepthImage.get(0);
                depthImageMemories[i] = pDepthImageMemory.get(0);
                depthImageViews[i] = createImageView(depthImages[i], depthFormat, VK_IMAGE_ASPECT_DEPTH_BIT, 1);

                VkCommandBuffer commandBuffer = beginImmediateCmd();
                transitionImageLayout(commandBuffer, depthImages[i], depthFormat,
                        VK_IMAGE_LAYOUT_UNDEFINED, VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL, 1);
                endImmediateCmd();
            }
        }
    }

    private static void destroyDepthResources() {
        for (int i = 0; i < depthImages.length; i++) {
            MemoryManager.freeImage(depthImages[i], depthImageMemories[i]);
            vkDestroyImageView(device, depthImageViews[i], null);
        }
        depthImages = new long[0];
        depthImageMemories = new long[0];
        depthImageViews = new long[0];
    }

    private static int findSupportedFormat(IntBuffer formatCandidates, int tiling, int features) {

        try(MemoryStack stack = stackPush()) {

            VkFormatProperties props = VkFormatProperties.callocStack(stack);

            for(int i = 0; i < formatCandidates.capacity(); ++i) {

                int format = formatCandidates.get(i);

                vkGetPhysicalDeviceFormatProperties(physicalDevice, format, props);

                if(tiling == VK_IMAGE_TILING_LINEAR && (props.linearTilingFeatures() & features) == features) {
                    return format;
                } else if(tiling == VK_IMAGE_TILING_OPTIMAL && (props.optimalTilingFeatures() & features) == features) {
                    return format;
                }

            }
        }

        throw new RuntimeException("Failed to find supported format");
    }
    private static int findDepthFormat() {
        return findSupportedFormat(
                stackGet().ints(VK_FORMAT_D32_SFLOAT, VK_FORMAT_D32_SFLOAT_S8_UINT, VK_FORMAT_D24_UNORM_S8_UINT),
                VK_IMAGE_TILING_OPTIMAL,
                VK_FORMAT_FEATURE_DEPTH_STENCIL_ATTACHMENT_BIT);
    }
    private static VkSurfaceFormatKHR chooseSwapSurfaceFormat(VkSurfaceFormatKHR.Buffer availableFormats) {
        List<VkSurfaceFormatKHR> list = availableFormats.stream().collect(java.util.stream.Collectors.toList());

        VkSurfaceFormatKHR format = list.get(0);
        boolean flag = true;

        for (VkSurfaceFormatKHR availableFormat : list) {
            if (availableFormat.format() == VK_FORMAT_R8G8B8A8_UNORM && availableFormat.colorSpace() == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR)
                return availableFormat;

            if (availableFormat.format() == VK_FORMAT_B8G8R8A8_UNORM && availableFormat.colorSpace() == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
                format = availableFormat;
                flag = false;
            }
        }

        if(flag) System.out.println("Non-optimal surface format.");
        return format;
    }
    private static int chooseSwapPresentMode(IntBuffer availablePresentModes) {
        if (vsync) return VK_PRESENT_MODE_FIFO_KHR;

        // Prefer MAILBOX (triple-buffered, no tearing) → IMMEDIATE → FIFO fallback
        boolean hasMailbox = false;
        boolean hasImmediate = false;
        for (int i = 0; i < availablePresentModes.capacity(); i++) {
            int mode = availablePresentModes.get(i);
            if (mode == VK_PRESENT_MODE_MAILBOX_KHR) hasMailbox = true;
            if (mode == VK_PRESENT_MODE_IMMEDIATE_KHR) hasImmediate = true;
        }

        if (hasMailbox) return VK_PRESENT_MODE_MAILBOX_KHR;
        if (hasImmediate) return VK_PRESENT_MODE_IMMEDIATE_KHR;
        return VK_PRESENT_MODE_FIFO_KHR;
    }
    private static VkExtent2D chooseSwapExtent(VkSurfaceCapabilitiesKHR capabilities) {

        if(capabilities.currentExtent().width() != UINT32_MAX) {
            return capabilities.currentExtent();
        }

        IntBuffer width = stackGet().ints(0);
        IntBuffer height = stackGet().ints(0);

        glfwGetFramebufferSize(window, width, height);

        VkExtent2D actualExtent = VkExtent2D.mallocStack().set(width.get(0), height.get(0));

        VkExtent2D minExtent = capabilities.minImageExtent();
        VkExtent2D maxExtent = capabilities.maxImageExtent();

        actualExtent.width(clamp(minExtent.width(), maxExtent.width(), actualExtent.width()));
        actualExtent.height(clamp(minExtent.height(), maxExtent.height(), actualExtent.height()));

        return actualExtent;
    }
    private static int clamp(int min, int max, int value) {
        return Math.max(min, Math.min(max, value));
    }
    public static long createImageView(long image, int format, int aspectFlags, int mipLevels) {

        try(MemoryStack stack = stackPush()) {

            VkImageViewCreateInfo viewInfo = VkImageViewCreateInfo.callocStack(stack);
            viewInfo.sType(VK_STRUCTURE_TYPE_IMAGE_VIEW_CREATE_INFO);
            viewInfo.image(image);
            viewInfo.viewType(VK_IMAGE_VIEW_TYPE_2D);
            viewInfo.format(format);
            viewInfo.subresourceRange().aspectMask(aspectFlags);
            viewInfo.subresourceRange().baseMipLevel(0);
            viewInfo.subresourceRange().levelCount(mipLevels);
            viewInfo.subresourceRange().baseArrayLayer(0);
            viewInfo.subresourceRange().layerCount(1);

            LongBuffer pImageView = stack.mallocLong(1);

            if(vkCreateImageView(device, viewInfo, null, pImageView) != VK_SUCCESS) {
                throw new RuntimeException("Failed to create texture image view");
            }

            return pImageView.get(0);
        }
    }

    public static VkViewport.Buffer viewport(MemoryStack stack) {
        VkViewport.Buffer viewport = VkViewport.callocStack(1, stack);
        viewport.x(0.0f);
        viewport.y(swapChainExtent.height());
        viewport.width(swapChainExtent.width());
        viewport.height(-swapChainExtent.height());
        viewport.minDepth(0.0f);
        viewport.maxDepth(1.0f);

        return viewport;
    }
    public static VkRect2D.Buffer scissor(MemoryStack stack) {
        VkRect2D.Buffer scissor = VkRect2D.callocStack(1, stack);
        scissor.offset(VkOffset2D.callocStack(stack).set(0, 0));
        scissor.extent(swapChainExtent);

        return scissor;
    }

    private static void createFramebuffers() {

        swapChainFramebuffers = new ArrayList<>(swapChainImageViews.size());

        try(MemoryStack stack = stackPush()) {

            LongBuffer attachments = stack.longs(VK_NULL_HANDLE, VK_NULL_HANDLE);
            //attachments = stack.mallocLong(1);
            LongBuffer pFramebuffer = stack.mallocLong(1);

            // Lets allocate the create info struct once and just update the pAttachments field each iteration
            VkFramebufferCreateInfo framebufferInfo = VkFramebufferCreateInfo.callocStack(stack);
            framebufferInfo.sType(VK_STRUCTURE_TYPE_FRAMEBUFFER_CREATE_INFO);
            framebufferInfo.renderPass(renderPass);
            framebufferInfo.width(swapChainExtent.width());
            framebufferInfo.height(swapChainExtent.height());
            framebufferInfo.layers(1);

            for(int fbIndex = 0; fbIndex < swapChainImageViews.size(); fbIndex++) {
                long imageView = swapChainImageViews.get(fbIndex);

                attachments.put(0, imageView);
                attachments.put(1, depthImageViews[fbIndex]);

                framebufferInfo.pAttachments(attachments);

                if(vkCreateFramebuffer(device, framebufferInfo, null, pFramebuffer) != VK_SUCCESS) {
                    throw new RuntimeException("Failed to create framebuffer");
                }

                swapChainFramebuffers.add(pFramebuffer.get(0));
            }
        }
    }



    public static void copyStagingtoLocalBuffer(long srcBuffer, long dstBuffer, long dstOffset, long size) {

        try(MemoryStack stack = stackPush()) {

            VkCommandBuffer commandBuffer = beginImmediateCmd();

            VkBufferCopy.Buffer copyRegion = VkBufferCopy.callocStack(1, stack);
            copyRegion.size(size);
            copyRegion.dstOffset(dstOffset);

            vkCmdCopyBuffer(commandBuffer, srcBuffer, dstBuffer, copyRegion);

            endImmediateCmd();
        }
    }

    public static void copyStagingtoLocalBuffer(long srcBuffer, long srcOffset, long dstBuffer, long dstOffset, long size) {

        try(MemoryStack stack = stackPush()) {

            TransferQueue.CommandBuffer commandBuffer = TransferQueue.beginCommands();

            VkBufferCopy.Buffer copyRegion = VkBufferCopy.callocStack(1, stack);
            copyRegion.size(size);
            copyRegion.srcOffset(srcOffset);
            copyRegion.dstOffset(dstOffset);

            vkCmdCopyBuffer(commandBuffer.getHandle(), srcBuffer, dstBuffer, copyRegion);

            long fence = TransferQueue.endCommands(commandBuffer);
            Synchronization.addFence(fence);
        }
    }

    private static void allocateImmediateCmdBuffer() {
        try(MemoryStack stack = stackPush()) {

            VkCommandBufferAllocateInfo allocInfo = VkCommandBufferAllocateInfo.callocStack(stack);
            allocInfo.sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_ALLOCATE_INFO);
            allocInfo.level(VK_COMMAND_BUFFER_LEVEL_PRIMARY);
            allocInfo.commandPool(commandPool);
            allocInfo.commandBufferCount(1);

            // calloc, not malloc: on failure the driver leaves the output untouched and the
            // stale stack value would be used as a live Vulkan handle (native crash).
            PointerBuffer pCommandBuffer = stack.callocPointer(1);
            int result = vkAllocateCommandBuffers(device, allocInfo, pCommandBuffer);
            if (result != VK_SUCCESS || pCommandBuffer.get(0) == VK_NULL_HANDLE) {
                throw new RuntimeException("Failed to allocate the immediate command buffer: " + result);
            }
            immediateCmdBuffer = new VkCommandBuffer(pCommandBuffer.get(0), device);

            VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.callocStack(stack);
            fenceInfo.sType(VK_STRUCTURE_TYPE_FENCE_CREATE_INFO);
            fenceInfo.flags(VK_FENCE_CREATE_SIGNALED_BIT);

            LongBuffer pFence = stack.callocLong(1);
            result = vkCreateFence(device, fenceInfo, null, pFence);
            if (result != VK_SUCCESS || pFence.get(0) == VK_NULL_HANDLE) {
                throw new RuntimeException("Failed to create the immediate fence: " + result);
            }
            vkResetFences(device,  pFence.get(0));

            immediateFence = pFence.get(0);
        }
    }

    public static VkCommandBuffer beginImmediateCmd() {
        try (MemoryStack stack = stackPush()) {
            VkCommandBufferBeginInfo beginInfo = VkCommandBufferBeginInfo.callocStack(stack);
            beginInfo.sType(VK_STRUCTURE_TYPE_COMMAND_BUFFER_BEGIN_INFO);

            vkBeginCommandBuffer(immediateCmdBuffer, beginInfo);
        }
        return immediateCmdBuffer;
    }

    public static void endImmediateCmd() {
        try (MemoryStack stack = stackPush()) {
            vkEndCommandBuffer(immediateCmdBuffer);

            VkSubmitInfo.Buffer submitInfo = VkSubmitInfo.callocStack(1, stack);
            submitInfo.sType(VK_STRUCTURE_TYPE_SUBMIT_INFO);
            submitInfo.pCommandBuffers(stack.pointers(immediateCmdBuffer));

            int result = vkQueueSubmit(graphicsQueue, submitInfo, immediateFence);
            if (result != VK_SUCCESS) {
                // Nothing would ever signal the fence: waiting on it would hang the client thread.
                vkResetCommandBuffer(immediateCmdBuffer, 0);
                throw new RuntimeException("Failed to submit the immediate command buffer: " + result);
            }

            vkWaitForFences(device, immediateFence, true, VUtil.UINT64_MAX);
            vkResetFences(device, immediateFence);
            vkResetCommandBuffer(immediateCmdBuffer, 0);
        }

    }

    private static PointerBuffer asPointerBuffer(Collection<String> collection) {

        MemoryStack stack = stackGet();

        PointerBuffer buffer = stack.mallocPointer(collection.size());

        collection.stream()
                .map(stack::UTF8)
                .forEach(buffer::put);

        return buffer.rewind();
    }

    private static PointerBuffer getRequiredExtensions() {

        if(ANDROID) {
            // Pojav's GLFW is a stub that does not implement Vulkan surface
            // creation at all.  The Android platform extension is the only
            // way to turn the ANativeWindow Pojav returns into a VkSurfaceKHR.
            MemoryStack stack = stackGet();

            PointerBuffer extensions = stack.mallocPointer(ENABLE_VALIDATION_LAYERS ? 3 : 2);
            extensions.put(stack.UTF8(VK_KHR_SURFACE_EXTENSION_NAME));
            extensions.put(stack.UTF8(VK_KHR_ANDROID_SURFACE_EXTENSION_NAME));
            if(ENABLE_VALIDATION_LAYERS) {
                extensions.put(stack.UTF8(VK_EXT_DEBUG_UTILS_EXTENSION_NAME));
            }

            Initializer.LOGGER.info("Required Vulkan instance extensions (" + extensions.position() + "):");
            for (int i = 0; i < extensions.position(); i++) {
                Initializer.LOGGER.info("  - " + extensions.getStringUTF8(i));
            }

            return extensions.rewind();
        }

        return getDesktopRequiredExtensions();
    }

    /**
     * Desktop-only: kept in a separate method so the JVM never has to resolve
     * GLFWVulkan on Android (Pojav's stub cannot see the bundled bindings).
     */
    private static PointerBuffer getDesktopRequiredExtensions() {

        PointerBuffer glfwExtensions = glfwGetRequiredInstanceExtensions();

        if(glfwExtensions == null) {
            throw new RuntimeException("glfwGetRequiredInstanceExtensions() returned null - Vulkan surface extensions not available. "
                    + "This may happen if the Vulkan loader is not found or GLFW was not compiled with Vulkan support.");
        }

        Initializer.LOGGER.info("Required Vulkan instance extensions (" + glfwExtensions.capacity() + "):");
        for (int i = 0; i < glfwExtensions.capacity(); i++) {
            Initializer.LOGGER.info("  - " + glfwExtensions.getStringUTF8(i));
        }

        if(ENABLE_VALIDATION_LAYERS) {

            MemoryStack stack = stackGet();

            PointerBuffer extensions = stack.mallocPointer(glfwExtensions.capacity() + 1);

            extensions.put(glfwExtensions);
            extensions.put(stack.UTF8(VK_EXT_DEBUG_UTILS_EXTENSION_NAME));

            // Rewind the buffer before returning it to reset its position back to 0
            return extensions.rewind();
        }

        return glfwExtensions;
    }

    private static boolean isDeviceSuitable(VkPhysicalDevice device) {

        QueueFamilyIndices indices = findQueueFamilies(device);

        boolean extensionsSupported = checkDeviceExtensionSupport(device);
        boolean swapChainAdequate = false;

        if(extensionsSupported) {
            try(MemoryStack stack = stackPush()) {
                SwapChainSupportDetails swapChainSupport = querySwapChainSupport(device, stack);
                swapChainAdequate = swapChainSupport.formats.hasRemaining() && swapChainSupport.presentModes.hasRemaining() ;
            }
        }

        boolean anisotropicFilterSuppoted = false;
        try(MemoryStack stack = stackPush()) {
            VkPhysicalDeviceFeatures supportedFeatures = VkPhysicalDeviceFeatures.mallocStack(stack);
            vkGetPhysicalDeviceFeatures(device, supportedFeatures);
            anisotropicFilterSuppoted = supportedFeatures.samplerAnisotropy();
        }

        return indices.isComplete() && extensionsSupported && swapChainAdequate;
    }

    private static boolean checkDeviceExtensionSupport(VkPhysicalDevice device) {

        try(MemoryStack stack = stackPush()) {

            IntBuffer extensionCount = stack.ints(0);

            vkEnumerateDeviceExtensionProperties(device, (String)null, extensionCount, null);

            VkExtensionProperties.Buffer availableExtensions = VkExtensionProperties.mallocStack(extensionCount.get(0), stack);

            vkEnumerateDeviceExtensionProperties(device, (String)null, extensionCount, availableExtensions);

            Set<String> extensions = availableExtensions.stream()
                    .map(VkExtensionProperties::extensionNameString)
                    .collect(toSet());

            extensions.removeAll(DEVICE_EXTENSIONS);

            return availableExtensions.stream()
                    .map(VkExtensionProperties::extensionNameString)
                    .collect(toSet())
                    .containsAll(DEVICE_EXTENSIONS);
        }
    }

    public static SwapChainSupportDetails querySwapChainSupport(VkPhysicalDevice device, @Nullable MemoryStack stack) {

        SwapChainSupportDetails details = new SwapChainSupportDetails();

        if(stack != null) {
            details.capabilities = VkSurfaceCapabilitiesKHR.mallocStack();
            vkGetPhysicalDeviceSurfaceCapabilitiesKHR(device, surface, details.capabilities);

            IntBuffer count = stack.ints(0);

            vkGetPhysicalDeviceSurfaceFormatsKHR(device, surface, count, null);

            if(count.get(0) != 0) {
                details.formats = VkSurfaceFormatKHR.mallocStack(count.get(0));
                vkGetPhysicalDeviceSurfaceFormatsKHR(device, surface, count, details.formats);
            }

            vkGetPhysicalDeviceSurfacePresentModesKHR(device,surface, count, null);

            if(count.get(0) != 0) {
                details.presentModes = stack.mallocInt(count.get(0));
                vkGetPhysicalDeviceSurfacePresentModesKHR(device, surface, count, details.presentModes);
            }
        }
        else {
            details.capabilities = VkSurfaceCapabilitiesKHR.malloc();
            vkGetPhysicalDeviceSurfaceCapabilitiesKHR(device, surface, details.capabilities);

            IntBuffer count = MemoryStack.stackInts(0);

            vkGetPhysicalDeviceSurfaceFormatsKHR(device, surface, count, null);

            if(count.get(0) != 0) {
                details.formats = VkSurfaceFormatKHR.malloc(count.get(0));
                vkGetPhysicalDeviceSurfaceFormatsKHR(device, surface, count, details.formats);
            }

            vkGetPhysicalDeviceSurfacePresentModesKHR(device,surface, count, null);

            if(count.get(0) != 0) {
                details.presentModes = MemoryUtil.memAllocInt(count.get(0));
                vkGetPhysicalDeviceSurfacePresentModesKHR(device, surface, count, details.presentModes);
            }
        }


        return details;
    }

    public static QueueFamilyIndices findQueueFamilies(VkPhysicalDevice device) {

        QueueFamilyIndices indices = new QueueFamilyIndices();

        try(MemoryStack stack = stackPush()) {

            IntBuffer queueFamilyCount = stack.ints(0);

            vkGetPhysicalDeviceQueueFamilyProperties(device, queueFamilyCount, null);

            VkQueueFamilyProperties.Buffer queueFamilies = VkQueueFamilyProperties.mallocStack(queueFamilyCount.get(0), stack);

            vkGetPhysicalDeviceQueueFamilyProperties(device, queueFamilyCount, queueFamilies);

            IntBuffer presentSupport = stack.ints(VK_FALSE);

            for(int i = 0;i < queueFamilies.capacity() || !indices.isComplete();i++) {

                if((queueFamilies.get(i).queueFlags() & VK_QUEUE_GRAPHICS_BIT) != 0) {
                    indices.graphicsFamily = i;
                }

                vkGetPhysicalDeviceSurfaceSupportKHR(device, i, surface, presentSupport);

                if(presentSupport.get(0) == VK_TRUE) {
                    indices.presentFamily = i;
                }

                if(indices.isComplete()) break;
            }

            return indices;
        }
    }

    private static boolean checkValidationLayerSupport() {

        try(MemoryStack stack = stackPush()) {

            IntBuffer layerCount = stack.ints(0);

            vkEnumerateInstanceLayerProperties(layerCount, null);

            VkLayerProperties.Buffer availableLayers = VkLayerProperties.mallocStack(layerCount.get(0), stack);

            vkEnumerateInstanceLayerProperties(layerCount, availableLayers);

            Set<String> availableLayerNames = availableLayers.stream()
                    .map(VkLayerProperties::layerNameString)
                    .collect(toSet());

            return availableLayerNames.containsAll(VALIDATION_LAYERS);
        }
    }

    /**
     * Switches the swap chain's present mode between FIFO and the tear free modes.
     *
     * <p>The flag is recorded before the guard so a call made before the renderer exists is not
     * lost: Minecraft sets vsync from initializeGame, well before the device is created, and the
     * swap chain is built from this value when it is. Only the recreation is deferred.
     *
     * <p>Touching Drawer at that point is what has to be avoided. Its class initialiser builds a
     * PipelineState, which initialises Pipeline, whose static fields cache Vulkan.getDevice() and
     * create a pipeline cache -- and the device is still null that early, so the class initialiser
     * failed and took the game down with it.
     */
    public static void setVsync(boolean b) {
        if (vsync == b) return;
        vsync = b;
        if (device != null) Drawer.shouldRecreate = true;
    }

    public static VkQueue getPresentQueue() { return presentQueue; }

    public static VkQueue getGraphicsQueue() { return graphicsQueue; }

    public static long getSwapChain() { return swapChain; }

    public static VkExtent2D getSwapchainExtent()
    {
        return swapChainExtent;
    }

    public static List<Long> getSwapChainImages() { return swapChainImages; }

    public static long getRenderPass()
    {
        return renderPass;
    }

    public static List<Long> getSwapChainFramebuffers()
    {
        return swapChainFramebuffers;
    }

    public static long getCommandPool()
    {
        return commandPool;
    }

    public static StagingBuffer getStagingBuffer(int i) { return stagingBuffers[i]; }

    public static DeviceInfo getDeviceInfo() { return deviceInfo; }
}

