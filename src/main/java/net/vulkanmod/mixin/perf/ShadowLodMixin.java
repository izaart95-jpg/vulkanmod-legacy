package net.vulkanmod.mixin.perf;

import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.entity.Entity;
import net.vulkanmod.vulkan.FrustumCuller;
import net.vulkanmod.vulkan.VRenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Progressive shadow LOD — smoothly fades shadow opacity based on distance
 * from the camera. No extra passes, no extra textures, zero-copy.
 *
 * - 0..fadeStart: full shadow (no modification)
 * - fadeStart..fogEnd: linear fade opacity from 1.0 → 0.0, shadow shrinks
 * - > fogEnd: shadow skipped entirely (early return)
 *
 * Works by temporarily scaling shadowDarkness and shadowSize before the
 * shadow render, then restoring them after. This affects the per-vertex
 * alpha that method_10207 writes to shadow quads.
 */
@Mixin(EntityRenderer.class)
public abstract class ShadowLodMixin {

    @Shadow protected float shadowSize;
    @Shadow protected float shadowDarkness;

    @Unique private float savedShadowSize;
    @Unique private float savedShadowDarkness;

    // Fade starts at 40% of fog distance, fully gone at 100%
    private static final float FADE_START_RATIO = 0.4f;

    /**
     * The shadow off switch.
     *
     * <p>It goes on method_1534, the public wrapper, not on renderShadow: the wrapper is what the
     * dispatcher calls and where vanilla itself tests the entityShadows option, so cancelling here
     * also skips the setup around the shadow rather than just the shadow itself.
     */
    @Inject(method = "method_1534", at = @At("HEAD"), cancellable = true)
    private void onShadowEntry(Entity entity, double x, double y, double z,
                               float darkness, float tickDelta, CallbackInfo ci) {
        if (net.vulkanmod.vulkan.PerformanceProfiler.ENABLED) {
            net.vulkanmod.vulkan.PerformanceProfiler.shadowEntryCalls.incrementAndGet();
        }
        if (!net.vulkanmod.config.VulkanModConfig.SHADOWS.value) {
            ci.cancel();
        }
    }

    @Inject(method = "renderShadow", at = @At("HEAD"), cancellable = true)
    private void onRenderShadowHead(Entity entity, double x, double y, double z,
                                    float darkness, float tickDelta, CallbackInfo ci) {
        if (net.vulkanmod.vulkan.PerformanceProfiler.ENABLED) {
            net.vulkanmod.vulkan.PerformanceProfiler.shadowRenderCalls.incrementAndGet();
        }
        if (!net.vulkanmod.config.VulkanModConfig.SHADOWS.value) {
            ci.cancel();
            return;
        }

        if (!net.vulkanmod.config.VulkanModConfig.ENTITY_SHADOW_FADE.value) return;

        float fogEnd = VRenderSystem.getShaderFogEnd();
        if (fogEnd <= 0) return;

        // Interpolated entity position
        double ex = entity.prevX + (entity.x - entity.prevX) * tickDelta;
        double ey = entity.prevY + (entity.y - entity.prevY) * tickDelta;
        double ez = entity.prevZ + (entity.z - entity.prevZ) * tickDelta;
        double distSq = FrustumCuller.getDistanceSq(ex, ey, ez);

        float dist = (float) Math.sqrt(distSq);

        // Beyond fog — skip shadow entirely
        if (dist >= fogEnd) {
            ci.cancel();
            return;
        }

        float fadeStart = fogEnd * FADE_START_RATIO;

        if (dist > fadeStart) {
            float fade = 1.0f - (dist - fadeStart) / (fogEnd - fadeStart);

            // Save original values
            savedShadowDarkness = this.shadowDarkness;
            savedShadowSize = this.shadowSize;

            // Scale down — opacity fades linearly, size shrinks to reduce block iterations
            this.shadowDarkness *= fade;
            this.shadowSize *= (0.5f + 0.5f * fade);
        } else {
            // Mark as unmodified
            savedShadowDarkness = -1.0f;
        }
    }

    @Inject(method = "renderShadow", at = @At("RETURN"))
    private void onRenderShadowReturn(Entity entity, double x, double y, double z,
                                      float darkness, float tickDelta, CallbackInfo ci) {
        // Restore original values if we modified them
        if (savedShadowDarkness >= 0.0f) {
            this.shadowDarkness = savedShadowDarkness;
            this.shadowSize = savedShadowSize;
            savedShadowDarkness = -1.0f;
        }
    }
}
