package net.vulkanmod.vulkan;

import org.joml.FrustumIntersection;
import org.joml.Matrix4f;

public class FrustumCuller {
    private static final FrustumIntersection frustum = new FrustumIntersection();
    private static double cameraX, cameraY, cameraZ;
    private static int renderDistanceBlocks = 128; // default 8 chunks * 16
    private static boolean frustumReady = false;
    private static double maxDistSq = (128 + 16) * (128 + 16); // precomputed

    // Update frustum planes from clip matrix (P × V) using JOML's built-in extraction.
    public static void updateFrustum(Matrix4f vp) {
        frustum.set(vp);
        frustumReady = true;
    }

    public static void setCameraPosition(double x, double y, double z) {
        cameraX = x;
        cameraY = y;
        cameraZ = z;
    }

    public static void setRenderDistance(int chunks) {
        renderDistanceBlocks = chunks * 16;
        double md = renderDistanceBlocks + 16;
        maxDistSq = md * md;
    }

    public static int getRenderDistanceBlocks() {
        return renderDistanceBlocks;
    }

    public static boolean isFrustumReady() {
        return frustumReady;
    }

    // Begin new frame — no-op now that dead occlusion code is removed
    public static void beginFrame() {
    }

    // Check if AABB is inside frustum using JOML FrustumIntersection
    public static boolean isAABBVisible(float minX, float minY, float minZ, float maxX, float maxY, float maxZ) {
        return frustum.testAab(minX, minY, minZ, maxX, maxY, maxZ);
    }

    // Chunk section bounding box test (sections are 16x16x16)
    public static boolean isSectionVisible(int sectionX, int sectionY, int sectionZ) {
        if (!frustumReady) return true;

        float minX = sectionX << 4; // * 16
        float maxX = minX + 16;
        float minY = sectionY << 4;
        float maxY = minY + 16;
        float minZ = sectionZ << 4;
        float maxZ = minZ + 16;

        // Frustum test
        if (!isAABBVisible(minX, minY, minZ, maxX, maxY, maxZ)) {
            return false;
        }

        // Distance test (squared) — precomputed maxDistSq
        double cx = minX + 8 - cameraX;
        double cy = minY + 8 - cameraY;
        double cz = minZ + 8 - cameraZ;
        return cx * cx + cy * cy + cz * cz <= maxDistSq;
    }

    // Chunk bounding box test (chunks are 16x256x16)
    public static boolean isChunkVisible(int chunkX, int chunkZ) {
        float minX = chunkX << 4;
        float maxX = minX + 16;
        float minZ = chunkZ << 4;
        float maxZ = minZ + 16;

        return isAABBVisible(minX, 0, minZ, maxX, 256, maxZ);
    }

    // Check if a position is within render distance (squared distance check)
    public static boolean isWithinRenderDistance(double x, double y, double z) {
        double dx = x - cameraX;
        double dy = y - cameraY;
        double dz = z - cameraZ;
        return dx * dx + dy * dy + dz * dz <= maxDistSq;
    }

    // Check if a chunk is within render distance
    public static boolean isChunkWithinRenderDistance(int chunkX, int chunkZ) {
        double dx = (chunkX << 4) + 8 - cameraX;
        double dz = (chunkZ << 4) + 8 - cameraZ;
        return dx * dx + dz * dz <= maxDistSq;
    }

    // Frustum check for entities (padded AABB vs frustum planes).
    // Distance culling is handled separately by the caller via fog-limit.
    public static boolean isEntityVisible(double x, double y, double z, double halfWidth, double height) {
        if (!frustumReady) return true;

        return isAABBVisible(
                (float)(x - halfWidth), (float)y, (float)(z - halfWidth),
                (float)(x + halfWidth), (float)(y + height), (float)(z + halfWidth)
        );
    }

    // Squared distance from camera to a point — used for LOD / VRS decisions
    public static double getDistanceSq(double x, double y, double z) {
        double dx = x - cameraX;
        double dy = y - cameraY;
        double dz = z - cameraZ;
        return dx * dx + dy * dy + dz * dz;
    }

    public static double getCameraX() { return cameraX; }
    public static double getCameraY() { return cameraY; }
    public static double getCameraZ() { return cameraZ; }
}
