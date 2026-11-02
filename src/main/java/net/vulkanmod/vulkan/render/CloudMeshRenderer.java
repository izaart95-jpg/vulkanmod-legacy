package net.vulkanmod.vulkan.render;

import com.mojang.blaze3d.platform.GlStateManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.Drawer;
import net.vulkanmod.vulkan.Pipeline;
import net.vulkanmod.vulkan.PipelineManager;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.memory.MemoryTypes;
import net.vulkanmod.vulkan.memory.VertexBuffer;
import net.vulkanmod.vulkan.texture.GLTextureCache;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

/**
 * Persistent mesh for Minecraft 1.8.9's fancy cloud layer.
 *
 * <p>{@code renderFancyClouds} regenerates its entire 8x8 grid of cloud cells every frame. The
 * geometry is identical from frame to frame apart from a small translation, the cloud height and
 * the tint of the day/night cycle; sampling the client thread put around a fifth of it inside
 * that method in a scene with almost no terrain. Building the cells once and animating them with
 * the modelview matrix and the existing {@code ColorModulator} uniform removes that work from
 * the frame entirely.
 *
 * <p>The only values baked into the mesh are the integer scroll offsets of the cloud texture and
 * whether the top/bottom faces are currently needed. Both change rarely, so a rebuild is a
 * handful of frames apart at worst and costs one upload when it happens.
 *
 * <p>Anaglyph 3D keeps using the vanilla path: its colour-mask passes are cheap relative to the
 * normal path and the extra state makes the cached path more fragile than it is worth.
 */
public final class CloudMeshRenderer {

    /** Minecraft's POSITION_TEXTURE_COLOR_NORMAL vertex: 12 + 8 + 4 + 3 + 1 bytes. */
    private static final int STRIDE = 28;

    /**
     * A cell can contribute a bottom quad, a top quad and up to 16 side quads (8 on each axis
     * when the cell is in the middle of the grid). 64 cells x 34 quads x 4 vertices = 8704, but
     * the outer cells skip one side per axis, so the real maximum is 6144.
     */
    private static final int MAX_VERTICES = 6144;

    private static final Identifier CLOUDS = new Identifier("textures/environment/clouds.png");
    private static final float EPSILON = 9.765625E-4f;
    private static final float UV_SCALE = 0.00390625f;
    private static final float SCALE = 12.0f;

    private static VertexBuffer mesh;
    private static final ByteBuffer buildBuffer = MemoryUtil.memAlloc(MAX_VERTICES * STRIDE);
    private static int vertexCount;
    private static long cacheKey = Long.MIN_VALUE;
    private static boolean loggedDraw;
    private static boolean loggedBuild;

    private CloudMeshRenderer() {}

