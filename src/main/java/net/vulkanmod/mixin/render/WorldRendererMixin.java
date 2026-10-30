package net.vulkanmod.mixin.render;

import net.minecraft.client.render.CameraView;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.entity.Entity;
import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.FrustumCuller;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.render.EntityInstanceRenderer;
import org.joml.Matrix4f;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(WorldRenderer.class)
public class WorldRendererMixin {
    // Reuse for frustum MVP calculation — avoids new Matrix4f() per frame
    private static final Matrix4f frustumMvpTemp = new Matrix4f();

    @Shadow private int renderDistance;

    // setupTerrain runs AFTER GameRenderer.orientCamera() which uses direct GL11
    // calls (glRotatef, glTranslated) for camera transforms. Those bypass
    // GlStateManager so our software stack is stale. Sync modelview from GL here
    // once — all subsequent GlStateManager ops will correctly build on top.
    @Inject(method = "setupTerrain", at = @At("HEAD"))
    private void onSetupTerrain(Entity camera, double tickDelta, CameraView frustum, int frame, boolean spectator, CallbackInfo ci) {
        if (!Initializer.vulkanReady) return;

        // Sync modelview from GL (catches orientCamera's direct GL calls)
        VRenderSystem.syncModelViewFromGL();

        // Save camera-only modelview inverse for per-chunk offset extraction
        VRenderSystem.saveCameraModelView();

        // Update render distance for culling
        FrustumCuller.setRenderDistance(this.renderDistance);

        // Fog distance from render distance — fog masks terrain pop-in
        VRenderSystem.updateFogFromRenderDistance(this.renderDistance * 16);

        // Update camera position (interpolated)
        double x = camera.prevX + (camera.x - camera.prevX) * tickDelta;
        double y = camera.prevY + (camera.y - camera.prevY) * tickDelta;
        double z = camera.prevZ + (camera.z - camera.prevZ) * tickDelta;
        FrustumCuller.setCameraPosition(x, y, z);

        // Begin occlusion culling frame (swap prev/current maps)
        FrustumCuller.beginFrame();
        net.vulkanmod.vulkan.texture.SmartAnimations.markWorldRendered();

        // Update frustum planes from current matrices — reuse static temp
        try {
            Matrix4f proj = VRenderSystem.getCurrentProjection();
            Matrix4f mv = VRenderSystem.getCurrentModelView();
            if (proj != null && mv != null) {
                proj.mul(mv, frustumMvpTemp);
                FrustumCuller.updateFrustum(frustumMvpTemp);
            }
        } catch (Exception e) {
            // Frustum update may fail early in init
        }

        // Begin entity instance collection for this frame
        EntityInstanceRenderer.getInstance().beginFrame();

    }

    // renderEntities also runs after camera setup — sync again in case
    // something changed between setupTerrain and renderEntities.
    @Inject(method = "renderEntities", at = @At("HEAD"))
    private void onRenderEntities(Entity camera, CameraView frustum, float tickDelta, CallbackInfo ci) {
        if (!Initializer.vulkanReady) return;
        VRenderSystem.syncModelViewFromGL();
    }

    @Inject(method = "renderLayer", at = @At("HEAD"))
    private void onRenderLayer(RenderLayer layer, double tickDelta, int pass, Entity camera, CallbackInfoReturnable<Integer> cir) {
        if (!Initializer.vulkanReady) return;
        FrustumCuller.setRenderDistance(this.renderDistance);

        // Sync fog color from GL state — MC's setupFog() has already run by this point,
        // so GL_FOG_COLOR matches the sky color for this tick
        VRenderSystem.syncFogFromGL();
    }
}
