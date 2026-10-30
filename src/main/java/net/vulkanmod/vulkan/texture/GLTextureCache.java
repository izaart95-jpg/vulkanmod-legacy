package net.vulkanmod.vulkan.texture;

import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.VRenderSystem;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

public class GLTextureCache {
    private static final Map<Integer, VulkanImage> cache = new HashMap<>();
    private static final Set<Integer> emptyTextures = new HashSet<>();
    private static int lastSyncedGlId = -1;
    private static VulkanImage lastSyncedImage = null;

    private static int syncCount = 0;
    private static int lastLightmapGlId = -1;
    private static VulkanImage lastLightmapImage = null;
    private static boolean lightmapSyncedThisFrame = false;

    public static void syncBoundTexture() {
        int trackedId = VRenderSystem.getBoundTextureId();
        SmartAnimations.markUsed(trackedId);

        // Fast path: tracked ID matches last synced — skip all GL calls (99% of draws)
        if (trackedId == lastSyncedGlId && lastSyncedImage != null && trackedId != 0) {
            VTextureSelector.bindTexture(lastSyncedImage);
            return;
        }

        // Try tracked ID first before falling back to GL query
        if (trackedId > 0) {
            VulkanImage cachedImage = cache.get(trackedId);
            if (cachedImage != null) {
                lastSyncedGlId = trackedId;
                lastSyncedImage = cachedImage;
                VTextureSelector.bindTexture(cachedImage);
                return;
            }
        }

        // Fall back to GL query only when tracked ID fails
        int glTextureId = trackedId > 0 ? trackedId : GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);

        if (glTextureId == 0) {
            VTextureSelector.bindTexture(VTextureSelector.getWhiteTexture());
            return;
        }

        VRenderSystem.trackBoundTexture(glTextureId);

        if (glTextureId == lastSyncedGlId && lastSyncedImage != null) {
            VTextureSelector.bindTexture(lastSyncedImage);
            return;
        }

        // If previously empty, remove from cache to re-read
        if (emptyTextures.remove(glTextureId)) {
            cache.remove(glTextureId);
        }

        VulkanImage cachedImage = cache.get(glTextureId);
        if (cachedImage != null) {
            lastSyncedGlId = glTextureId;
            lastSyncedImage = cachedImage;
            VTextureSelector.bindTexture(cachedImage);
            return;
        }