    /**
     * Draws the cached cloud layer, or returns false so the vanilla method (and the batching
     * fallback) can run. The caller must not have changed any state when this returns false.
     */
    public static boolean render(MinecraftClient client, ClientWorld world, int ticks,
                                 float tickDelta, int anaglyphFilter) {
        if (!Initializer.vulkanReady || anaglyphFilter != 2 || client == null || world == null) {
            return false;
        }

        Entity camera = client.getCameraEntity();
        if (camera == null) return false;

        Drawer drawer = Drawer.getInstance();
        if (!drawer.isRenderPassActive()) return false;

        Pipeline pipeline = PipelineManager.getInstance()
                .getPipelineForFormat(VertexFormats.POSITION_TEXTURE_COLOR_NORMAL);
        if (pipeline == null) return false;

        // Every value below mirrors WorldRenderer.renderFancyClouds exactly. The comments there
        // are sparse, so the names are kept close to the original arithmetic.
        float cameraY = (float) (camera.prevTickY + (camera.y - camera.prevTickY) * (double) tickDelta);
        float cloudY = world.dimension.getCloudHeight() - cameraY + 0.33f;
        boolean bottomFace = cloudY > -5.0f;
        boolean topFace = cloudY <= 5.0f;

        double time = (float) ticks + tickDelta;
        double scrollX = (camera.prevX + (camera.x - camera.prevX) * (double) tickDelta
                + time * 0.03) / 12.0;
        double scrollZ = (camera.prevZ + (camera.z - camera.prevZ) * (double) tickDelta) / 12.0 + 0.33;

        // The original code wraps the scroll into 2048-block segments before taking the
        // fractional part. That wrap only exists to keep the texture coordinates in range; keep
        // it so the cached mesh is identical to what the vanilla method would build.
        int wrapX = MathHelper.floor(scrollX / 2048.0);
        int wrapZ = MathHelper.floor(scrollZ / 2048.0);
        scrollX -= (double) (wrapX * 2048);
        scrollZ -= (double) (wrapZ * 2048);

        int integerX = MathHelper.floor(scrollX);
        int integerZ = MathHelper.floor(scrollZ);
        float fractionX = (float) (scrollX - (double) integerX);
        float fractionZ = (float) (scrollZ - (double) integerZ);

        float textureX = (float) integerX * UV_SCALE;
        float textureZ = (float) integerZ * UV_SCALE;

        // The texture offset and the face set are the only baked state. The cell pattern itself
        // is fixed, and the rest of the animation rides in uniforms.
        long key = ((long) integerX << 32) ^ (integerZ & 0xffffffffL);
        key = key * 31L + (bottomFace ? 1 : 0);
        key = key * 31L + (topFace ? 1 : 0);

        if (key != cacheKey) {
            build(bottomFace, topFace, textureX, textureZ);
            cacheKey = key;
        }
        if (mesh == null || vertexCount <= 0) return false;

        // Flush any pending terrain before touching the colour mask: the batch draw applies the
        // current pipeline state, so flushing under a disabled mask would erase it.
        drawer.flushTerrainBatch();

        Vec3d color = world.getCloudColor(tickDelta);

        GlStateManager.disableCull();
        client.getTextureManager().bindTexture(CLOUDS);
        GlStateManager.enableBlend();
        GlStateManager.blendFuncSeparate(770, 771, 1, 0);

        // The generic pipeline reads the texture through Vulkan's selector, not GL's binding.
        GLTextureCache.syncBoundTexture();
        Drawer.setBoundPipeline(pipeline);

        // The per-face brightness factors are baked into the mesh; the day/night colour is the
        // modulator, exactly as the vanilla vertex colours multiplied the base cloud colour.
        GlStateManager.color4f((float) color.x, (float) color.y, (float) color.z, 1.0f);

        GlStateManager.pushMatrix();
        GlStateManager.translatef(-SCALE * fractionX, cloudY, -SCALE * fractionZ);
        VRenderSystem.calculateMVPWithGLSync();

        // Same two passes as vanilla: fill depth with the colour masked off so only the nearest
        // cloud surface is blended, then draw the visible pass. Without the prepass, cloud faces
        // behind the nearest one would blend through and the layer would look washed out.
        GlStateManager.colorMask(false, false, false, false);
        drawer.drawPersistent(mesh, vertexCount, 7, VertexFormats.POSITION_TEXTURE_COLOR_NORMAL);
        GlStateManager.colorMask(true, true, true, true);
        drawer.drawPersistent(mesh, vertexCount, 7, VertexFormats.POSITION_TEXTURE_COLOR_NORMAL);

        if (!loggedDraw) {
            loggedDraw = true;
            Initializer.LOGGER.info("[CloudMesh] cached fancy-cloud path active");
        }

        GlStateManager.popMatrix();

        GlStateManager.color4f(1.0f, 1.0f, 1.0f, 1.0f);
        GlStateManager.disableBlend();
        GlStateManager.enableCull();
        return true;
    }

