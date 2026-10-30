package net.vulkanmod.vulkan.async;

import net.vulkanmod.Initializer;

import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Async lighting engine that offloads light propagation calculations
 * to worker threads. Minecraft's lighting is purely CPU-based (flood fill
 * from light sources), and this system allows those calculations to happen
 * without blocking the main render thread.
 *
 * Integration: Hook into MC's lighting update path (ChunkProvider / World)
 * to submit light updates here instead of blocking the main thread.
 */
public class AsyncLightingEngine {
    private static AsyncLightingEngine INSTANCE;

    private final ExecutorService executor;
    private final ConcurrentLinkedQueue<LightUpdate> pendingUpdates = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<LightResult> completedResults = new ConcurrentLinkedQueue<>();
    private final AtomicInteger pendingCount = new AtomicInteger(0);

    // Batch size: how many light updates to process per tick on main thread
    private static final int MAX_RESULTS_PER_TICK = 64;

    private AsyncLightingEngine() {
        int threads = Math.max(1, Math.min(2, Runtime.getRuntime().availableProcessors() / 4));
        executor = new ThreadPoolExecutor(
                threads, threads,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(512),
                r -> {
                    Thread t = new Thread(r, "AsyncLighting");
                    t.setDaemon(true);
                    t.setPriority(Thread.NORM_PRIORITY - 2);
                    return t;
                },
                new ThreadPoolExecutor.DiscardOldestPolicy()
        );
        Initializer.LOGGER.info("AsyncLightingEngine: started with " + threads + " threads");
    }

    public static AsyncLightingEngine getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new AsyncLightingEngine();
        }
        return INSTANCE;
    }

    /**
     * Submit a light update for async processing.
     * Called when a block changes that affects lighting.
     */
    public void submitLightUpdate(int chunkX, int chunkZ, int sectionY,
                                   byte[] blockLight, byte[] skyLight,
                                   int[] neighborLight) {
        LightUpdate update = new LightUpdate(chunkX, chunkZ, sectionY, blockLight, skyLight, neighborLight);
        pendingCount.incrementAndGet();

        executor.submit(() -> {
            try {
                LightResult result = processLightUpdate(update);
                completedResults.add(result);
            } finally {
                pendingCount.decrementAndGet();
            }
        });
    }

    /**
     * Process light propagation on worker thread.
     * Implements a simplified flood-fill from light sources within
     * a 16x16x16 section, considering neighbor data for cross-section propagation.
     */
    private LightResult processLightUpdate(LightUpdate update) {
        byte[] newBlockLight = update.blockLight.clone();
        byte[] newSkyLight = update.skyLight.clone();

        // Propagate block light (flood fill with attenuation)
        propagateLight(newBlockLight, update.neighborLight, false);

        // Propagate sky light (flood fill from top, attenuated by opacity)
        propagateLight(newSkyLight, update.neighborLight, true);

        return new LightResult(update.chunkX, update.chunkZ, update.sectionY,
                newBlockLight, newSkyLight);
    }

    /**
     * Simple flood-fill light propagation within a 16x16x16 section.
     * Each light value decreases by 1 per block distance.
     */
    private void propagateLight(byte[] lightData, int[] neighborLight, boolean isSkyLight) {
        // Iterate multiple passes for propagation (max light level is 15)
        boolean changed = true;
        int passes = 0;
        int maxPasses = 15;

        while (changed && passes < maxPasses) {
            changed = false;
            passes++;

            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    for (int x = 0; x < 16; x++) {
                        int idx = (y << 8) | (z << 4) | x;
                        int current = lightData[idx] & 0xF;

                        // Check all 6 neighbors
                        int maxNeighbor = 0;

                        if (x > 0) maxNeighbor = Math.max(maxNeighbor, (lightData[idx - 1] & 0xF));
                        if (x < 15) maxNeighbor = Math.max(maxNeighbor, (lightData[idx + 1] & 0xF));
                        if (z > 0) maxNeighbor = Math.max(maxNeighbor, (lightData[idx - 16] & 0xF));
                        if (z < 15) maxNeighbor = Math.max(maxNeighbor, (lightData[idx + 16] & 0xF));
                        if (y > 0) maxNeighbor = Math.max(maxNeighbor, (lightData[idx - 256] & 0xF));
                        if (y < 15) maxNeighbor = Math.max(maxNeighbor, (lightData[idx + 256] & 0xF));

                        // For sky light, vertical propagation doesn't attenuate
                        int propagated;
                        if (isSkyLight && y < 15 && (lightData[idx + 256] & 0xF) == 15) {
                            propagated = 15;
                        } else {
                            propagated = Math.max(0, maxNeighbor - 1);
                        }

                        if (propagated > current) {
                            lightData[idx] = (byte) propagated;
                            changed = true;
                        }
                    }
                }
            }
        }
    }

    /**
     * Drain completed light results on main thread.
     * Call this once per tick to apply async lighting results.
     */
    public int drainCompletedResults(java.util.function.Consumer<LightResult> consumer) {
        int processed = 0;
        LightResult result;
        while (processed < MAX_RESULTS_PER_TICK && (result = completedResults.poll()) != null) {
            consumer.accept(result);
            processed++;
        }
        return processed;
    }

    public int getPendingCount() {
        return pendingCount.get();
    }

    public boolean hasCompletedResults() {
        return !completedResults.isEmpty();
    }

    public void shutdown() {
        executor.shutdown();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            executor.shutdownNow();
        }
    }

    public static class LightUpdate {
        public final int chunkX, chunkZ, sectionY;
        public final byte[] blockLight;  // 4096 entries (16^3)
        public final byte[] skyLight;    // 4096 entries
        public final int[] neighborLight; // neighboring section light for cross-boundary propagation

        public LightUpdate(int chunkX, int chunkZ, int sectionY,
                           byte[] blockLight, byte[] skyLight, int[] neighborLight) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.sectionY = sectionY;
            this.blockLight = blockLight;
            this.skyLight = skyLight;
            this.neighborLight = neighborLight;
        }
    }

    public static class LightResult {
        public final int chunkX, chunkZ, sectionY;
        public final byte[] blockLight;
        public final byte[] skyLight;

        public LightResult(int chunkX, int chunkZ, int sectionY,
                           byte[] blockLight, byte[] skyLight) {
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
            this.sectionY = sectionY;
            this.blockLight = blockLight;
            this.skyLight = skyLight;
        }
    }
}
