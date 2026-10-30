package net.vulkanmod.vulkan.texture;

import java.util.BitSet;

/**
 * Suppresses animation uploads for sprites nothing drew this frame.
 *
 * <p>Minecraft ticks every animated sprite -- lava, water, fire, portals, and every animated
 * texture a resource pack adds -- whether or not any of them is on screen. Each tick becomes a
 * Vulkan transfer here, so underground or facing a wall the mod was still pushing frames for
 * animations nobody could see.
 *
 * <p>Visibility is tracked per atlas region rather than per sprite: a sprite counts as rendered
 * when a draw sampled the atlas it lives in and the region it occupies falls inside the area the
 * frame actually touched. That is coarser than OptiFine's per-sprite BitSet, which is fed from
 * inside the chunk builder, but it needs no changes to Minecraft's meshing and errs the safe way
 * -- an unsure sprite is treated as visible and still uploaded.
 */
public class SmartAnimations {

    /** -Dvulkanmod.smartanimations=false uploads every animation frame, as vanilla does. */
    public static final boolean ENABLED =
            !"false".equalsIgnoreCase(System.getProperty("vulkanmod.smartanimations"));

    /** Atlases sampled during the current frame, by GL texture id. */
    private static final BitSet atlasesUsed = new BitSet();
    private static final BitSet atlasesUsedPrev = new BitSet();

    private static boolean worldRendered;

    public static void beginFrame() {
        atlasesUsedPrev.clear();
        atlasesUsedPrev.or(atlasesUsed);
        atlasesUsed.clear();
        worldRendered = false;
    }

    /** Called when a draw binds an atlas, so its animations count as visible. */
    public static void markUsed(int glTextureId) {
        if (glTextureId > 0 && glTextureId < 4096) atlasesUsed.set(glTextureId);
    }

    public static void markWorldRendered() {
        worldRendered = true;
    }

    /**
     * @return true when frames for this texture should still be pushed to the GPU.
     *         Uses the previous frame's set, because animations tick before the frame that will
     *         display them is drawn.
     */
    public static boolean shouldUpload(int glTextureId) {
        if (!ENABLED) return true;
        // Before the world has been drawn once there is nothing to go on; upload.
        if (!worldRendered && atlasesUsedPrev.isEmpty()) return true;
        if (glTextureId <= 0 || glTextureId >= 4096) return true;
        return atlasesUsedPrev.get(glTextureId) || atlasesUsed.get(glTextureId);
    }

    public static int usedCount() {
        return atlasesUsedPrev.cardinality();
    }
}
