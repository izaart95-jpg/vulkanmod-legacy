package net.vulkanmod.mixin.render;

import com.mojang.blaze3d.platform.GlStateManager;
import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.Drawer;
import net.vulkanmod.vulkan.Pipeline;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.texture.GLTextureCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.FloatBuffer;

@Mixin(GlStateManager.class)
public class GlStateManagerMixin {

    // Blend, depth, cull, colour and colour-mask state is mirrored into VRenderSystem and the
    // original call is cancelled, so the OpenGL driver never sees it. Nothing reads any of it
    // back: the readbacks this mod still performs are the modelview and projection matrices,
    // the fog colour and texture parameters, so those hooks deliberately stay as @At("RETURN")
    // and let the real call through.

    // === Blend State ===

    @Inject(method = "enableBlend", at = @At("HEAD"), cancellable = true)
    private static void onEnableBlend(CallbackInfo ci) {
        if (!Initializer.vulkanReady) return;
        Drawer.currentBlendState = Pipeline.DEFAULT_BLEND_STATE;
        ci.cancel();
    }

    @Inject(method = "disableBlend", at = @At("HEAD"), cancellable = true)
    private static void onDisableBlend(CallbackInfo ci) {
        if (!Initializer.vulkanReady) return;
        Drawer.currentBlendState = Pipeline.NO_BLEND_STATE;
        ci.cancel();
    }

    @Inject(method = "blendFunc", at = @At("HEAD"), cancellable = true)
    private static void onBlendFunc(int src, int dst, CallbackInfo ci) {
        if (!Initializer.vulkanReady) return;
        Drawer.currentBlendState = Pipeline.getOrCreateBlendState(src, dst, src, dst);
        ci.cancel();
    }

    @Inject(method = "blendFuncSeparate", at = @At("HEAD"), cancellable = true)
    private static void onBlendFuncSeparate(int srcRgb, int dstRgb, int srcAlpha, int dstAlpha, CallbackInfo ci) {
        if (!Initializer.vulkanReady) return;
        Drawer.currentBlendState = Pipeline.getOrCreateBlendState(srcRgb, dstRgb, srcAlpha, dstAlpha);
        ci.cancel();
    }

    // === Depth State ===

    @Inject(method = "enableDepthTest", at = @At("HEAD"), cancellable = true)
    private static void onEnableDepth(CallbackInfo ci) {
        if (!Initializer.vulkanReady) return;
        VRenderSystem.enableDepthTest();
        ci.cancel();
    }

    @Inject(method = "disableDepthTest", at = @At("HEAD"), cancellable = true)
    private static void onDisableDepth(CallbackInfo ci) {
        if (!Initializer.vulkanReady) return;
        VRenderSystem.disableDepthTest();
        ci.cancel();
    }

    @Inject(method = "depthFunc", at = @At("HEAD"), cancellable = true)
    private static void onDepthFunc(int func, CallbackInfo ci) {
        if (!Initializer.vulkanReady) return;
        VRenderSystem.depthFunc(func);
        ci.cancel();
    }

    @Inject(method = "depthMask", at = @At("HEAD"), cancellable = true)
    private static void onDepthMask(boolean mask, CallbackInfo ci) {
        if (!Initializer.vulkanReady) return;
        VRenderSystem.depthMask(mask);
        ci.cancel();
    }

    // === Cull State ===

    @Inject(method = "enableCull", at = @At("HEAD"), cancellable = true)
    private static void onEnableCull(CallbackInfo ci) {
        if (!Initializer.vulkanReady) return;
        VRenderSystem.enableCull();
        ci.cancel();
    }

    @Inject(method = "disableCull", at = @At("HEAD"), cancellable = true)
    private static void onDisableCull(CallbackInfo ci) {
        if (!Initializer.vulkanReady) return;
        VRenderSystem.disableCull();
        ci.cancel();
    }

    // === Color ===

    @Inject(method = "color4f", at = @At("HEAD"), cancellable = true)
    private static void onColor4f(float r, float g, float b, float a, CallbackInfo ci) {
        if (!Initializer.vulkanReady) return;
        VRenderSystem.setShaderColor(r, g, b, a);
        ci.cancel();
    }

    @Inject(method = "color3f", at = @At("HEAD"), cancellable = true)
    private static void onColor3f(float r, float g, float b, CallbackInfo ci) {
        if (!Initializer.vulkanReady) return;
        VRenderSystem.setShaderColor(r, g, b, 1.0f);
        ci.cancel();
    }

    // === Color Mask ===

    @Inject(method = "colorMask", at = @At("HEAD"), cancellable = true)
    private static void onColorMask(boolean r, boolean g, boolean b, boolean a, CallbackInfo ci) {
        if (!Initializer.vulkanReady) return;
        VRenderSystem.colorMask(r, g, b, a);
        ci.cancel();
    }

    // === Clear ===

