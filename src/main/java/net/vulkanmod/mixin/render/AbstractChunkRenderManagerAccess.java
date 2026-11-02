package net.vulkanmod.mixin.render;

import net.minecraft.client.render.world.AbstractChunkRenderManager;
import net.minecraft.client.world.BuiltChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import java.util.List;

/**
 * The per-frame section list and camera position live in the base class; the VBO renderer only
 * inherits them. An accessor mixin keeps the fast path readable without widening their
 * visibility in the game classes themselves.
 */
@Mixin(AbstractChunkRenderManager.class)
public interface AbstractChunkRenderManagerAccess {

    @Accessor("helpers")
    List<BuiltChunk> vulkanmod$getHelpers();

    @Accessor("field_10667")
    boolean vulkanmod$isRenderReady();

    @Accessor("viewX")
    double vulkanmod$getViewX();

    @Accessor("viewY")
    double vulkanmod$getViewY();

    @Accessor("viewZ")
    double vulkanmod$getViewZ();
}
