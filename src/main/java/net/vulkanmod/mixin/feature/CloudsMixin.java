package net.vulkanmod.mixin.feature;

import net.minecraft.client.render.WorldRenderer;
import net.vulkanmod.config.VulkanModConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The cloud layer switch, and the largest single saving found by sampling the client thread.
 *
 * <p>1.8.9 has no cached mesh for clouds: renderFancyClouds walks a grid of cloud cells around the
 * camera and pushes every quad through the immediate mode Tessellator, every frame. Sampling put
 * 21% of the client thread inside it -- 16% in the method and another 5% in BufferBuilder.color
 * called from it -- in a flat test world with almost no terrain to draw.
 *
 * <p>That is the shape of the problem this was chasing: the cost does not depend on how much
 * terrain is in view, so it is invisible in a heavy scene and dominant in a light one.
 */
@Mixin(WorldRenderer.class)
public class CloudsMixin {

    @Inject(method = "renderClouds", at = @At("HEAD"), cancellable = true)
    private void vulkanmodClouds(CallbackInfo ci) {
        if (!VulkanModConfig.CLOUDS.value) ci.cancel();
    }
}