    // Once Vulkan owns the frame the default GL framebuffer is never presented, and the world
    // and GUI framebuffers it clears are never sampled. Minecraft still issues two or three
    // full-framebuffer glClear calls per frame (MinecraftClient.runGameLoop,
    // GameRenderer.renderWorld and setupHudMatrixMode), which on gl4es means translating and
    // queuing work the driver then throws away. Mirror the state into VRenderSystem, record the
    // equivalent vkCmdClearAttachments, and skip the GL call entirely.
    //
    // Before vulkanReady the calls still run: the loading screen is drawn through real GL.
    // When no Vulkan render pass is active clearAttachments() is a no-op, and the next render
    // pass begins with LOAD_OP_CLEAR, so skipping the GL clear cannot leave stale pixels.

    @Inject(method = "clearColor(FFFF)V", at = @At("HEAD"), cancellable = true)
    private static void onClearColor(float r, float g, float b, float a, CallbackInfo ci) {
        if (!Initializer.vulkanReady) return;
        VRenderSystem.clearColor(r, g, b, a);
        ci.cancel();
    }

    @Inject(method = "clearDepth(D)V", at = @At("HEAD"), cancellable = true)
    private static void onClearDepth(double depth, CallbackInfo ci) {
        if (!Initializer.vulkanReady) return;
        VRenderSystem.clearDepth = (float) depth;
        ci.cancel();
    }

    @Inject(method = "clear", at = @At("HEAD"), cancellable = true)
    private static void onClear(int mask, CallbackInfo ci) {
        if (!Initializer.vulkanReady) return;
        try {
            VRenderSystem.clear(mask);
        } catch (Exception e) {
            // Render pass may not be active yet
        }
        ci.cancel();
    }

    // === Viewport ===

    @Inject(method = "viewPort", at = @At("RETURN"))
    private static void onViewport(int x, int y, int w, int h, CallbackInfo ci) {
        if (!Initializer.vulkanReady) return;
        Drawer drawer = Drawer.getInstance();
        if (drawer != null && drawer.isRenderPassActive()) {
            Drawer.setViewport(x, y, w, h);
        }
    }

    // === Polygon Offset ===

    @Inject(method = "polygonOffset", at = @At("RETURN"))
    private static void onPolygonOffset(float factor, float units, CallbackInfo ci) {
        if (!Initializer.vulkanReady) return;
        VRenderSystem.polygonOffset(factor, units);
    }

    @Inject(method = "enablePolyOffset", at = @At("RETURN"))
    private static void onEnablePolyOffset(CallbackInfo ci) {
        if (!Initializer.vulkanReady) return;
        try {
            VRenderSystem.enablePolygonOffset();
        } catch (Exception e) {
            // May fail if render pass not active
        }
    }

    @Inject(method = "disablePolyOffset", at = @At("RETURN"))
    private static void onDisablePolyOffset(CallbackInfo ci) {
        if (!Initializer.vulkanReady) return;
        try {
            VRenderSystem.disablePolygonOffset();
        } catch (Exception e) {
            // May fail if render pass not active
        }
    }

    // === Logic Op ===

    @Inject(method = "enableColorLogic", at = @At("RETURN"))
    private static void onEnableColorLogic(CallbackInfo ci) {
        if (!Initializer.vulkanReady) return;
        VRenderSystem.enableColorLogicOp();
    }

    @Inject(method = "disableColorLogic", at = @At("RETURN"))
    private static void onDisableColorLogic(CallbackInfo ci) {
        if (!Initializer.vulkanReady) return;
        VRenderSystem.disableColorLogicOp();
    }

    @Inject(method = "logicOp", at = @At("RETURN"))
    private static void onLogicOp(int op, CallbackInfo ci) {
        if (!Initializer.vulkanReady) return;
        VRenderSystem.logicOp(op);
    }

    // === Matrix Stack (eliminates glGetFloatv GPU-CPU sync) ===
    //
    // These hooks deliberately do NOT check Initializer.vulkanReady before mirroring, unlike
    // every other hook in this class. The software matrix stack has to shadow GL's from the very
    // first call, because what matters is not just the current matrix but the *depth* of the
    // stack: vulkanReady only flips after initializeGame has already rendered the loading screen
    // and the resource reload, and any pushMatrix that happened before the flip whose popMatrix
    // lands after it leaves our stack permanently offset from GL's. From then on every pop
    // restores a matrix saved at the wrong nesting level.
    //
    // The mirror is the source of truth once the renderer is up, so the GL entry points are
    // cancelled outright. On Android each of these crosses gl4es; entity parts and GUI widgets
    // alone issue hundreds of them per frame. Before vulkanReady the real call still runs so the
    // loading screen keeps rendering.
    //
    // Everything that mutates a matrix goes through GlStateManager in 1.8.9. The one exception,
    // Project.gluPerspective's direct glMultMatrixf, now calls GlStateManager.multMatrix too.

    /** Shared tail: mirror first, then skip the GL call once the renderer owns the state. */
    private static void vulkanmodMatrixHook(CallbackInfo ci) {
        if (Initializer.vulkanReady) ci.cancel();
    }

    @Inject(method = "matrixMode", at = @At("HEAD"), cancellable = true)
    private static void onMatrixMode(int mode, CallbackInfo ci) {
        VRenderSystem.matrixMode(mode);
        vulkanmodMatrixHook(ci);
    }

