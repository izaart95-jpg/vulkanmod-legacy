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
}
