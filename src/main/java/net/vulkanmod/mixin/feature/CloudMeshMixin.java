package net.vulkanmod.mixin.feature;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.client.world.ClientWorld;
import net.vulkanmod.vulkan.render.CloudMeshRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Replaces the fancy cloud layer with a persistent mesh.
 *
 * <p>The vanilla method rebuilds the same 8x8 grid of cloud cells every frame and draws each
 * cell separately (twice, for the depth prepass). The mesh only changes when the integer texture
 * scroll or the visible face set changes, so it can be built once and animated with a matrix
 * translation and the colour modulator.
 */
@Mixin(WorldRenderer.class)
public class CloudMeshMixin {

    @Shadow private MinecraftClient client;
    @Shadow private ClientWorld world;
    @Shadow private int ticks;

    @Inject(method = "renderFancyClouds", at = @At("HEAD"), cancellable = true)
    private void vulkanmod$renderCachedClouds(float tickDelta, int anaglyphFilter, CallbackInfo ci) {
        if (CloudMeshRenderer.render(this.client, this.world, this.ticks, tickDelta, anaglyphFilter)) {
            ci.cancel();
        }
    }
}
