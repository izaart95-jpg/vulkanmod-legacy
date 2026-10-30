package net.vulkanmod.mixin.render;

import net.minecraft.client.MinecraftClient;
import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.Drawer;
import net.vulkanmod.vulkan.Vulkan;
import net.vulkanmod.vulkan.texture.GLTextureCache;
import org.lwjgl.opengl.Display;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(MinecraftClient.class)
public class MinecraftClientMixin {

    @Inject(method = "initializeGame", at = @At("RETURN"))
    private void onInitializeGame(CallbackInfo ci) {
        // Display.create() disables Vulkan mode when the machine has no usable
        // Vulkan device, in which case the game keeps its real OpenGL context.
        // Do not try to initialise the renderer on top of that.
        if (!Display.getVulkanMode()) return;

        long windowHandle = Display.getHandle();
        Initializer.LOGGER.info("Window handle captured: " + windowHandle);
        Initializer.initVulkanRenderer(windowHandle);
    }

    @Inject(method = "runGameLoop", at = @At("HEAD"))
    private void beginRender(CallbackInfo ci) {
        if (!Initializer.vulkanReady) return;
        // Do NOT syncFromGL here — MC hasn't set up camera/projection yet.
        // The matrixMode() hook in GlStateManagerMixin handles syncing from GL
        // at the correct moments (after gluPerspective etc.).
        GLTextureCache.resetFrameState();
        Drawer.getInstance().initiateRenderPass();
    }

    @Inject(method = "updateDisplay", at = @At("HEAD"))
    private void onUpdateDisplay(CallbackInfo ci) {
        if (!Initializer.vulkanReady) return;
        Drawer drawer = Drawer.getInstance();
        drawer.endRenderPass();
        drawer.submitDraw();
    }

    @Inject(method = "stop", at = @At("HEAD"))
    private void onStop(CallbackInfo ci) {
        if (!Initializer.vulkanReady) return;
        Vulkan.cleanUp();
    }
}
