package net.vulkanmod.mixin.render;

import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.vulkanmod.Initializer;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the main menu panorama over the whole window instead of a square in the corner.
 *
 * <p>Vanilla renders the panorama at a 256x256 viewport, copies the result into a texture with
 * glCopyTexSubImage2D, blurs it by drawing that texture back over itself seven times, and finally
 * stretches the texture across the screen. The copy is a framebuffer readback, which this backend
 * does not implement, so the texture stayed empty: the fullscreen stretch drew nothing and what
 * remained on screen was the raw 256x256 render in the corner, exactly as the screenshot showed.
 *
 * <p>Rather than implement a readback for one cosmetic screen, the panorama is rendered straight
 * to the window at its real size. The blur chain is what the readback existed for, so it goes with
 * it; the darkening pass that keeps the title and buttons legible is kept and drawn directly.
 * The result is a sharp full screen panorama, which also skips seven fullscreen blur passes.
 */
@Mixin(TitleScreen.class)
public abstract class TitleScreenPanoramaMixin {

    @Shadow protected abstract void renderPanorama(int mouseX, int mouseY, float tickDelta);

    @Inject(method = "renderBackground(IIF)V", at = @At("HEAD"), cancellable = true)
    private void vulkanmodFullscreenPanorama(int mouseX, int mouseY, float tickDelta, CallbackInfo ci) {
        if (!Initializer.vulkanReady) return;

        renderPanorama(mouseX, mouseY, tickDelta);

        // Vanilla's blur pass also dimmed the panorama; without it the menu text sits on a bright
        // sky and is hard to read, so the dimming is reapplied on its own.
        Screen self = (Screen) (Object) this;
        DrawableHelper.fill(0, 0, self.width, self.height, 0x66101018);

        ci.cancel();
    }

    /**
     * Uses the window's aspect ratio for the panorama projection.
     *
     * <p>Vanilla hardcodes 1.0 because it was always rendering into a square viewport. Now that
     * the panorama covers the window, that constant would stretch it horizontally.
     */
    @Redirect(method = "renderPanorama(IIF)V",
            at = @At(value = "INVOKE",
                     target = "Lorg/lwjgl/util/glu/Project;gluPerspective(FFFF)V"))
    private void vulkanmodPanoramaAspect(float fovY, float aspect, float zNear, float zFar) {
        Screen self = (Screen) (Object) this;
        float real = Initializer.vulkanReady && self.height > 0
                ? (float) self.width / (float) self.height
                : aspect;

        // glFrustum rather than a call back into Project: the GLU helper is supplied by the
        // launcher at runtime and is not on the compile classpath. This is the same matrix, which
        // is all gluPerspective builds.
        double top = Math.tan(Math.toRadians(fovY) / 2.0) * zNear;
        double right = top * real;
        GL11.glFrustum(-right, right, -top, top, zNear, zFar);
    }
}
