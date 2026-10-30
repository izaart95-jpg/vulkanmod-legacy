package net.vulkanmod.mixin.render;

import com.mojang.blaze3d.platform.GlStateManager;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.ModelBox;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.model.ModelPart;
import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.Drawer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.List;

@Mixin(ModelPart.class)
public class ModelPartMixin {

    @Shadow public boolean visible;
    @Shadow public boolean hide;
    @Shadow public float offsetX;
    @Shadow public float offsetY;
    @Shadow public float offsetZ;
    @Shadow public float posX;
    @Shadow public float posY;
    @Shadow public float posZ;
    @Shadow public float pivotX;
    @Shadow public float pivotY;
    @Shadow public float pivotZ;
    @Shadow public List<ModelBox> cuboids;
    @Shadow public List<ModelPart> modelList;

    private static final float RAD_TO_DEG = 57.295776f;

    // Exactly mimics MC 1.8.9 ModelPart.render(float).
    // Uses GlStateManager calls which mirror to BOTH GL state AND VRenderSystem
    // software stack (via GlStateManagerMixin hooks). This means calculateMVP()
    // can use the software stack with zero glGetFloatv calls.
    @Inject(method = "render", at = @At("HEAD"), cancellable = true)
    private void vulkanRender(float scale, CallbackInfo ci) {
        if (!Initializer.vulkanReady) return;

        if (this.hide || !this.visible) {
            ci.cancel();
            return;
        }

        GlStateManager.translatef(this.offsetX, this.offsetY, this.offsetZ);

        if (this.posX == 0.0f && this.posY == 0.0f && this.posZ == 0.0f) {
            if (this.pivotX == 0.0f && this.pivotY == 0.0f && this.pivotZ == 0.0f) {
                drawCuboids(scale);
                renderChildren(scale);
            } else {
                GlStateManager.translatef(this.pivotX * scale, this.pivotY * scale, this.pivotZ * scale);
                drawCuboids(scale);
                renderChildren(scale);
                GlStateManager.translatef(-this.pivotX * scale, -this.pivotY * scale, -this.pivotZ * scale);
            }
        } else {
            GlStateManager.pushMatrix();
            GlStateManager.translatef(this.pivotX * scale, this.pivotY * scale, this.pivotZ * scale);

            if (this.posZ != 0.0f) GlStateManager.rotatef(this.posZ * RAD_TO_DEG, 0.0f, 0.0f, 1.0f);
            if (this.posY != 0.0f) GlStateManager.rotatef(this.posY * RAD_TO_DEG, 0.0f, 1.0f, 0.0f);
            if (this.posX != 0.0f) GlStateManager.rotatef(this.posX * RAD_TO_DEG, 1.0f, 0.0f, 0.0f);

            drawCuboids(scale);
            renderChildren(scale);

            GlStateManager.popMatrix();
        }

        // Undo the offset applied at the top. Minecraft's render() ends with exactly this and
        // the mixin was missing it: with no push/pop around the offset in two of the three
        // branches, every part with a non-zero offset leaked its translation into the matrix,
        // permanently and cumulatively. That was the software matrix stack's drift from GL.
        GlStateManager.translatef(-this.offsetX, -this.offsetY, -this.offsetZ);

        ci.cancel();
    }

    @Inject(method = "rotateAndRender", at = @At("HEAD"), cancellable = true)
    private void vulkanRotateAndRender(float scale, CallbackInfo ci) {
        if (!Initializer.vulkanReady) return;

        if (this.hide || !this.visible) {
            ci.cancel();
            return;
        }

        GlStateManager.pushMatrix();
        GlStateManager.translatef(this.pivotX * scale, this.pivotY * scale, this.pivotZ * scale);

        if (this.posY != 0.0f) GlStateManager.rotatef(this.posY * RAD_TO_DEG, 0.0f, 1.0f, 0.0f);
        if (this.posX != 0.0f) GlStateManager.rotatef(this.posX * RAD_TO_DEG, 1.0f, 0.0f, 0.0f);
        if (this.posZ != 0.0f) GlStateManager.rotatef(this.posZ * RAD_TO_DEG, 0.0f, 0.0f, 1.0f);

        drawCuboids(scale);

        GlStateManager.popMatrix();
        ci.cancel();
    }

    private void drawCuboids(float scale) {
        if (Initializer.vulkanReady && !Drawer.isBatching()) {
            Drawer.beginBatch();
            BufferBuilder buf = Tessellator.getInstance().getBuffer();
            for (int i = 0; i < this.cuboids.size(); i++) {
                this.cuboids.get(i).draw(buf, scale);
            }
            Drawer.getInstance().flushBatch();
        } else {
            BufferBuilder buf = Tessellator.getInstance().getBuffer();
            for (int i = 0; i < this.cuboids.size(); i++) {
                this.cuboids.get(i).draw(buf, scale);
            }
        }
    }

    private void renderChildren(float scale) {
        if (this.modelList != null) {
            for (int i = 0; i < this.modelList.size(); i++) {
                this.modelList.get(i).render(scale);
            }
        }
    }
}
