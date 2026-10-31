package org.lwjgl.opengl

import net.vulkanmod.util.AndroidUtil
import org.lwjgl.BufferUtils
import org.lwjgl.PointerBuffer
import org.lwjgl.egl.EGL
import org.lwjgl.egl.EGL10
import org.lwjgl.egl.EGL14
import org.lwjgl.glfw.GLFW
import org.lwjgl.glfw.GLFWErrorCallback
import org.lwjgl.glfw.GLFWVulkan
import org.lwjgl.glfw.GLFWImage
import org.lwjgl.glfw.GLFWWindowSizeCallback
import org.lwjgl.input.Keyboard
import org.lwjgl.input.Mouse
import org.lwjgl.system.MemoryStack
import org.lwjgl.system.MemoryUtil
import org.lwjgl.vulkan.VK
import org.lwjgl.vulkan.VK10
import org.lwjgl.vulkan.VkApplicationInfo
import org.lwjgl.vulkan.VkInstance
import org.lwjgl.vulkan.VkInstanceCreateInfo
import java.io.File
import java.nio.Buffer
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.sqrt


object Display {
    @JvmStatic
    var title: String = ""
        set(value) {
            field = value
            if (isCreated) {
                GLFW.glfwSetWindowTitle(handle, value)
            }
        }
    @JvmStatic
    var handle: Long = -1
    private var resizable = false

    // When true, creates main window with GLFW_NO_API for Vulkan surface creation.
    // A hidden GL window is created for MC's GL calls that still need a context.
    @JvmStatic
    var vulkanMode = false
    private var dummyGLWindow: Long = 0

    // Android has no second GLFW window that could host a hidden OpenGL
    // context.  Vulkan owns the ANativeWindow returned by Pojav, so the GL
    // calls Minecraft still makes are serviced by a private EGL pbuffer.
    private var androidEglDisplay: Long = 0
    private var androidEglSurface: Long = 0
    private var androidEglContext: Long = 0

    @JvmStatic
    var displayMode: DisplayMode = DisplayMode(640, 480, 24, 60)

    @JvmStatic
    var width = 0

    @JvmStatic
    var height = 0

    @JvmStatic
    var xPos = 0

    @JvmStatic
    var yPos = 0

    @JvmStatic
    val desktopDisplayMode: DisplayMode?
     get() { return availableDisplayModes.maxByOrNull { it.width * it.height } }
    private var window_resized = false
    private var sizeCallback: GLFWWindowSizeCallback? = null

    private var cached_icons: Array<ByteBuffer>? = null
    @JvmStatic
    fun setIcon(icons: Array<ByteBuffer>): Int {
        // LWJGL2 doesn't enforce this to be called after window creation,
        // meaning you have to keep hold the icons to use them when the window is created
        if (!this.cached_icons.contentEquals(icons)) {
            // you have to also clone the byte buffers to avoid seg faults from them being freed
            cached_icons = icons.map { cloneByteBuffer(it) }.toTypedArray()
        }

        if (this.isCreated) {
            GLFW.glfwSetWindowIcon(this.handle, iconsToGLFWBuffer(this.cached_icons!!))
            return 1
        } else {
            return 0
        }
    }


    private fun cloneByteBuffer(original: ByteBuffer): ByteBuffer {
        // code taken from LWJGL2' Display.java
        val clone = BufferUtils.createByteBuffer(original.capacity())
        val old_position = original.position()
        clone.put(original)
        // if compiled with java 11+ calls ByteBuffer.position/ByteBuffer.flip however java <= 8 doesnt have these
        // methods and instead compiles as calls to Buffer.position/Buffer.flip causing crashes on those java versions
        (original as Buffer).position(old_position)
        (clone as Buffer).flip()

        return clone
    }
    private fun iconsToGLFWBuffer(icons: Array<ByteBuffer>): GLFWImage.Buffer {
        val buffer = GLFWImage.create(icons.size)
        icons.forEach { icon ->
            val size: Int = icon.limit() / 4
            val dimension = sqrt(size.toDouble()).toInt()
            GLFWImage.malloc().use { image ->
                buffer.put(image.set(dimension, dimension, icon))
            }
        }
        buffer.flip()
        return buffer
    }

