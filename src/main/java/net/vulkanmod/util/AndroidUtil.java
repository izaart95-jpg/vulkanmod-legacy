package net.vulkanmod.util;

/**
 * Runtime detection for Android launchers that run a desktop JVM on top of
 * Android (PojavLauncher / Amethyst).
 *
 * <p>The mod jar is shared between desktop and Android, so every branch that
 * touches windowing or native loading has to know which environment it is in.
 * Pojav sets {@code POJAV_NATIVEDIR} and {@code POJAV_RENDERER} in the game
 * process; the remaining checks cover launchers that do not set those
 * variables but still put the JVM on Android storage.
 */
public final class AndroidUtil {
    private static Boolean android;
    private static Boolean mjLauncher;

    private AndroidUtil() {}

    public static boolean isAndroid() {
        Boolean cached = android;
        if (cached != null) return cached;

        boolean detected = false;
        try {
            String nativeDir = System.getenv("POJAV_NATIVEDIR");
            String renderer = System.getenv("POJAV_RENDERER");
            String libPath = System.getProperty("java.library.path", "");
            String runtime = (System.getProperty("java.vm.name", "") + " "
                    + System.getProperty("java.runtime.name", "") + " "
                    + System.getProperty("java.vendor", "")).toLowerCase();

            detected = (nativeDir != null && !nativeDir.isEmpty())
                    || (renderer != null && !renderer.isEmpty())
                    || libPath.contains("/data/data/")
                    || libPath.contains("/data/app/")
                    || libPath.contains("/data/user/")
                    || runtime.contains("dalvik");
        } catch (Throwable ignored) {
            // Environment access can be restricted on exotic JVMs; treat it as desktop.
        }

        android = detected;
        return detected;
    }

    /**
     * Detects MJLauncher (MojoLauncher), which runs 1.8.9 on LWJGL2 and does
     * not expose LWJGL3 or the {@code org.lwjgl.glfw} Java classes at all.
     *
     * <p>Unlike Pojav it supplies a complete GLFW 3.4 native library and
     * hooks LWJGL's {@code DynamicLinkLoader} to route OpenGL and Vulkan to
     * the selected renderer/driver handles.  The mod therefore has to bring
     * its own LWJGL3 classes and use the launcher's native GLFW.
     *
     * <p>Do not probe this by loading {@code git.artdeell.dnbootstrap.glfw.GLFW}:
     * that class registers the launcher's JNI callbacks in a static
     * initializer, and loading a second copy would redirect them to the mod.
     */
    public static boolean isMJLauncher() {
        Boolean cached = mjLauncher;
        if (cached != null) return cached;

        boolean detected = false;
        if (isAndroid()) {
            try {
                String marker = nullToEmpty(System.getenv("MOD_ANDROID_RUNTIME")) + '\n'
                        + nullToEmpty(System.getProperty("java.class.path", "")) + '\n'
                        + nullToEmpty(System.getProperty("java.library.path", "")) + '\n'
                        + nullToEmpty(System.getProperty("user.dir", ""));
                detected = marker.contains("mjlaunch") || marker.contains("git.artdeell");
            } catch (Throwable ignored) {
                // Environment access can be restricted on exotic JVMs.
            }
        }

        mjLauncher = detected;
        return detected;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }
}
