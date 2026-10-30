package net.vulkanmod.mixin.render;

import net.minecraft.client.options.GameOptions;
import net.vulkanmod.Initializer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameOptions.class)
public class GameOptionsMixin {

    @Shadow public boolean vbo;

    @Inject(method = "load", at = @At("RETURN"))
    private void forceVbo(CallbackInfo ci) {
        this.vbo = true;
        Initializer.LOGGER.info("[GameOptions] Forced VBOs ON for Vulkan chunk rendering");
    }
}
