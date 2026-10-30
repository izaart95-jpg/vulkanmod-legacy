package net.vulkanmod.mixin.feature;

import net.minecraft.client.render.item.HeldItemRenderer;
import net.vulkanmod.config.VulkanModConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Drops the flames drawn across the screen while burning.
 *
 * <p>The overlay covers most of the viewport with an animated texture and hides what is in front
 * of you, which matters more in a fight than the fire being visible; the health bar and the damage
 * tint still say you are on fire.
 */
@Mixin(HeldItemRenderer.class)
public class FireOverlayMixin {

    @Inject(method = "renderFireOverlay", at = @At("HEAD"), cancellable = true)
    private void vulkanmodFireOverlay(CallbackInfo ci) {
        if (!VulkanModConfig.FIRE_OVERLAY.value) ci.cancel();
    }
}
