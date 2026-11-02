package net.vulkanmod.mixin.render;

import net.minecraft.client.render.entity.LivingEntityRenderer;
import net.minecraft.entity.LivingEntity;
import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.VRenderSystem;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.nio.FloatBuffer;

/**
 * Restores the red hurt flash and the creeper fuse flash.
 *
 * <p>1.8.9 does not draw a second red model: {@code method_10252} reconfigures the fixed-function
 * texture environment so that the lightmap unit becomes
 * {@code GL_INTERPOLATE(constant, previous, ONE_MINUS_SRC_ALPHA)}. The constant is
 * {@code (1, 0, 0, 0.3)} for a hurt entity or white with a fuse-dependent alpha for a creeper.
 * That state is invisible to the Vulkan shader, so mobs were rendered with their normal texture
 * and no flash.
 *
 * <p>The constant Minecraft just wrote to {@code buffer} is copied into the entity shader's
 * OverlayColor uniform, and cleared again by {@code method_10260} -- the same place vanilla
 * restores the normal texture environment.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class LivingEntityRendererMixin {

    @Shadow protected FloatBuffer buffer;

    @Inject(method = "method_10252", at = @At("RETURN"))
    private void vulkanmod$captureOverlay(LivingEntity entity, float tickDelta, boolean combineTextures,
                                         CallbackInfoReturnable<Boolean> cir) {
        if (!Initializer.vulkanReady) return;
        if (!cir.getReturnValueZ()) return;

        VRenderSystem.setEntityOverlayColor(
                buffer.get(0), buffer.get(1), buffer.get(2), buffer.get(3));
    }

    @Inject(method = "method_10260", at = @At("HEAD"))
    private void vulkanmod$clearOverlay(CallbackInfo ci) {
        if (!Initializer.vulkanReady) return;
        VRenderSystem.clearEntityOverlayColor();
    }
}