        readAndCacheTexture(glTextureId);
    }

    public static void resetFrameState() {
        lightmapSyncedThisFrame = false;
    }

    // Reusable pixel buffer for lightmap reads — avoids alloc/free per frame
    private static ByteBuffer lightmapPixelBuf;
    private static int lightmapPixelBufSize;
    // Frame counter to throttle lightmap re-reads (lightmap only changes when
    // time-of-day or brightness setting changes — not every frame)
    private static int lightmapFrameCounter = 0;
    private static final int LIGHTMAP_SYNC_INTERVAL = 30; // Re-read every N frames

    public static void syncLightmapTexture() {
        // Only sync once per frame — lightmap doesn't change mid-frame
        if (lightmapSyncedThisFrame && lastLightmapImage != null) {
            VTextureSelector.setLightTexture(lastLightmapImage);
            return;
        }

        // Fast path: TextureUploadMixin pushes the lightmap pixels into the cache every time
        // MC uploads them, so the image is already current and no GL call is needed. This also
        // means the lightmap is now exact rather than up to LIGHTMAP_SYNC_INTERVAL frames stale.
        int trackedId = VRenderSystem.getLightmapTextureId();
        if (trackedId > 0) {
            VulkanImage cached = cache.get(trackedId);
            if (cached != null) {
                lastLightmapGlId = trackedId;
                lastLightmapImage = cached;
                lightmapSyncedThisFrame = true;
                VTextureSelector.setLightTexture(cached);
                return;
            }
        }

        // Slow path: the lightmap has not come through the upload hook yet (first frames).
        // Read it back once so something sensible is bound, then let the hook take over.
        GL13.glActiveTexture(GL13.GL_TEXTURE1);
        int lightmapId = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);

        if (lightmapId <= 0) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            VTextureSelector.setLightTexture(VTextureSelector.getWhiteTexture());
            return;
        }

        int width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
        int height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);

        if (width <= 0 || height <= 0) {
            GL13.glActiveTexture(GL13.GL_TEXTURE0);
            VTextureSelector.setLightTexture(VTextureSelector.getWhiteTexture());
            return;
        }

        int needed = width * height * 4;
        if (lightmapPixelBuf == null || lightmapPixelBufSize < needed) {
            if (lightmapPixelBuf != null) MemoryUtil.memFree(lightmapPixelBuf);
            lightmapPixelBuf = MemoryUtil.memAlloc(needed);
            lightmapPixelBufSize = needed;
        }
        lightmapPixelBuf.clear();

        try {
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, lightmapPixelBuf);
            lightmapPixelBuf.position(0);
            VulkanImage lightmap = new VulkanImage(width, height, 4, false, false, lightmapPixelBuf);
            cache.put(lightmapId, lightmap);
            lastLightmapGlId = lightmapId;
            lastLightmapImage = lightmap;
            VTextureSelector.setLightTexture(lightmap);
            lightmapSyncedThisFrame = true;
        } catch (Exception e) {
            Initializer.LOGGER.error("[GLTextureCache] Failed to sync lightmap texture", e);
            VTextureSelector.setLightTexture(VTextureSelector.getWhiteTexture());
        }
        GL13.glActiveTexture(GL13.GL_TEXTURE0);
    }

    // Scratch buffer for ARGB int[] -> RGBA byte conversion. Reused; the lightmap is tiny
    // (16x16) but map textures and skins come through the same path.
    private static ByteBuffer pixelConvBuf;
    private static int pixelConvBufSize;

    /**
     * Pushes an animated sprite frame into the Vulkan copy of an atlas.
     *
     * <p>Minecraft uploads animation frames through TextureUtil's mipmap path, not the flat
     * int[] path, so none of them were reaching Vulkan: the atlas was read back once with
     * glGetTexImage and then never refreshed. Lava, water, fire and portals were frozen.
     */
    public static void uploadSubRegion(int glId, int[][] levels, int width, int height,
                                       int xOffset, int yOffset) {
        if (glId <= 0 || levels == null || levels.length == 0 || width <= 0 || height <= 0) return;

        if (!SmartAnimations.shouldUpload(glId)) return;

        VulkanImage image = cache.get(glId);
        if (image == null) return; // atlas not mirrored to Vulkan (yet); nothing to refresh

        // Refresh every mip level Minecraft supplied. Updating only level 0 would leave the
        // lower levels showing the previous animation frame, which reads as distant lava and
        // water freezing while the same blocks up close animate.
        int levelCount = Math.min(levels.length, image.getMipLevels());
        for (int level = 0; level < levelCount; level++) {
            int[] pixels = levels[level];
            if (pixels == null) continue;

            int lw = width >> level, lh = height >> level;
            int lx = xOffset >> level, ly = yOffset >> level;
            if (lw <= 0 || lh <= 0) break;

            int count = lw * lh;
            if (pixels.length < count) continue;
            if (lx < 0 || ly < 0
                    || lx + lw > (image.getWidth() >> level) || ly + lh > (image.getHeight() >> level)) {
                continue;
            }

            ByteBuffer dst = convertPixels(pixels, count);
            try {
                image.uploadSubTextureAsync(level, lw, lh, lx, ly, 4, 0, 0, lw, dst);
            } catch (Exception e) {
                Initializer.LOGGER.error("[GLTextureCache] Failed to upload animation frame for {} level {}",
                        glId, level, e);
                return;
            }
        }
    }

    private static ByteBuffer convertPixels(int[] pixels, int count) {
        int needed = count * 4;
        if (pixelConvBuf == null || pixelConvBufSize < needed) {
            if (pixelConvBuf != null) MemoryUtil.memFree(pixelConvBuf);
            pixelConvBuf = MemoryUtil.memAlloc(needed);
            pixelConvBufSize = needed;
        }

        ByteBuffer dst = pixelConvBuf;
        dst.clear();
        for (int i = 0; i < count; i++) {
            int p = pixels[i];
            dst.put((byte) (p >> 16));  // R
            dst.put((byte) (p >> 8));   // G
            dst.put((byte) p);          // B
            dst.put((byte) (p >>> 24)); // A
        }
        dst.position(0);
        dst.limit(needed);
        return dst;
    }

    /**
     * Publishes an int[] (0xAARRGGBB) texture upload into the Vulkan cache.
     * Called from TextureUploadMixin, i.e. at the moment MC hands the pixels to GL.
     */
    public static void uploadFromPixels(int glId, int[] pixels, int width, int height) {
        if (glId <= 0 || pixels == null || width <= 0 || height <= 0) return;
        int count = width * height;
        if (pixels.length < count) return;

        ByteBuffer dst = convertPixels(pixels, count);

        try {
            VulkanImage existing = cache.get(glId);
            if (existing != null && existing.getWidth() == width && existing.getHeight() == height) {
                // Same dimensions: reuse the image and just push new texels.
                existing.uploadSubTextureAsync(0, width, height, 0, 0, 4, 0, 0, width, dst);
            } else {
                VulkanImage image = new VulkanImage(width, height, 4, false, false, dst);
                cache.put(glId, image);
                emptyTextures.remove(glId);
                if (lastSyncedGlId == glId) {
                    lastSyncedImage = image;
                }
                if (lastLightmapGlId == glId) {
                    lastLightmapImage = image;
                }
                if (existing != null) {
                    // The image view changed, so any descriptor set pointing at it is stale.
                    net.vulkanmod.vulkan.Pipeline.requestDescriptorInvalidation();
                }
            }
        } catch (Exception e) {
            Initializer.LOGGER.error("[GLTextureCache] Failed to upload pixels for texture {}", glId, e);
        }
    }

    /** -Dvulkanmod.dumptextures=true writes what we hand Vulkan to disk, to compare against GL. */
    private static final boolean DUMP = Boolean.getBoolean("vulkanmod.dumptextures");

    private static void dumpLevel(int glId, int level, int w, int h, ByteBuffer rgba) {
        if (!DUMP) return;
        try {
            java.awt.image.BufferedImage img =
                    new java.awt.image.BufferedImage(w, h, java.awt.image.BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < h; y++) {
                for (int x = 0; x < w; x++) {
                    int i = (y * w + x) * 4;
                    int r = rgba.get(i) & 0xFF, g = rgba.get(i + 1) & 0xFF;
                    int b = rgba.get(i + 2) & 0xFF, a = rgba.get(i + 3) & 0xFF;
                    img.setRGB(x, y, (a << 24) | (r << 16) | (g << 8) | b);
                }
            }
            java.io.File out = new java.io.File("texdump");
            out.mkdirs();
            javax.imageio.ImageIO.write(img, "png", new java.io.File(out, "tex" + glId + "_mip" + level + ".png"));
            Initializer.LOGGER.warn("[GLTextureCache] dumped tex {} level {} ({}x{})", glId, level, w, h);
        } catch (Exception e) {
            Initializer.LOGGER.error("[GLTextureCache] dump failed", e);
        }
    }

    private static void readAndCacheTexture(int glTextureId) {
        int width = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_WIDTH);
        int height = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, 0, GL11.GL_TEXTURE_HEIGHT);

        if (width <= 0 || height <= 0) {
            Initializer.LOGGER.warn("[GLTextureCache] Texture {} has invalid size: {}x{}", glTextureId, width, height);
            VTextureSelector.bindTexture(VTextureSelector.getWhiteTexture());
            return;
        }

        ByteBuffer pixels = MemoryUtil.memAlloc(width * height * 4);
        try {
            GL11.glGetTexImage(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, pixels);
            int glError = GL11.glGetError();
            if (glError != GL11.GL_NO_ERROR) {
                Initializer.LOGGER.error("[GLTextureCache] GL error {} reading texture {} ({}x{})",
                        glError, glTextureId, width, height);
                VTextureSelector.bindTexture(VTextureSelector.getWhiteTexture());
                return;
            }

            boolean allZero = isBufferEmpty(pixels, Math.min(width * 4, pixels.remaining()));
            if (allZero) {
                emptyTextures.add(glTextureId);
                if (syncCount < 5) {
                    Initializer.LOGGER.warn("[GLTextureCache] Texture {} ({}x{}) appears empty — will retry",
                            glTextureId, width, height);
                }
            }

            // Read GL texture parameters to match Vulkan sampler settings
            int wrapS = GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S);
            int magFilter = GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER);
            // GL_REPEAT = 0x2901, GL_CLAMP = 0x2900, GL_CLAMP_TO_EDGE = 0x812F
            boolean clamp = (wrapS != GL11.GL_REPEAT);
            // GL_LINEAR = 0x2601, GL_NEAREST = 0x2600
            boolean blur = (magFilter == GL11.GL_LINEAR);

            // Mirror whatever mip chain GL already holds.
            //
            // This used to be "mipmap only fully opaque textures", which excluded the block atlas
            // because of grass, leaves and glass -- and an unmipmapped 512x512 atlas is both the
            // shimmering over-sharp look on distant terrain and a serious bandwidth problem,
            // since every distant fragment samples a texel far from its neighbour's and the
            // texture cache never hits. Minecraft generates alpha-aware mip levels itself, so
            // copying its levels gives vanilla's appearance and vanilla's sampling behaviour.
            // Probe how many levels GL actually holds. GL_TEXTURE_MAX_LEVEL only states how many
            // are *allowed*: Minecraft sets it from the mipmap option even for textures it never
            // fills. Creating the Vulkan image from that count left the missing levels
            // uninitialised, and sampling them produced a black checkerboard over distant terrain.
            int maxLevel = GL11.glGetTexParameteri(GL11.GL_TEXTURE_2D, GL12.GL_TEXTURE_MAX_LEVEL);
            int levelCap = Math.max(1, Math.min(maxLevel + 1, VulkanImage.calculateMipLevels(width, height)));
            if (Boolean.getBoolean("vulkanmod.nomipmaps")) levelCap = 1;
            int levelCount = 1;
            for (int level = 1; level < levelCap; level++) {
                int lw = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, level, GL11.GL_TEXTURE_WIDTH);
                int lh = GL11.glGetTexLevelParameteri(GL11.GL_TEXTURE_2D, level, GL11.GL_TEXTURE_HEIGHT);
                int expectedW = Math.max(1, width >> level), expectedH = Math.max(1, height >> level);
                if (lw != expectedW || lh != expectedH) break;
                levelCount = level + 1;
            }

            VulkanImage vkImage;
            if (levelCount > 1) {
                vkImage = VulkanImage.createWithMipLevels(levelCount, width, height, 4, blur, clamp);
                pixels.position(0);
                dumpLevel(glTextureId, 0, width, height, pixels);
                vkImage.uploadMipLevel(0, width, height, pixels);

                ByteBuffer levelBuf = null;
                try {
                    for (int level = 1; level < levelCount; level++) {
                        int lw = Math.max(1, width >> level), lh = Math.max(1, height >> level);
                        int needed = lw * lh * 4;
                        if (levelBuf == null || levelBuf.capacity() < needed) {
                            if (levelBuf != null) MemoryUtil.memFree(levelBuf);
                            levelBuf = MemoryUtil.memAlloc(needed);
                        }
                        levelBuf.clear();
                        GL11.glGetTexImage(GL11.GL_TEXTURE_2D, level, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, levelBuf);
                        levelBuf.position(0);
                        dumpLevel(glTextureId, level, lw, lh, levelBuf);
                        vkImage.uploadMipLevel(level, lw, lh, levelBuf);
                    }
                } finally {
                    if (levelBuf != null) MemoryUtil.memFree(levelBuf);
                }
            } else {
                vkImage = new VulkanImage(width, height, 4, blur, clamp, pixels);
            }

            cache.put(glTextureId, vkImage);
            lastSyncedGlId = glTextureId;
            lastSyncedImage = vkImage;
            VTextureSelector.bindTexture(vkImage);

            syncCount++;
            if (syncCount <= 30) {
                Initializer.LOGGER.info("[GLTextureCache] Cached GL texture {} -> VulkanImage ({}x{}, {} mip levels){}",
                        glTextureId, width, height, levelCount, allZero ? " [EMPTY]" : "");
            }
        } catch (Exception e) {
            Initializer.LOGGER.error("[GLTextureCache] Failed to sync texture {}", glTextureId, e);
            VTextureSelector.bindTexture(VTextureSelector.getWhiteTexture());
        } finally {
            MemoryUtil.memFree(pixels);
        }
    }


    private static boolean isBufferEmpty(ByteBuffer buffer, int checkBytes) {
        for (int i = 0; i < checkBytes; i++) {
            if (buffer.get(i) != 0) {
                return false;
            }
        }
        return true;
    }

    public static void handleResize() {
        invalidateAll();
    }

    public static void invalidate(int glTextureId) {
        VulkanImage removed = cache.remove(glTextureId);
        emptyTextures.remove(glTextureId);
        if (removed != null) {
            Initializer.LOGGER.debug("[GLTextureCache] Invalidated texture {}", glTextureId);
        }
        if (lastSyncedGlId == glTextureId) {
            lastSyncedGlId = -1;
            lastSyncedImage = null;
        }
        if (removed != null) net.vulkanmod.vulkan.Pipeline.requestDescriptorInvalidation();
    }

    public static void invalidateAll() {
        Initializer.LOGGER.info("[GLTextureCache] Invalidating all {} cached textures", cache.size());
        cache.clear();
        emptyTextures.clear();
        lastSyncedGlId = -1;
        lastSyncedImage = null;
        net.vulkanmod.vulkan.Pipeline.requestDescriptorInvalidation();
    }
}
