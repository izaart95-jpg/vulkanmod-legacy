package net.vulkanmod.mixin.render;

import net.minecraft.client.render.CameraView;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.WorldRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.FrustumCuller;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.render.ChunkInfoQueue;
import net.vulkanmod.vulkan.render.EntityInstanceRenderer;
import net.vulkanmod.vulkan.render.OcclusionCache;
import com.mojang.blaze3d.platform.GLX;
import org.joml.Matrix4f;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;

@Mixin(WorldRenderer.class)
public class WorldRendererMixin {
    // Reuse for frustum MVP calculation — avoids new Matrix4f() per frame
    private static final Matrix4f frustumMvpTemp = new Matrix4f();

    @Shadow private int renderDistance;

    /**
     * The rebuilt render list. Vanilla replaces it with a fresh {@code ArrayList} on every
     * terrain update, i.e. every time the camera moves or turns; the list is only consumed
     * synchronously during the same frame, so the same instance can be cleared and refilled.
     */
    @Shadow private List visibleChunks;

    /** Reused BFS queue; the traversal below drains it completely, so resetting is enough. */
    @Unique private static final ChunkInfoQueue vm$bfsQueue = new ChunkInfoQueue();

    // setupTerrain runs AFTER GameRenderer.orientCamera() which uses direct GL11
    // calls (glRotatef, glTranslated) for camera transforms. Those bypass
    // GlStateManager so our software stack is stale. Sync modelview from GL here
    // once — all subsequent GlStateManager ops will correctly build on top.
    @Inject(method = "setupTerrain", at = @At("HEAD"))
    private void onSetupTerrain(Entity camera, double tickDelta, CameraView frustum, int frame, boolean spectator, CallbackInfo ci) {
        if (!Initializer.vulkanReady) return;

        // Sync modelview from GL only when the software stack cannot be trusted. Every camera
        // transform in 1.8.9 goes through GlStateManager, whose hooks mirror the operation here;
        // the direct GL11 calls that remain are projection setup (gluPerspective), not modelview.
        // The old unconditional glGetFloatv was two driver round-trips per frame, and on Android
        // every one of those crosses gl4es.
        if (!VRenderSystem.isSoftwareStackTrusted() || VRenderSystem.isMatrixDebug()) {
            VRenderSystem.syncModelViewFromGL();
        }

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
        if (!VRenderSystem.isSoftwareStackTrusted() || VRenderSystem.isMatrixDebug()) {
            VRenderSystem.syncModelViewFromGL();
        }
    }

    @Inject(method = "renderLayer", at = @At("HEAD"))
    private void onRenderLayer(RenderLayer layer, double tickDelta, int pass, Entity camera, CallbackInfoReturnable<Integer> cir) {
        if (!Initializer.vulkanReady) return;
        FrustumCuller.setRenderDistance(this.renderDistance);

        // Sync fog color from GL state — MC's setupFog() has already run by this point,
        // so GL_FOG_COLOR matches the sky color for this tick
        VRenderSystem.syncFogFromGL();
    }

    // ---------------------------------------------------------------------------------------
    // Fixed-function client arrays are not used by any draw any more: VertexBuffer.draw and
    // Tessellator.draw are both intercepted and emitted through Vulkan pipelines. Vanilla still
    // enables and disables the block-format client arrays around every render layer, plus the
    // second texture unit, which is five GL calls per layer (twenty per frame) that only cost
    // a gl4es round trip on Android. Leave the real calls in place when Vulkan did not start.
    // ---------------------------------------------------------------------------------------

    @Redirect(method = "renderLayer(Lnet/minecraft/client/render/RenderLayer;)V",
            at = @At(value = "INVOKE",
                     target = "Lorg/lwjgl/opengl/GL11;glEnableClientState(I)V"))
    private void vulkanmodSkipEnableClientState(int cap) {
        if (!Initializer.vulkanReady) GL11.glEnableClientState(cap);
    }