    private static void build(boolean bottomFace, boolean topFace, float textureX, float textureZ) {
        ByteBuffer b = buildBuffer;
        b.clear();

        for (int cellX = -3; cellX <= 4; cellX++) {
            for (int cellZ = -3; cellZ <= 4; cellZ++) {
                float baseX = cellX * 8;
                float baseZ = cellZ * 8;

                if (bottomFace) {
                    vertex(b, baseX, 0.0f, baseZ + 8.0f,
                            uv(baseX, 0.0f, textureX), uv(baseZ, 8.0f, textureZ), 0.7f, 0.0f, -1.0f, 0.0f);
                    vertex(b, baseX + 8.0f, 0.0f, baseZ + 8.0f,
                            uv(baseX, 8.0f, textureX), uv(baseZ, 8.0f, textureZ), 0.7f, 0.0f, -1.0f, 0.0f);
                    vertex(b, baseX + 8.0f, 0.0f, baseZ,
                            uv(baseX, 8.0f, textureX), uv(baseZ, 0.0f, textureZ), 0.7f, 0.0f, -1.0f, 0.0f);
                    vertex(b, baseX, 0.0f, baseZ,
                            uv(baseX, 0.0f, textureX), uv(baseZ, 0.0f, textureZ), 0.7f, 0.0f, -1.0f, 0.0f);
                }

                if (topFace) {
                    vertex(b, baseX, 4.0f - EPSILON, baseZ + 8.0f,
                            uv(baseX, 0.0f, textureX), uv(baseZ, 8.0f, textureZ), 1.0f, 0.0f, 1.0f, 0.0f);
                    vertex(b, baseX + 8.0f, 4.0f - EPSILON, baseZ + 8.0f,
                            uv(baseX, 8.0f, textureX), uv(baseZ, 8.0f, textureZ), 1.0f, 0.0f, 1.0f, 0.0f);
                    vertex(b, baseX + 8.0f, 4.0f - EPSILON, baseZ,
                            uv(baseX, 8.0f, textureX), uv(baseZ, 0.0f, textureZ), 1.0f, 0.0f, 1.0f, 0.0f);
                    vertex(b, baseX, 4.0f - EPSILON, baseZ,
                            uv(baseX, 0.0f, textureX), uv(baseZ, 0.0f, textureZ), 1.0f, 0.0f, 1.0f, 0.0f);
                }

                if (cellX > -1) {
                    for (int slice = 0; slice < 8; slice++) {
                        float x = baseX + slice;
                        float u = uv(baseX, slice + 0.5f, textureX);
                        float v0 = uv(baseZ, 8.0f, textureZ);
                        float v1 = uv(baseZ, 0.0f, textureZ);
                        vertex(b, x, 0.0f, baseZ + 8.0f, u, v0, 0.9f, -1.0f, 0.0f, 0.0f);
                        vertex(b, x, 4.0f, baseZ + 8.0f, u, v0, 0.9f, -1.0f, 0.0f, 0.0f);
                        vertex(b, x, 4.0f, baseZ, u, v1, 0.9f, -1.0f, 0.0f, 0.0f);
                        vertex(b, x, 0.0f, baseZ, u, v1, 0.9f, -1.0f, 0.0f, 0.0f);
                    }
                }

                if (cellX <= 1) {
                    for (int slice = 0; slice < 8; slice++) {
                        float x = baseX + slice + 1.0f - EPSILON;
                        float u = uv(baseX, slice + 0.5f, textureX);
                        float v0 = uv(baseZ, 8.0f, textureZ);
                        float v1 = uv(baseZ, 0.0f, textureZ);
                        vertex(b, x, 0.0f, baseZ + 8.0f, u, v0, 0.9f, 1.0f, 0.0f, 0.0f);
                        vertex(b, x, 4.0f, baseZ + 8.0f, u, v0, 0.9f, 1.0f, 0.0f, 0.0f);
                        vertex(b, x, 4.0f, baseZ, u, v1, 0.9f, 1.0f, 0.0f, 0.0f);
                        vertex(b, x, 0.0f, baseZ, u, v1, 0.9f, 1.0f, 0.0f, 0.0f);
                    }
                }

                if (cellZ > -1) {
                    for (int slice = 0; slice < 8; slice++) {
                        float z = baseZ + slice;
                        float v = uv(baseZ, slice + 0.5f, textureZ);
                        float u0 = uv(baseX, 0.0f, textureX);
                        float u1 = uv(baseX, 8.0f, textureX);
                        vertex(b, baseX, 4.0f, z, u0, v, 0.8f, 0.0f, 0.0f, -1.0f);
                        vertex(b, baseX + 8.0f, 4.0f, z, u1, v, 0.8f, 0.0f, 0.0f, -1.0f);
                        vertex(b, baseX + 8.0f, 0.0f, z, u1, v, 0.8f, 0.0f, 0.0f, -1.0f);
                        vertex(b, baseX, 0.0f, z, u0, v, 0.8f, 0.0f, 0.0f, -1.0f);
                    }
                }

                if (cellZ <= 1) {
                    for (int slice = 0; slice < 8; slice++) {
                        float z = baseZ + slice + 1.0f - EPSILON;
                        float v = uv(baseZ, slice + 0.5f, textureZ);
                        float u0 = uv(baseX, 0.0f, textureX);
                        float u1 = uv(baseX, 8.0f, textureX);
                        vertex(b, baseX, 4.0f, z, u0, v, 0.8f, 0.0f, 0.0f, 1.0f);
                        vertex(b, baseX + 8.0f, 4.0f, z, u1, v, 0.8f, 0.0f, 0.0f, 1.0f);
                        vertex(b, baseX + 8.0f, 0.0f, z, u1, v, 0.8f, 0.0f, 0.0f, 1.0f);
                        vertex(b, baseX, 0.0f, z, u0, v, 0.8f, 0.0f, 0.0f, 1.0f);
                    }
                }
            }
        }

        b.flip();
        int bytes = b.remaining();
        if (bytes == 0) {
            vertexCount = 0;
            return;
        }

        if (mesh == null || mesh.getSize() < bytes) {
            if (mesh != null) mesh.freeBuffer();
            mesh = new VertexBuffer(MAX_VERTICES * STRIDE, MemoryTypes.GPU_MEM);
        }
        mesh.uploadWholeBuffer(b);
        vertexCount = bytes / STRIDE;
        if (!loggedBuild) {
            loggedBuild = true;
            Initializer.LOGGER.info("[CloudMesh] built cached fancy-cloud mesh ({} vertices, {} KB)",
                    vertexCount, bytes / 1024);
        }
    }

    /**
     * Writes one vertex. X and Z are given in unscaled cloud-grid units and multiplied by the
     * same 12x that vanilla applies with {@code GlStateManager.scalef}; that scale then lives in
     * the buffer instead of the per-frame matrix.
     */
    private static void vertex(ByteBuffer b, float x, float y, float z,
                               float u, float v, float color, float nx, float ny, float nz) {
        b.putFloat(x * SCALE);
        b.putFloat(y);
        b.putFloat(z * SCALE);
        b.putFloat(u);
        b.putFloat(v);
        int c = (int) (color * 255.0f);
        b.put((byte) c).put((byte) c).put((byte) c).put((byte) 204);
        b.put((byte) (nx * 127.0f));
        b.put((byte) (ny * 127.0f));
        b.put((byte) (nz * 127.0f));
        b.put((byte) 0);
    }

    private static float uv(float base, float offset, float textureOffset) {
        return (base + offset) * UV_SCALE + textureOffset;
    }
}
