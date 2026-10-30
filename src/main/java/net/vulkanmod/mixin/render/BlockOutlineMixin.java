package net.vulkanmod.mixin.render;

import net.minecraft.client.render.WorldRenderer;
import net.vulkanmod.vulkan.VRenderSystem;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Captures the line width Minecraft sets for the block selection outline.
 *
 * <p>drawBlockOutline calls GL11.glLineWidth directly rather than going through GlStateManager,
 * so there is no state hook to piggyback on. Vulkan takes the width as dynamic state on the line
 * pipeline, and without this it would always draw at 1.0 and look thinner than vanilla.
 */
@Mixin(WorldRenderer.class)
public class BlockOutlineMixin {

    @Redirect(method = "drawBlockOutline",
              at = @At(value = "INVOKE", target = "Lorg/lwjgl/opengl/GL11;glLineWidth(F)V"))
    private void captureLineWidth(float width) {
        VRenderSystem.lineWidth = width;
        GL11.glLineWidth(width);
    }
}
