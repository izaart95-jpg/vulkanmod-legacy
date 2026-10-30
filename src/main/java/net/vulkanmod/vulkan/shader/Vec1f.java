package net.vulkanmod.vulkan.shader;

import net.vulkanmod.vulkan.VRenderSystem;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

public class Vec1f extends Field {

    protected Vec1f(String name, AlignedStruct struct) {
        super(name, 1, 1, struct.getCurrentOffset());

        struct.setCurrentOffset(offset + size);
        setFunction();
    }

    void setFunction() {
        if ("FogStart".equals(this.name)) {
            this.set = VRenderSystem::getShaderFogStart;
        } else if ("FogEnd".equals(this.name)) {
            this.set = VRenderSystem::getShaderFogEnd;
        } else if ("LineWidth".equals(this.name)) {
            this.set = VRenderSystem::getShaderLineWidth;
        } else if ("GameTime".equals(this.name)) {
            this.set = VRenderSystem::getShaderGameTime;
        }
    }

    void update(FloatBuffer fb) {
        float f = (float) this.set.get();
        fb.position(offset);
        fb.put(f);
    }

    void update(ByteBuffer buffer) {
        float f = (float) this.set.get();
        buffer.position(offset * 4);
        buffer.putFloat(f);
    }
}
