package net.vulkanmod.mixin.perf;

import com.mojang.blaze3d.platform.GlStateManager;
import net.minecraft.client.render.entity.EntityRenderDispatcher;
import net.minecraft.client.render.entity.EntityRenderer;
import net.minecraft.client.render.entity.ItemEntityRenderer;
import net.minecraft.entity.ItemEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Makes dropped items always face the camera (billboard).
 * Redirects the Y-axis rotation in method_10221 (the per-item render method)
 * to use the camera's yaw instead of the item's age-based spinning rotation.
 * This makes 2D items always show their face and 3D items their front.
 */
@Mixin(ItemEntityRenderer.class)
public abstract class ItemEntityRendererMixin extends EntityRenderer<ItemEntity> {

    protected ItemEntityRendererMixin(EntityRenderDispatcher dispatcher) {
        super(dispatcher);
    }

    @Redirect(method = "method_10221",
              at = @At(value = "INVOKE",
                       target = "Lcom/mojang/blaze3d/platform/GlStateManager;rotatef(FFFF)V"))
    private void billboardRotation(float angle, float x, float y, float z) {
        // Replace item spin rotation with camera-facing rotation
        GlStateManager.rotatef(180.0f - this.dispatcher.yaw, 0.0f, 1.0f, 0.0f);
    }
}