    @JvmStatic
    fun update() {
        window_resized = false
        GLFW.glfwPollEvents()
        if ( Mouse.isCreated() ) {
            Mouse.poll()
//            Mouse.updateCursor();
        }

        if ( Keyboard.isCreated() ) {
            Keyboard.poll()
        }
        // In Vulkan mode, presentation is handled by Vulkan's swap chain.
        // glfwSwapBuffers only works with OpenGL contexts.
        if (!vulkanMode) {
            GLFW.glfwSwapBuffers(handle)
        }
    }

    @JvmStatic
    fun create(pixelFormat: PixelFormat?) {
        val android = AndroidUtil.isAndroid()

        // Setup an error callback. The default implementation
        GLFWErrorCallback.createPrint(System.err).set()

        // GLFW 3.4 (shipped by LWJGL) can choose between Wayland and X11 at
        // runtime. Let the user pick Wayland when their session is Wayland;
        // otherwise GLFW would usually go through XWayland even though the
        // compositor supports the native protocol. The hint is harmless on
        // Windows and macOS, where this block is skipped entirely.  Pojav's
        // GLFW stub is much smaller and does not implement these entry points.
        if (!android && System.getProperty("os.name", "").toLowerCase().contains("linux")) {
            val session = System.getenv("XDG_SESSION_TYPE")
            val useWayland = net.vulkanmod.config.VulkanModConfig.USE_WAYLAND.value
            var platform = GLFW.GLFW_ANY_PLATFORM
            if ("wayland".equals(session, ignoreCase = true) && useWayland
                && GLFW.glfwPlatformSupported(GLFW.GLFW_PLATFORM_WAYLAND)) {
                platform = GLFW.GLFW_PLATFORM_WAYLAND
            } else if ("x11".equals(session, ignoreCase = true)
                && GLFW.glfwPlatformSupported(GLFW.GLFW_PLATFORM_X11)) {
                platform = GLFW.GLFW_PLATFORM_X11
            }
            if (platform != GLFW.GLFW_ANY_PLATFORM) {
                GLFW.glfwInitHint(GLFW.GLFW_PLATFORM, platform)
            }
        }

        check(GLFW.glfwInit()) { "Unable to initialize GLFW" }

        // The main window is normally created with GLFW_NO_API so Vulkan can
        // own its surface.  If the machine has no Vulkan loader/device, keep a
        // real OpenGL context instead: the mod then disables its Vulkan hooks
        // and the vanilla renderer remains usable rather than showing a blank
        // window.  On Android the same check decides between the Vulkan path
        // and Pojav's normal gl4es window.
        if (vulkanMode && !hasVulkanDevice()) {
            net.vulkanmod.Initializer.LOGGER.error("No usable Vulkan device found; falling back to OpenGL")
            vulkanMode = false
        }

        // Configure GLFW.  Pojav's window is the real Android surface; the
        // desktop visibility/resizability hints either do nothing or are
        // handled Java-side, so only the client API hint matters there.
        GLFW.glfwDefaultWindowHints() // optional, the current window hints are already the default
        if (!android) {
            GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE) // the window will stay hidden after creation
            GLFW.glfwWindowHint(GLFW.GLFW_RESIZABLE, if (resizable) GLFW.GLFW_TRUE else GLFW.GLFW_FALSE)
        }

        if (vulkanMode) {
            // No OpenGL context on the main window — required for Vulkan surface creation.
            // In Pojav this also switches the renderer to the native Vulkan path, which
            // makes glfwCreateWindow return the ANativeWindow pointer.
            GLFW.glfwWindowHint(GLFW.GLFW_CLIENT_API, GLFW.GLFW_NO_API)
        }

        handle =
            GLFW.glfwCreateWindow(displayMode.width, displayMode.height, title, MemoryUtil.NULL, MemoryUtil.NULL)

        if (android) {
            // Pojav ignores the requested size and uses the activity surface.
            // Query the size it actually created instead of assuming 640x480.
            val w = BufferUtils.createIntBuffer(1)
            val h = BufferUtils.createIntBuffer(1)
            GLFW.glfwGetFramebufferSize(handle, w, h)
            width = if (w.get(0) > 0) w.get(0) else displayMode.width
            height = if (h.get(0) > 0) h.get(0) else displayMode.height
            displayMode = DisplayMode(width, height, displayMode.bitsPerPixel, displayMode.frequency)
        } else {
            width = displayMode.width
            height = displayMode.height
        }

