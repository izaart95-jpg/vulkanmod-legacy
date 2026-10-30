package net.vulkanmod.vulkan.async;

import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.Drawer;
import net.vulkanmod.vulkan.memory.MemoryManager;
import net.vulkanmod.vulkan.memory.MemoryTypes;
import net.vulkanmod.vulkan.memory.VertexBuffer;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Async chunk meshing system. Offloads face classification, normal-based
 * reordering, and persistent VBO upload preparation to worker threads.
 * The main thread only needs to pick up completed results and do the
 * final GPU upload (which requires the Vulkan context).
 */
public class AsyncChunkMesher {
    private static AsyncChunkMesher INSTANCE;

    private final ExecutorService executor;
    private final int threadCount;

    // Stats
    private final AtomicInteger pendingTasks = new AtomicInteger(0);
    private final AtomicInteger completedTasks = new AtomicInteger(0);

    private AsyncChunkMesher() {
        // Use half available cores (min 2, max 4) — meshing is CPU-bound but
        // we don't want to starve the main thread or lighting engine
        threadCount = Math.max(2, Math.min(4, Runtime.getRuntime().availableProcessors() / 2));
        executor = new ThreadPoolExecutor(
                threadCount, threadCount,
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(256),
                r -> {
                    Thread t = new Thread(r, "ChunkMesher-" + pendingTasks.get());
                    t.setDaemon(true);
                    t.setPriority(Thread.NORM_PRIORITY - 1);
                    return t;
                },
                new ThreadPoolExecutor.DiscardOldestPolicy()
        );
        Initializer.LOGGER.info("AsyncChunkMesher: started with " + threadCount + " threads");
    }

    public static AsyncChunkMesher getInstance() {
        if (INSTANCE == null) {
            INSTANCE = new AsyncChunkMesher();
        }
        return INSTANCE;
    }

    /**
     * Submit chunk mesh data for async face classification and reordering.
     * Returns a Future containing the processed result.
     */
    public Future<MeshResult> submitMeshTask(ByteBuffer vertexData, int vertexCount,
                                              int vertexSize, int normalOffset) {
        return submitMeshTask(vertexData, vertexCount, vertexSize, normalOffset, -1);
    }

    public Future<MeshResult> submitMeshTask(ByteBuffer vertexData, int vertexCount,
                                              int vertexSize, int normalOffset, int positionOffset) {
        pendingTasks.incrementAndGet();

        // Copy vertex data to a thread-owned buffer (input may be reused)
        int dataSize = vertexCount * vertexSize;
        byte[] dataCopy = new byte[dataSize];
        int pos = vertexData.position();
        vertexData.get(dataCopy, 0, dataSize);
        vertexData.position(pos);

        return executor.submit(() -> {
            try {
                return processMesh(dataCopy, vertexCount, vertexSize, normalOffset, positionOffset);
            } finally {
                pendingTasks.decrementAndGet();
                completedTasks.incrementAndGet();
            }
        });
    }

    /**
     * Process mesh on worker thread: classify faces by normal, reorder quads
     * by direction for CPU face culling.
     */
    private MeshResult processMesh(byte[] srcData, int vertexCount, int vertexSize,
                                   int normalOffset, int positionOffset) {
        int quadCount = vertexCount / 4;
        int quadBytes = 4 * vertexSize;

        boolean useNormals = normalOffset >= 0;
        boolean useGeometry = !useNormals && positionOffset >= 0;

        if (quadCount <= 0 || (!useNormals && !useGeometry)) {
            return new MeshResult(srcData, vertexCount, null, null, false);
        }

        ByteBuffer view = useGeometry
                ? ByteBuffer.wrap(srcData).order(ByteOrder.nativeOrder())
                : null;

        // Classify each quad by facing direction
        int[] quadDirs = new int[quadCount];
        int[] dirCounts = new int[7];

        for (int q = 0; q < quadCount; q++) {
            int quadBase = q * quadBytes;
            int dir;
            if (useNormals) {
                int base = quadBase + normalOffset;
                dir = (base + 2 < srcData.length)
                        ? classifyNormal(srcData[base], srcData[base + 1], srcData[base + 2])
                        : 6;
            } else {
                dir = classifyGeometric(view, quadBase + positionOffset, vertexSize, srcData.length);
            }
            quadDirs[q] = dir;
            dirCounts[dir]++;
        }

        // Compute start offsets per direction
        int[] dirStart = new int[7];
        dirStart[0] = 0;
        for (int d = 1; d < 7; d++) {
            dirStart[d] = dirStart[d - 1] + dirCounts[d - 1];
        }

        // Reorder quads by direction
        int[] dirCurrent = Arrays.copyOf(dirStart, 7);
        byte[] reordered = new byte[quadCount * quadBytes];

        for (int q = 0; q < quadCount; q++) {
            int dir = quadDirs[q];
            int destQuad = dirCurrent[dir]++;
            System.arraycopy(srcData, q * quadBytes, reordered, destQuad * quadBytes, quadBytes);
        }

        return new MeshResult(reordered, quadCount * 4, dirCounts.clone(), dirStart.clone(), true);
    }

