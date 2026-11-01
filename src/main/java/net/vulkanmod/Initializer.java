package net.vulkanmod;

import net.fabricmc.api.ClientModInitializer;
import net.vulkanmod.util.AndroidUtil;
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

        if (AndroidUtil.isAndroid()) {
            // Android exposes the EGL driver as libEGL.so, not libEGL.so.1.
            // Load it explicitly from Display.create() instead of letting the
            // EGL class initializer fail while looking for the desktop name.
            System.setProperty("org.lwjgl.egl.explicitInit", "true");

            if (AndroidUtil.isMJLauncher()) {
                // MJLauncher has no LWJGL3 in its parent class loader; the mod
                // bundles the complete LWJGL core and lets MJ's dlopen hook
                // redirect libvulkan.so to the driver it selected.
                LOGGER.info("MJLauncher detected: using the bundled LWJGL3 flavour");
            } else {
                // Pojav's 1.8.9 classpath contains LWJGL 2, whose
                // org.lwjgl.PointerBuffer shadows LWJGL 3's class.  The Android
                // jar therefore bundles a complete, consistent LWJGL 3 core and
                // OpenGL API; loading the launcher's GL here would verify GL
                // against LWJGL 2 and fail with a VerifyError.
                LOGGER.info("Pojav/Amethyst detected: using the bundled LWJGL3 core and OpenGL API");
            }
        }

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

        // Pojav's GLFWVulkan lives in the parent class loader and references
        // Vulkan classes that are not on Pojav's own classpath.  Display has
        // already probed the loader and enumerated a physical device before
        // the window was created, so use that result instead.
        if (!AndroidUtil.isAndroid()) {
            try {
                if (!desktopVulkanSupported()) {
                    LOGGER.error("Vulkan is NOT supported on this system!");
                    LOGGER.error("Make sure vulkan-icd-loader and your GPU's Vulkan driver (e.g. vulkan-radeon) are installed.");
                    return;
                }
            } catch (Exception e) {
                LOGGER.error("Failed to check Vulkan support", e);
                return;
            }
        } else {
            LOGGER.info("Android: using the Vulkan device validated by Display.create()");
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

    /**
     * Kept separate from the caller so that Android never resolves Pojav's
     * GLFWVulkan class: that stub references Vulkan bindings which are not on
     * the launcher's own classpath.
     */
    private static boolean desktopVulkanSupported() {
        return GLFWVulkan.glfwVulkanSupported();
    }
}