        if (vulkanMode) {
            if (android) {
                // Vulkan owns the activity window.  Minecraft still makes GL
                // calls (texture readback, matrix queries, HUD fallbacks), so
                // give it a private off-screen EGL context instead of the
                // hidden GLFW window used on desktop.
                createAndroidGLContext()
            } else {
                // Create a hidden 1x1 OpenGL window so MC's GL calls don't crash
                GLFW.glfwDefaultWindowHints()
                GLFW.glfwWindowHint(GLFW.GLFW_VISIBLE, GLFW.GLFW_FALSE)
                dummyGLWindow = GLFW.glfwCreateWindow(1, 1, "VulkanMod GL Compat", MemoryUtil.NULL, MemoryUtil.NULL)
                GLFW.glfwMakeContextCurrent(dummyGLWindow)
            }
        } else {
            GLFW.glfwMakeContextCurrent(handle)
        }

        GL.createCapabilities()
        // create general callbacks
        sizeCallback = GLFWWindowSizeCallback.create(Display::resizeCallback)
        GLFW.glfwSetWindowSizeCallback(handle, sizeCallback)
        Mouse.create()
        Keyboard.create()
        GLFW.glfwShowWindow(handle)
        if (this.cached_icons != null) {
            this.setIcon(this.cached_icons!!)
        }
    }

    /**
     * Creates the EGL pbuffer/context that backs Minecraft's remaining GL
     * calls on Android while Vulkan presents the visible frame.
     *
     * <p>Pojav's GLFW window owns an ANativeWindow, not an EGL context, when
     * {@code GLFW_NO_API} is selected.  Asking GLFW to make that handle
     * current would pass a native-window pointer to Pojav's GL bridge, so the
     * context has to be created through EGL directly.  gl4es is loaded by
     * LWJGL as the OpenGL function provider and translates these calls to
     * the current GLES context.
     */
    private fun createAndroidGLContext() {
        EGL.create(androidEglLibraryPath())

        val stack = MemoryStack.stackPush()
        try {
            val display = EGL10.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            if (display == MemoryUtil.NULL) {
                throw IllegalStateException("eglGetDisplay returned EGL_NO_DISPLAY")
            }
            androidEglDisplay = display

            val major = stack.ints(0)
            val minor = stack.ints(0)
            if (!EGL10.eglInitialize(display, major, minor)) {
                throw IllegalStateException("eglInitialize failed: 0x" + Integer.toHexString(EGL10.eglGetError()))
            }

            val configAttribs = intArrayOf(
                EGL10.EGL_SURFACE_TYPE, EGL10.EGL_PBUFFER_BIT,
                EGL_RENDERABLE_TYPE, EGL_OPENGL_ES2_BIT,
                EGL10.EGL_RED_SIZE, 8,
                EGL10.EGL_GREEN_SIZE, 8,
                EGL10.EGL_BLUE_SIZE, 8,
                EGL10.EGL_ALPHA_SIZE, 8,
                EGL10.EGL_DEPTH_SIZE, 24,
                EGL10.EGL_STENCIL_SIZE, 8,
                EGL10.EGL_NONE
            )
            val configs = stack.mallocPointer(1)
            val configCount = IntArray(1)
            if (!EGL10.eglChooseConfig(display, configAttribs, configs, configCount)
                || configCount[0] == 0) {
                throw IllegalStateException("No EGL pbuffer config for OpenGL ES 2: 0x"
                        + Integer.toHexString(EGL10.eglGetError()))
            }
            val config = configs.get(0)

            val pbufferAttribs = intArrayOf(
                EGL10.EGL_WIDTH, 64,
                EGL10.EGL_HEIGHT, 64,
                EGL10.EGL_NONE
            )
            val surface = EGL10.eglCreatePbufferSurface(display, config, pbufferAttribs)
            if (surface == EGL10.EGL_NO_SURFACE) {
                throw IllegalStateException("eglCreatePbufferSurface failed: 0x"
                        + Integer.toHexString(EGL10.eglGetError()))
            }
            androidEglSurface = surface

            val contextAttribs = intArrayOf(
                EGL_CONTEXT_CLIENT_VERSION, 2,
                EGL10.EGL_NONE
            )
            val context = EGL10.eglCreateContext(display, config, EGL10.EGL_NO_CONTEXT, contextAttribs)
            if (context == EGL10.EGL_NO_CONTEXT) {
                throw IllegalStateException("eglCreateContext failed: 0x"
                        + Integer.toHexString(EGL10.eglGetError()))
            }
            androidEglContext = context

            if (!EGL10.eglMakeCurrent(display, surface, surface, context)) {
                throw IllegalStateException("eglMakeCurrent failed: 0x"
                        + Integer.toHexString(EGL10.eglGetError()))
            }

            initAndroidGl4es()
        } catch (t: Throwable) {
            destroyAndroidGLContext()
            throw t
        } finally {
            stack.close()
        }
    }

    /**
     * Chooses the EGL implementation the same way Pojav/Amethyst do.
     *
     * <p>{@code POJAVEXEC_EGL} selects an alternative EGL (ANGLE, MobileGlues,
     * Mesa, ...) by library name.  Pojav hands that name to {@code dlopen},
     * which resolves it through {@code LD_LIBRARY_PATH}; LWJGL's loader does
     * not, so resolve the name to an absolute path first and only fall back
     * to the plain name when the library cannot be found.
     */
    private fun androidEglLibraryPath(): String {
        val configured = System.getenv("POJAVEXEC_EGL")?.trim()
        if (!configured.isNullOrEmpty()) {
            val direct = File(configured)
            if (direct.isAbsolute) {
                return if (direct.isFile) direct.absolutePath else configured
            }

            val resolved = resolveAndroidLibrary(configured)
            if (resolved != null) return resolved
            return configured
        }

        val systemPath = if (System.getProperty("os.arch", "").contains("64"))
            "/system/lib64/libEGL.so" else "/system/lib/libEGL.so"
        return if (File(systemPath).isFile) systemPath else "libEGL.so"
    }

    /** Resolves a bare library name through the launcher's native directories. */
    private fun resolveAndroidLibrary(fileName: String): String? {
        val candidates = ArrayList<File>()

        val nativeDir = System.getenv("POJAV_NATIVEDIR")
        if (!nativeDir.isNullOrEmpty()) {
            candidates.add(File(nativeDir, fileName))
        }

        val libraryPath = System.getProperty("java.library.path", "")
        for (dir in libraryPath.split(File.pathSeparator)) {
            if (dir.isEmpty()) continue
            candidates.add(File(dir, fileName))
        }

        return candidates.firstOrNull { it.isFile }?.absolutePath
    }

    /**
     * Pojav's GLCapabilities constructor normally calls
     * {@code PojavRendererInit.onCreateCapabilities} to give gl4es a callback
     * for the surface dimensions.  The mod ships the unpatched LWJGL
     * GLCapabilities, so the same initialization has to be requested here.
     * Desktops do not have the class and skip this entirely.
     */
    private fun initAndroidGl4es() {
        try {
            val rendererInit = Class.forName("org.lwjgl.opengl.PojavRendererInit")
            val provider = GL.getFunctionProvider()
            rendererInit.getMethod("onCreateCapabilities", org.lwjgl.system.FunctionProvider::class.java)
                .invoke(null, provider)
        } catch (t: Throwable) {
            net.vulkanmod.Initializer.LOGGER.debug("gl4es renderer initialization skipped", t)
        }
    }

    private fun destroyAndroidGLContext() {
        if (androidEglContext == 0L && androidEglSurface == 0L) {
            return
        }

        try {
            if (androidEglDisplay != 0L) {
                EGL10.eglMakeCurrent(androidEglDisplay, EGL10.EGL_NO_SURFACE,
                    EGL10.EGL_NO_SURFACE, EGL10.EGL_NO_CONTEXT)
                if (androidEglContext != 0L) {
                    EGL10.eglDestroyContext(androidEglDisplay, androidEglContext)
                }
                if (androidEglSurface != 0L) {
                    EGL10.eglDestroySurface(androidEglDisplay, androidEglSurface)
                }
                EGL10.eglTerminate(androidEglDisplay)
            }
        } catch (t: Throwable) {
            t.printStackTrace()
        } finally {
            androidEglContext = 0
            androidEglSurface = 0
            androidEglDisplay = 0
        }
    }

    // Values from EGL 1.4.  LWJGL's EGL bindings expose these under
    // different versions, so keeping the numeric values here avoids a
    // version-sensitive lookup on Android.
    private const val EGL_RENDERABLE_TYPE = 0x3040
    private const val EGL_OPENGL_ES2_BIT = 0x0004
    private const val EGL_CONTEXT_CLIENT_VERSION = 0x3098

    /**
     * Checks for a real Vulkan device, not just for a Vulkan loader.
     *
     * <p>GLFW's own check only verifies that the loader is installed; a
     * machine with the loader but no working GPU driver would still pass it.
     * The window would then be created without an OpenGL context and the game
     * would have nothing to render with once device creation failed. Creating
     * a throwaway instance first makes it possible to fall back cleanly.
     */
    private fun hasVulkanDevice(): Boolean {
        // Referencing VK initializes LWJGL's loader. On macOS this is what
        // loads the bundled MoltenVK implementation.
        try {
            VK.getFunctionProvider()
        } catch (t: Throwable) {
            net.vulkanmod.Initializer.LOGGER.error("Vulkan loader is not available", t)
            return false
        }

        // Pojav replaces GLFW's Vulkan helpers and its GLFWVulkan class
        // cannot see the mod's Vulkan bindings (the parent class loader
        // defines it).  The loader check above and the instance probe below
        // are sufficient on Android.
        if (!AndroidUtil.isAndroid() && !desktopGlfwVulkanSupported()) return false

        val stack = MemoryStack.stackPush()
        try {
            val appInfo = VkApplicationInfo.callocStack(stack)
                .sType(VK10.VK_STRUCTURE_TYPE_APPLICATION_INFO)
                .pApplicationName(stack.UTF8("VulkanMod"))
                .applicationVersion(VK10.VK_MAKE_VERSION(1, 0, 0))
                .pEngineName(stack.UTF8("VulkanMod"))
                .engineVersion(VK10.VK_MAKE_VERSION(1, 0, 0))
                .apiVersion(VK10.VK_API_VERSION_1_0)

            val createInfo = VkInstanceCreateInfo.callocStack(stack)
                .sType(VK10.VK_STRUCTURE_TYPE_INSTANCE_CREATE_INFO)
                .pApplicationInfo(appInfo)

            val pInstance = stack.mallocPointer(1)
            if (VK10.vkCreateInstance(createInfo, null, pInstance) != VK10.VK_SUCCESS) {
                return false
            }

            val instance = VkInstance(pInstance[0], createInfo)
            try {
                val count = stack.mallocInt(1)
                VK10.vkEnumeratePhysicalDevices(instance, count, null)
                return count[0] > 0
            } finally {
                VK10.vkDestroyInstance(instance, null)
            }
        } catch (t: Throwable) {
            net.vulkanmod.Initializer.LOGGER.warn("Vulkan device pre-check failed", t)
            return false
        } finally {
            stack.close()
        }
    }

    /**
     * Isolated so Pojav's GLFWVulkan stub is only resolved on desktop.  The
     * stub references Vulkan classes that are not on the launcher's classpath.
     */
    private fun desktopGlfwVulkanSupported(): Boolean = GLFWVulkan.glfwVulkanSupported()

    /**
     * This is a shit code snippet, but at least it works.
     */
    @JvmStatic
    fun setFullscreen(fullscreen: Boolean) {
        println("setFullscreen: $fullscreen")
        runCatching {
            this.resizeCallback(handle, displayMode.width, displayMode.height)
            if (fullscreen) {
                var monitor = GLFW.glfwGetPrimaryMonitor()

                GLFW.glfwSetWindowMonitor(
                    handle,
                    monitor,
                    0,
                    0,
                    width,
                    height,
                    displayMode.frequency
                )
                xPos = displayMode.width / 2
                yPos = displayMode.height / 2
            } else {
                xPos -= width / 2
                yPos -= height / 2
                GLFW.glfwSetWindowMonitor(
                    handle,
                    0L,
                    xPos,// need a xPos
                    yPos,// need a yPos
                    width,
                    height,
                    -1
                )
            }
            GLFW.glfwSetWindowSize(handle, width, height)
        }.onFailure {
            it.printStackTrace()
        }
    }
    @JvmStatic
    val availableDisplayModes: Array<DisplayMode>
        get() {
            val primaryMonitor = GLFW.glfwGetPrimaryMonitor()
            if (primaryMonitor == MemoryUtil.NULL) {
                return arrayOf()
            }
            val videoModes = GLFW.glfwGetVideoModes(primaryMonitor) ?: error("No video modes found")
            return videoModes.map { mode ->
                DisplayMode(
                    mode.width(),
                    mode.height(),
                    mode.redBits() + mode.blueBits() + mode.greenBits(),
                    mode.refreshRate()
                )
            }.toHashSet().toTypedArray()
        }

    private fun resizeCallback(window: Long, width: Int, height: Int) {
        if (window == handle) {
            window_resized = true
            Display.width = width
            Display.height = height
        }
    }

    fun destroyWindow() {
        // free callbacks
        sizeCallback?.free()
        Mouse.destroy()
        Keyboard.destroy()
        // Destroy the private Android GL context before the native window.
        destroyAndroidGLContext()
        // Destroy the window
        GLFW.glfwDestroyWindow(handle)
        if (vulkanMode && dummyGLWindow != 0L) {
            GLFW.glfwDestroyWindow(dummyGLWindow)
            dummyGLWindow = 0
        }
    }

    @JvmStatic
    fun destroy() {
        destroyWindow()
        // Terminate GLFW and free the error callback
        GLFW.glfwTerminate()
        GLFW.glfwSetErrorCallback(null)?.free()
    }

    @JvmStatic
    val isCreated: Boolean
        get() = handle != -1L

    @JvmStatic
    val isCloseRequested: Boolean
        get() = GLFW.glfwWindowShouldClose(handle)

    @JvmStatic
    val isActive: Boolean
        get() = true

    /**
     * Resizes the window directly.
     *
     * LWJGL2's setDisplayMode is not implemented by this shim, and the compile classpath still
     * carries the real LWJGL2 jar -- so a call to it compiles and then fails at runtime with
     * NoSuchMethodError, because this object is the class that actually gets loaded. This is the
     * supported way to ask for a size, used by the profiling harness to make runs comparable.
     */
    @JvmStatic
    fun setWindowSize(width: Int, height: Int) {
        if (!isCreated) return
        GLFW.glfwSetWindowSize(handle, width, height)
    }

    @JvmStatic
    fun setResizable(isResizable: Boolean) {
        resizable = isResizable
        if (isCreated) {
            GLFW.glfwWindowHint(GLFW.GLFW_RESIZABLE, if (resizable) GLFW.GLFW_TRUE else GLFW.GLFW_FALSE)
        }
    }

    /**
     * The top of the game's own FPS slider, where it is labelled "Unlimited".
     *
     * The game itself agrees this value means no limit -- isFramerateValid() returns false at it,
     * which is what draws the "Unlimited" caption. It still hands the number to Display.sync, so
     * LWJGL2 dutifully holds the frame rate at exactly 260 anyway. That was a hard ceiling on every
     * scene light enough to run faster, and Sync burns the client thread in sleep and yield loops
     * to enforce it, so the cap cost CPU as well as frames.
     */
    private const val UNLIMITED_FPS = 260

    @JvmStatic
    fun sync(fps: Int) {
        if (fps >= UNLIMITED_FPS) return
        Sync.sync(fps)
    }

    @JvmStatic
    fun setVSyncEnabled(enabled: Boolean) {
        if (vulkanMode) {
            // glfwSwapInterval only affects an OpenGL context. In Vulkan the equivalent is the
            // swapchain present mode, so the setting has to be handed to the renderer -- without
            // this the game's vsync option did nothing at all and presentation always ran in
            // whichever mode the swapchain happened to pick.
            net.vulkanmod.vulkan.Vulkan.setVsync(enabled)
        } else {
            GLFW.glfwSwapInterval(if (enabled) 1 else 0)
        }
    }

    @JvmStatic
    fun wasResized(): Boolean = window_resized
}