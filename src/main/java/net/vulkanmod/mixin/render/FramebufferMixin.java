package net.vulkanmod.mixin.render;

import net.minecraft.client.gl.Framebuffer;
import net.vulkanmod.Initializer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Framebuffer.class)
public class FramebufferMixin {

    @Inject(method = "draw", at = @At("HEAD"), cancellable = true)
    private void beforeDraw(int width, int height, CallbackInfo ci) {
        if (Initializer.vulkanReady) {
            ci.cancel();
        }
    }
}