    /**
     * Derives a quad's facing from its own geometry.
     *
     * <p>VertexFormats.BLOCK carries no NORMAL element, so there is nothing to read: the facing
     * has to come from the winding. For a counter-clockwise quad, (v1-v0) x (v2-v0) is the
     * outward normal. Degenerate quads fall back to UNDEFINED, which is never culled.
     */
    private static int classifyGeometric(ByteBuffer data, int posBase, int vertexSize, int dataLength) {
        if (posBase + 2 * vertexSize + 12 > dataLength) return 6;

        float x0 = data.getFloat(posBase);
        float y0 = data.getFloat(posBase + 4);
        float z0 = data.getFloat(posBase + 8);

        int p1 = posBase + vertexSize;
        float ax = data.getFloat(p1) - x0;
        float ay = data.getFloat(p1 + 4) - y0;
        float az = data.getFloat(p1 + 8) - z0;

        int p2 = posBase + 2 * vertexSize;
        float bx = data.getFloat(p2) - x0;
        float by = data.getFloat(p2 + 4) - y0;
        float bz = data.getFloat(p2 + 8) - z0;

        float nx = ay * bz - az * by;
        float ny = az * bx - ax * bz;
        float nz = ax * by - ay * bx;

        float fax = Math.abs(nx), fay = Math.abs(ny), faz = Math.abs(nz);
        float sum = fax + fay + faz;
        if (sum < 1.0e-6f) return 6;

        // Only bucket a quad by direction when its normal is genuinely axis aligned.
        //
        // The face mask culls a whole direction using a test that is only valid for flat,
        // axis-aligned faces ("is the camera above the top of this section"). Anything angled --
        // cross-shaped plants, torches, fences, stairs, rails -- has a diagonal normal, and
        // assigning it to its dominant axis meant it got culled at angles from which it is
        // plainly visible. Those go to UNDEFINED, which is never culled.
        float max = Math.max(fax, Math.max(fay, faz));
        if (sum - max > 1.0e-4f * max) return 6;

        if (fay >= fax && fay >= faz) return ny > 0 ? 1 : 0;
        if (fax >= fay && fax >= faz) return nx > 0 ? 5 : 4;
        return nz > 0 ? 3 : 2;
    }

    private static int classifyNormal(byte nx, byte ny, byte nz) {
        int ax = nx < 0 ? -nx : nx;
        int ay = ny < 0 ? -ny : ny;
        int az = nz < 0 ? -nz : nz;
        if (ax == 0 && ay == 0 && az == 0) return 6;
        if (ay >= ax && ay >= az) return ny > 0 ? 1 : 0;
        if (ax >= ay && ax >= az) return nx > 0 ? 5 : 4;
        return nz > 0 ? 3 : 2;
    }

    public int getPendingCount() {
        return pendingTasks.get();
    }

    public int getCompletedCount() {
        return completedTasks.get();
    }

    public void shutdown() {
        executor.shutdown();
        try {
            executor.awaitTermination(5, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            executor.shutdownNow();
        }
    }

    /**
     * Result of async mesh processing. Contains reordered vertex data
     * and face direction metadata for CPU culling.
     */
    public static class MeshResult {
        public final byte[] vertexData;
        public final int vertexCount;
        public final int[] dirQuadCounts;  // quads per direction (7 dirs)
        public final int[] dirStartQuad;   // start quad index per direction
        public final boolean hasFaceCulling;

        public MeshResult(byte[] vertexData, int vertexCount,
                          int[] dirQuadCounts, int[] dirStartQuad, boolean hasFaceCulling) {
            this.vertexData = vertexData;
            this.vertexCount = vertexCount;
            this.dirQuadCounts = dirQuadCounts;
            this.dirStartQuad = dirStartQuad;
            this.hasFaceCulling = hasFaceCulling;
        }
    }
}
