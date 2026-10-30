package net.vulkanmod.mixin.perf;

import net.minecraft.client.world.BuiltChunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes WorldRenderer$ChunkInfo.chunk, which is package private and so unreachable from here. */
@Mixin(targets = "net.minecraft.client.render.WorldRenderer$ChunkInfo")
public interface ChunkInfoAccessor {
    @Accessor("chunk")
    BuiltChunk vulkanmodGetChunk();
}
