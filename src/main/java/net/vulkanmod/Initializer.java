package net.vulkanmod;

import net.fabricmc.api.ClientModInitializer;
import net.vulkanmod.vulkan.VRenderSystem;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.glfw.GLFWVulkan;
import org.lwjgl.opengl.Display;

public class Initializer implements ClientModInitializer {
    public static final Logger LOGGER = LogManager.getLogger("VulkanMod");

    // Tracks whether Vulkan has been fully initialized and is ready for rendering
    public static volatile boolean vulkanReady = false;
    public static boolean insideFBOBlit = false;

    @Override
    public void onInitializeClient() {
        LOGGER.info("== VulkanMod 1.8.9 ==");

        net.vulkanmod.config.VulkanModConfig.load();

        // Set vulkanMode BEFORE Display.create() is called (which happens in initializeGame).
        // This makes the main window use GLFW_NO_API so Vulkan surface creation works.
        Display.setVulkanMode(true);
        LOGGER.info("VulkanMod loaded. Display set to Vulkan mode.");
    }

    /**
     * Called from MinecraftClientMixin after initializeGame completes
     * (GLFW and the window are ready at that point).
     */
    public static void initVulkanRenderer(long windowHandle) {
        LOGGER.info("Checking Vulkan support...");

        try {
            if (!GLFWVulkan.glfwVulkanSupported()) {
                LOGGER.error("Vulkan is NOT supported on this system!");
                LOGGER.error("Make sure vulkan-icd-loader and your GPU's Vulkan driver (e.g. vulkan-radeon) are installed.");
                return;
            }
        } catch (Exception e) {
            LOGGER.error("Failed to check Vulkan support", e);
            return;
        }

        LOGGER.info("Vulkan is supported! Initializing renderer with window handle: " + windowHandle);

        try {
            VRenderSystem.setWindow(windowHandle);
            VRenderSystem.initRenderer();
            vulkanReady = true;
            LOGGER.info("Vulkan renderer initialized successfully!");

            // Announce the diagnostic flags. Without this, "no divergence was reported" and
            // "the flag never reached the JVM" look identical in a log.
            if (Boolean.getBoolean("vulkanmod.matrixdebug")) {
                LOGGER.warn("[VRenderSystem] matrixdebug ACTIVE -- comparing the software matrix "
                        + "stack against GL on every draw. Expect low FPS; this is a diagnostic mode.");
            }
            if (Boolean.getBoolean("vulkanmod.softwarematrix")) {
                LOGGER.warn("[VRenderSystem] softwarematrix ACTIVE -- trusting the software matrix "
                        + "stack instead of reading GL back per draw.");
            }
        } catch (Exception e) {
            LOGGER.error("Failed to initialize Vulkan renderer", e);
            vulkanReady = false;
        }
    }
}