    @Redirect(method = "renderLayer(Lnet/minecraft/client/render/RenderLayer;)V",
            at = @At(value = "INVOKE",
                     target = "Lorg/lwjgl/opengl/GL11;glDisableClientState(I)V"))
    private void vulkanmodSkipDisableClientState(int cap) {
        if (!Initializer.vulkanReady) GL11.glDisableClientState(cap);
    }

    @Redirect(method = "renderLayer(Lnet/minecraft/client/render/RenderLayer;)V",
            at = @At(value = "INVOKE",
                     target = "Lcom/mojang/blaze3d/platform/GLX;gl13ClientActiveTexture(I)V"))
    private void vulkanmodSkipClientActiveTexture(int unit) {
        if (!Initializer.vulkanReady) GLX.gl13ClientActiveTexture(unit);
    }

    // ---------------------------------------------------------------------------------------
    // Terrain visibility graph. getOpenChunkFaces flood-fills the 16x16x16 section containing
    // the camera every time the camera moves -- including pure rotation, because setupTerrain
    // treats a yaw change as a terrain update. Walking through open air visits thousands of
    // cells per frame. The answer only depends on the blocks in that section and the camera's
    // block position, so cache it and let ChunkSection writes invalidate the entry.
    // ---------------------------------------------------------------------------------------

    @Unique private static long vm$openFacesKey = Long.MIN_VALUE;
    @Unique private static int vm$openFacesVersion = Integer.MIN_VALUE;
    @Unique private static int vm$openFacesMask;

    @Inject(method = "getOpenChunkFaces", at = @At("HEAD"), cancellable = true)
    private void vulkanmod$reuseOpenFaces(BlockPos pos, CallbackInfoReturnable<Set<Direction>> cir) {
        if (!Initializer.vulkanReady) return;
        if (pos.asLong() != vm$openFacesKey) return;
        if (OcclusionCache.version() != vm$openFacesVersion) return;

        // Return a copy: the caller removes the facing direction from the set.
        EnumSet<Direction> faces = EnumSet.noneOf(Direction.class);
        int mask = vm$openFacesMask;
        for (Direction direction : OcclusionCache.DIRECTION_VALUES) {
            if ((mask & (1 << direction.ordinal())) != 0) faces.add(direction);
        }
        cir.setReturnValue(faces);
    }

    @Inject(method = "getOpenChunkFaces", at = @At("RETURN"))
    private void vulkanmod$rememberOpenFaces(BlockPos pos, CallbackInfoReturnable<Set<Direction>> cir) {
        if (!Initializer.vulkanReady) return;
        int mask = 0;
        for (Direction direction : cir.getReturnValue()) {
            mask |= 1 << direction.ordinal();
        }
        vm$openFacesKey = pos.asLong();
        vm$openFacesVersion = OcclusionCache.version();
        vm$openFacesMask = mask;
    }

    /** Direction.values() clones a six-element array per call; the BFS calls it per section. */
    @Redirect(method = "setupTerrain",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/util/math/Direction;values()[Lnet/minecraft/util/math/Direction;"))
    private Direction[] vulkanmod$cachedDirections() {
        return OcclusionCache.DIRECTION_VALUES;
    }

    // ---------------------------------------------------------------------------------------
    // Render-list allocation. Both containers below were allocated fresh on every terrain
    // rebuild -- which is every frame while the camera is moving or turning. They live for the
    // duration of one rebuild only, so clearing the previous contents is equivalent and keeps
    // several thousand short-lived objects per second out of the young generation.
    // ---------------------------------------------------------------------------------------

    @Redirect(method = "setupTerrain",
            at = @At(value = "INVOKE",
                     target = "Lcom/google/common/collect/Lists;newArrayList()Ljava/util/ArrayList;"))
    private ArrayList<?> vulkanmod$reuseVisibleChunks() {
        ArrayList<?> list = (ArrayList<?>) this.visibleChunks;
        list.clear();
        return list;
    }

    @Redirect(method = "setupTerrain",
            at = @At(value = "INVOKE",
                     target = "Lcom/google/common/collect/Lists;newLinkedList()Ljava/util/LinkedList;"))
    private LinkedList<?> vulkanmod$reuseBfsQueue() {
        vm$bfsQueue.reset();
        return vm$bfsQueue;
    }
}
