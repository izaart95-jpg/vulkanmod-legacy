package net.vulkanmod.config;

import net.vulkanmod.Initializer;
import org.lwjgl.BufferUtils;
import org.lwjgl.glfw.GLFW;
import org.lwjgl.glfw.GLFWVidMode;
import org.lwjgl.opengl.Display;

import java.nio.IntBuffer;

/**
 * Applies the Windowed Fullscreen option through GLFW.
 *
 * <p>1.8.9 only has Display.setFullscreen(), which always changes the monitor
 * mode (exclusive fullscreen). The option here is the other kind: the normal
 * window is undecorated and stretched over the desktop, which is friendlier to
 * alt-tabbing and works on X11, Wayland, Windows and macOS alike.
 *
 * <p>The change is applied on the client tick rather than from the settings
 * screen so that a click cannot resize the window while the GUI is still being
 * drawn. The previous bounds are captured first and restored when the option is
 * switched off. Pressing F11 remains an override and is not fought every tick.
 */
public final class WindowModeController {

    private static final IntBuffer X = BufferUtils.createIntBuffer(1);
    private static final IntBuffer Y = BufferUtils.createIntBuffer(1);
    private static final IntBuffer W = BufferUtils.createIntBuffer(1);
    private static final IntBuffer H = BufferUtils.createIntBuffer(1);

    private static boolean initialized;
    private static boolean lastWanted;
    private static int windowedX;
    private static int windowedY;
    private static int windowedWidth;
    private static int windowedHeight;

    private WindowModeController() {}

    /** Called once per client tick. Cheap when the option has not changed. */
    public static void tick() {
        // Android is always fullscreen and Pojav's GLFW stub has no monitors
        // or window decorations to toggle.
        if (net.vulkanmod.util.AndroidUtil.isAndroid()) return;

        boolean wanted = VulkanModConfig.WINDOWED_FULLSCREEN.value;

        if (!initialized) {
            initialized = true;
            lastWanted = wanted;
            if (wanted) apply(true);
            return;
        }

        if (wanted == lastWanted) return;
        lastWanted = wanted;
        apply(wanted);
    }

    private static void apply(boolean borderless) {
        long handle = Display.getHandle();
        if (handle == 0L) return;

        try {
            if (borderless) {
                X.clear(); Y.clear(); W.clear(); H.clear();
                GLFW.glfwGetWindowPos(handle, X, Y);
                GLFW.glfwGetWindowSize(handle, W, H);
                windowedX = X.get(0);
                windowedY = Y.get(0);
                windowedWidth = W.get(0);
                windowedHeight = H.get(0);

                long monitor = GLFW.glfwGetPrimaryMonitor();
                GLFWVidMode mode = monitor == 0L ? null : GLFW.glfwGetVideoMode(monitor);
                if (mode == null) return;

                GLFW.glfwSetWindowAttrib(handle, GLFW.GLFW_DECORATED, GLFW.GLFW_FALSE);
                GLFW.glfwSetWindowMonitor(handle, 0L, 0, 0, mode.width(), mode.height(), GLFW.GLFW_DONT_CARE);
            } else {
                GLFW.glfwSetWindowMonitor(handle, 0L, windowedX, windowedY,
                                          windowedWidth, windowedHeight, GLFW.GLFW_DONT_CARE);
                GLFW.glfwSetWindowAttrib(handle, GLFW.GLFW_DECORATED, GLFW.GLFW_TRUE);
            }
        } catch (Throwable t) {
            // A window manager may reject a mode change; the game must keep running.
            Initializer.LOGGER.warn("[Window] could not apply windowed fullscreen", t);
        }
    }
}
