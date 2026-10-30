package net.vulkanmod.mixin.feature;

import net.minecraft.client.render.WorldRenderer;
import net.vulkanmod.config.VulkanModConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Hides the black wireframe box around the block you are looking at. */
@Mixin(WorldRenderer.class)
public class BlockSelectionMixin {

    @Inject(method = "drawBlockOutline", at = @At("HEAD"), cancellable = true)
    private void vulkanmodBlockSelection(CallbackInfo ci) {
        if (!VulkanModConfig.BLOCK_SELECTION.value) ci.cancel();
    }
}
