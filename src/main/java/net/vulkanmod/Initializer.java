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

            // Pojav ships its own LWJGL in the launcher class loader and its
            // GL11/GL11C wrappers are JNI methods. Java resolves JNI symbols
            // per defining class loader, so that loader needs the matching
            // liblwjgl_opengl.so too.
            initAndroidParentGL();
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

    /**
     * Initializes the OpenGL bindings that Pojav/Amethyst keep in the
     * launcher (parent) class loader.
     *
     * <p>Those launchers replace the generated LWJGL OpenGL wrapper classes
     * with JNI methods. JNI lookup is scoped to the class loader that defined
     * the class, so loading lwjgl_opengl through this mod's class loader (as
     * GL's own static initializer does) is not enough: the launcher loader
     * must associate the library with itself as well. Calling the launcher's
     * copy of {@code Library.loadSystem} is important because the
     * {@code System.load}/{@code System.loadLibrary} method references then
     * belong to the launcher's defining loader.
     */
    private static void initAndroidParentGL() {
        try {
            String libraryPath = prepareAndroidParentGLLibrary();
            if (libraryPath == null) {
                LOGGER.error("Android: could not prepare liblwjgl_opengl.so for the launcher class loader");
                return;
            }

            Class<?> libraryClass = Class.forName("org.lwjgl.system.Library");
            ClassLoader launcherLoader = libraryClass.getClassLoader();
            if (launcherLoader == null) {
                launcherLoader = ClassLoader.getSystemClassLoader();
            }

            java.lang.reflect.Method loadSystem =
                    libraryClass.getMethod("loadSystem", String.class, String.class);

            loadSystem.invoke(null, "org.lwjgl.opengl", libraryPath);
            LOGGER.info("Android: loaded launcher liblwjgl_opengl.so from " + libraryPath);

            // Initialize the launcher's GL class after its native library is
            // associated with the loader. On desktop this would be the mod's
            // own GL class, which is already initialized by Display.
            Class<?> glClass = Class.forName("org.lwjgl.opengl.GL", true, launcherLoader);
            LOGGER.info("Android: launcher GL bindings initialized by "
                    + glClass.getClassLoader());
        } catch (Throwable t) {
            // Do not abort startup: if the launcher already initialized its
            // GL classes this call is redundant. If it did not, the first
            // native GL call will produce a clearer stack trace.
            LOGGER.error("Android: could not initialize the launcher's OpenGL bindings", t);
        }
    }

    /**
     * Copies liblwjgl_opengl.so to a private file that the launcher class
     * loader can load.
     *
     * <p>Both class loaders must be able to use the library but the JVM
     * refuses to load the same canonical file into two loaders ("already
     * loaded in another classloader"). The mod class loader gets the copy
     * bundled in this jar through LWJGL's normal extraction, so the launcher
     * gets a separate copy here. If the launcher ships the library, its own
     * build is copied (it matches the launcher's Java classes); otherwise the
     * bundled one is used as a fallback.
     */
    private static String prepareAndroidParentGLLibrary() {
        String source = findAndroidNativeLibrary("liblwjgl_opengl.so");
        java.io.InputStream bundled = null;

        if (source == null) {
            bundled = Initializer.class.getResourceAsStream(
                    androidBundledNativePath("org/lwjgl/opengl/liblwjgl_opengl.so"));
            if (bundled == null) {
                return null;
            }
        }

        java.io.File directory = new java.io.File(
                System.getProperty("java.io.tmpdir", "."), "vulkanmod");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            closeQuietly(bundled);
            return null;
        }

        // A unique name avoids overwriting a library that is still mapped by
        // a previous run of the game and keeps the two class loaders' files
        // on different canonical paths.
        java.io.File target = new java.io.File(directory,
                "liblwjgl_opengl_parent_" + Long.toHexString(System.nanoTime()) + ".so");

        java.io.InputStream input = bundled;
        try {
            if (input == null) {
                input = new java.io.FileInputStream(source);
            }
            try (java.io.InputStream in = input;
                 java.io.OutputStream out = new java.io.FileOutputStream(target)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                }
            }
            target.setReadable(true, false);
            target.setExecutable(true, false);
            return target.getAbsolutePath();
        } catch (Throwable t) {
            LOGGER.error("Android: failed to copy liblwjgl_opengl.so", t);
            closeQuietly(bundled);
            return null;
        }
    }

    private static String androidBundledNativePath(String relativePath) {
        String osArch = System.getProperty("os.arch", "");
        boolean is64Bit = osArch.contains("64") || osArch.startsWith("armv8");
        String arch = osArch.startsWith("arm") || osArch.startsWith("aarch64")
                ? (is64Bit ? "arm64" : "arm32")
                : (is64Bit ? "x64" : "x86");
        return "/linux/" + arch + "/" + relativePath;
    }

    private static void closeQuietly(java.io.Closeable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (Throwable ignored) {
        }
    }

    /**
     * Finds a launcher-provided native library. Both Pojav variants put
     * their Android libraries either in the application's native library
     * directory or on java.library.path.
     */
    private static String findAndroidNativeLibrary(String fileName) {
        String nativeDir = System.getenv("POJAV_NATIVEDIR");
        String found = findInDirectory(nativeDir, fileName);
        if (found != null) return found;

        String libraryPath = System.getProperty("java.library.path", "");
        for (String dir : libraryPath.split(java.io.File.pathSeparator)) {
            found = findInDirectory(dir, fileName);
            if (found != null) return found;
        }
        return null;
    }

    private static String findInDirectory(String directory, String fileName) {
        if (directory == null || directory.isEmpty()) return null;
        java.io.File file = new java.io.File(directory, fileName);
        return file.isFile() ? file.getAbsolutePath() : null;
    }
}
