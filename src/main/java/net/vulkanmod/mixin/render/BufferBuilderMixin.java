package net.vulkanmod.mixin.render;

import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.VertexFormat;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.nio.ByteBuffer;

/**
 * Prevents "Already building!" crash by resetting the buffer if begin() is
 * called while still in building state (e.g. after teleportation or interrupted render).
 */
@Mixin(BufferBuilder.class)
public class BufferBuilderMixin {

    @Shadow private boolean building;
    @Shadow private int vertexCount;
    @Shadow private ByteBuffer buffer;

    @Inject(method = "begin", at = @At("HEAD"))
    private void resetIfAlreadyBuilding(int drawMode, VertexFormat format, CallbackInfo ci) {
        if (this.building) {
            // Just reset building flag. Don't call end() as it shrinks the
            // buffer limit and corrupts state for concurrent text batching.
            // begin() itself will call reset() and restore limit to capacity.
            this.building = false;
        }
    }
}