    @Inject(method = "pushMatrix", at = @At("HEAD"), cancellable = true)
    private static void onPushMatrix(CallbackInfo ci) {
        VRenderSystem.pushMatrix();
        vulkanmodMatrixHook(ci);
    }

    @Inject(method = "popMatrix", at = @At("HEAD"), cancellable = true)
    private static void onPopMatrix(CallbackInfo ci) {
        VRenderSystem.popMatrix();
        vulkanmodMatrixHook(ci);
    }

    @Inject(method = "loadIdentity", at = @At("HEAD"), cancellable = true)
    private static void onLoadIdentity(CallbackInfo ci) {
        VRenderSystem.loadIdentity();
        vulkanmodMatrixHook(ci);
    }

    @Inject(method = "translatef", at = @At("HEAD"), cancellable = true)
    private static void onTranslateF(float x, float y, float z, CallbackInfo ci) {
        VRenderSystem.translatef(x, y, z);
        vulkanmodMatrixHook(ci);
    }

    @Inject(method = "translated", at = @At("HEAD"), cancellable = true)
    private static void onTranslateD(double x, double y, double z, CallbackInfo ci) {
        VRenderSystem.translatef((float) x, (float) y, (float) z);
        vulkanmodMatrixHook(ci);
    }

    @Inject(method = "rotatef", at = @At("HEAD"), cancellable = true)
    private static void onRotate(float angle, float x, float y, float z, CallbackInfo ci) {
        VRenderSystem.rotatef(angle, x, y, z);
        vulkanmodMatrixHook(ci);
    }

    @Inject(method = "scalef", at = @At("HEAD"), cancellable = true)
    private static void onScaleF(float x, float y, float z, CallbackInfo ci) {
        VRenderSystem.scalef(x, y, z);
        vulkanmodMatrixHook(ci);
    }

    @Inject(method = "scaled", at = @At("HEAD"), cancellable = true)
    private static void onScaleD(double x, double y, double z, CallbackInfo ci) {
        VRenderSystem.scalef((float) x, (float) y, (float) z);
        vulkanmodMatrixHook(ci);
    }

    @Inject(method = "ortho", at = @At("HEAD"), cancellable = true)
    private static void onOrtho(double l, double r, double b, double t, double n, double f, CallbackInfo ci) {
        VRenderSystem.ortho(l, r, b, t, n, f);
        vulkanmodMatrixHook(ci);
    }

    @Inject(method = "multiMatrix", at = @At("HEAD"), cancellable = true)
    private static void onMultiMatrix(FloatBuffer fb, CallbackInfo ci) {
        fb.rewind();
        VRenderSystem.multMatrix(fb);
        vulkanmodMatrixHook(ci);
    }

    /**
     * Serves the matrix getters from the software stack.
     *
     * <p>{@code Frustum.start} alone reads both matrices once per frame, and every terrain update
     * (which includes pure camera rotation) constructs and starts a captured frustum as well.
     * {@code Camera.update} reads them too, and {@code BuiltChunk} does so in the non-batched
     * fallback. With the GL matrix calls cancelled these reads would see stale state, so they
     * come from the same matrices the renderer uses.
     */
    @Inject(method = "getFloat", at = @At("HEAD"), cancellable = true)
    private static void onGetFloat(int mode, FloatBuffer buffer, CallbackInfo ci) {
        if (!Initializer.vulkanReady || !VRenderSystem.isSoftwareStackTrusted()) return;

        if (mode == 2982) { // GL_MODELVIEW_MATRIX
            VRenderSystem.writeModelView(buffer);
            ci.cancel();
        } else if (mode == 2983) { // GL_PROJECTION_MATRIX
            VRenderSystem.writeProjection(buffer);
            ci.cancel();
        }
    }

    // === Fog ===
    //
    // Read from the arguments instead of querying GL back for GL_FOG_START / GL_FOG_END /
    // GL_FOG_COLOR once per render layer.

    @Inject(method = "fogStart", at = @At("RETURN"))
    private static void onFogStart(float start, CallbackInfo ci) {
        VRenderSystem.setFogStart(start);
    }

    @Inject(method = "fogEnd", at = @At("RETURN"))
    private static void onFogEnd(float end, CallbackInfo ci) {
        VRenderSystem.setFogEnd(end);
    }

    // === Texture Tracking (eliminates glGetInteger) ===

    @Inject(method = "activeTexture", at = @At("RETURN"))
    private static void onActiveTexture(int texture, CallbackInfo ci) {
        if (!Initializer.vulkanReady) return;
        VRenderSystem.setActiveTextureUnit(texture);
    }

    @Inject(method = "bindTexture", at = @At("RETURN"))
    private static void onBindTexture(int id, CallbackInfo ci) {
        if (!Initializer.vulkanReady) return;
        VRenderSystem.trackBoundTexture(id);
    }

    // Invalidate cached VulkanImage when GL texture is deleted
    @Inject(method = "deleteTexture", at = @At("RETURN"))
    private static void onDeleteTexture(int id, CallbackInfo ci) {
        if (!Initializer.vulkanReady) return;
        GLTextureCache.invalidate(id);
    }
}
