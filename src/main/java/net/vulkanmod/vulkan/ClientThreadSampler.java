package net.vulkanmod.vulkan;

import net.vulkanmod.Initializer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A sampling profiler for the client thread.
 *
 * <p>The frame timers in {@link PerformanceProfiler} only see the regions someone thought to wrap,
 * so they can say how much time is unaccounted for but never where it went. Measurement showed the
 * Vulkan backend costing well under a millisecond of an eight millisecond frame, which means the
 * answer is somewhere nobody had put a timer -- and adding timers by guesswork is how the last few
 * wrong conclusions happened.
 *
 * <p>This takes the client thread's stack at a fixed interval and counts what it finds. Sampling
 * needs no prior belief about where the time is, which is exactly the property that was missing.
 *
 * <p>Enabled by -Dvulkanmod.profiler.sampler=true. Off by default: capturing another thread's
 * stack brings it to a safepoint, which perturbs the very thing being measured.
 */
public final class ClientThreadSampler {

    private ClientThreadSampler() {}

    public static final boolean ENABLED =
            PerformanceProfiler.ENABLED && Boolean.getBoolean("vulkanmod.profiler.sampler");

    /** Frequent enough to resolve a millisecond of a frame, rare enough to stay under a few
     *  percent of overhead at the frame rates this is used at. */
    private static final long INTERVAL_MS = 3;

    /** How deep a sample is attributed. The top frame alone is usually a JDK or driver call. */
    private static final int DEPTH = 4;

    private static final Map<String, int[]> counts = new HashMap<>();

    /**
     * The same samples grouped by which part of the frame they belong to.
     *
     * <p>The per-stack list only ever shows its own top entries, and a long tail of small distinct
     * stacks can hide more time than everything named above it. Attributing every sample to one
     * phase is what makes the report add up to 100%, which is the only way to know nothing is
     * being missed.
     */
    private static final Map<String, int[]> phases = new HashMap<>();

    /** Matched against the stack from the bottom up; the first hit names the phase. */
    private static final String[][] PHASES = {
            {"WorldRenderer.renderClouds", "clouds"},
            {"GameRenderer.renderClouds", "clouds"},
            {"WorldRenderer.renderLayer", "terrain"},
            {"WorldRenderer.updateChunks", "chunk upload"},
            {"WorldRenderer.setupTerrain", "terrain culling"},
            {"WorldRenderer.renderEntities", "entities"},
            {"WorldRenderer.renderSky", "sky"},
            {"GameRenderer.renderWorld", "world (other)"},
            {"InGameHud.render", "hud"},
            {"Screen.render", "screen"},
            {"MinecraftClient.tick", "client tick"},
            {"Drawer.drawFrame", "submit + present"},
            {"Drawer.initiateRenderPass", "frame setup"},
            {"MinecraftClient.updateDisplay", "display update"},
            {"Sync.sync", "fps limiter"},
            {"MinecraftClient.runGameLoop", "game loop (other)"},
    };
    private static volatile Thread target;
    private static volatile boolean running;
    private static int totalSamples;

    public static synchronized void start(Thread clientThread) {
        if (!ENABLED || running) return;
        target = clientThread;
        running = true;

        Thread sampler = new Thread(ClientThreadSampler::loop, "VulkanMod Sampler");
        sampler.setDaemon(true);
        // Below the client thread: the sampler must never be the reason a frame is late.
        sampler.setPriority(Thread.MIN_PRIORITY);
        sampler.start();
        Initializer.LOGGER.warn("[Sampler] sampling {} every {} ms", clientThread.getName(), INTERVAL_MS);
    }

    private static void loop() {
        while (running) {
            Thread t = target;
            if (t == null || !t.isAlive()) break;

            StackTraceElement[] stack = t.getStackTrace();
            if (stack.length > 0) record(stack);

            try {
                Thread.sleep(INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
    }

    private static synchronized void record(StackTraceElement[] stack) {
        totalSamples++;

        // Attributed to the deepest few frames, joined, so "drawing" and "meshing" do not both
        // collapse into whatever JDK method happens to sit at the top of the stack.
        StringBuilder key = new StringBuilder();
        for (int i = 0; i < Math.min(DEPTH, stack.length); i++) {
            StackTraceElement e = stack[i];
            if (i > 0) key.append(" <- ");
            key.append(shorten(e.getClassName())).append('.').append(e.getMethodName());
        }

        counts.computeIfAbsent(key.toString(), k -> new int[1])[0]++;
        phases.computeIfAbsent(phaseOf(stack), k -> new int[1])[0]++;
    }

    /**
     * Names the phase a sample belongs to.
     *
     * <p>Walked from the top of the stack downwards so the innermost recognised phase wins: a
     * sample inside renderClouds under renderWorld is clouds, not "world (other)".
     */
    private static String phaseOf(StackTraceElement[] stack) {
        for (StackTraceElement e : stack) {
            String frame = e.getClassName() + "." + e.getMethodName();
            for (String[] phase : PHASES) {
                if (frame.endsWith(phase[0])) return phase[1];
            }
        }
        return "unclassified";
    }

    /** Only the last two package segments, so a line of output holds four frames. */
    private static String shorten(String className) {
        int last = className.lastIndexOf('.');
        if (last < 0) return className;
        int prev = className.lastIndexOf('.', last - 1);
        return prev < 0 ? className : className.substring(prev + 1);
    }

    public static synchronized String report(int limit) {
        if (!ENABLED || totalSamples == 0) return "";

        List<Map.Entry<String, int[]>> top = new ArrayList<>(counts.entrySet());
        top.sort(Comparator.comparingInt((Map.Entry<String, int[]> e) -> e.getValue()[0]).reversed());

        List<Map.Entry<String, int[]>> byPhase = new ArrayList<>(phases.entrySet());
        byPhase.sort(Comparator.comparingInt((Map.Entry<String, int[]> e) -> e.getValue()[0]).reversed());

        StringBuilder sb = new StringBuilder();
        sb.append(String.format("║ CLIENT THREAD SAMPLES: %d total%n", totalSamples));
        sb.append("║ BY PHASE (adds up to 100%):\n");
        for (Map.Entry<String, int[]> e : byPhase) {
            sb.append(String.format("║   %5.1f%%  %s%n",
                    100.0 * e.getValue()[0] / totalSamples, e.getKey()));
        }
        sb.append("║ HOTTEST STACKS:\n");
        for (int i = 0; i < Math.min(limit, top.size()); i++) {
            Map.Entry<String, int[]> e = top.get(i);
            sb.append(String.format("║   %5.1f%%  %s%n",
                    100.0 * e.getValue()[0] / totalSamples, e.getKey()));
        }

        counts.clear();
        phases.clear();
        totalSamples = 0;
        return sb.toString();
    }

    public static synchronized void stop() {
        running = false;
    }
}
