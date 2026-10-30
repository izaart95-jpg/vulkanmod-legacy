package net.vulkanmod.mixin.feature;

import net.minecraft.client.render.item.ItemRenderer;
import net.vulkanmod.config.VulkanModConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The enchantment sheen switch.
 *
 * <p>The glint is a second pass over the whole item model with its own texture matrix animation, so
 * turning it off removes a draw per enchanted item on screen as well as changing how they look.
 */
@Mixin(ItemRenderer.class)
public class ItemGlintMixin {

    @Inject(method = "renderGlint", at = @At("HEAD"), cancellable = true)
    private void vulkanmodGlint(CallbackInfo ci) {
        if (!VulkanModConfig.ITEM_GLINT.value) ci.cancel();
    }
}
