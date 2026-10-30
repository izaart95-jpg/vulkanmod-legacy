package net.vulkanmod.mixin.render;

import net.minecraft.client.render.VertexFormat;
import net.vulkanmod.interfaces.VertexFormatMixed;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;

import java.util.List;

@Mixin(VertexFormat.class)
public class VertexFormatMixin implements VertexFormatMixed {
    @Shadow private List<Integer> positions;

    public int getOffset(int i) {
        return positions.get(i);
    }
}
