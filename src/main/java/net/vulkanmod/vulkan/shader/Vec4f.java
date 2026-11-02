package net.vulkanmod.vulkan.shader;

import net.vulkanmod.vulkan.VRenderSystem;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

public class Vec4f extends Field {

    protected Vec4f(String name, AlignedStruct ubo) {
        super(name, 4, 4, ubo.getCurrentOffset());

        ubo.setCurrentOffset(offset + size);
        setFunction();
    }

    void setFunction() {
        if ("ColorModulator".equals(this.name)) {
            this.set = VRenderSystem::getShaderColorArray;
        } else if ("FogColor".equals(this.name)) {
            this.set = VRenderSystem::getShaderFogColorArray;
        } else if ("OverlayColor".equals(this.name)) {
            this.set = VRenderSystem::getShaderEntityOverlayColorArray;
        }
    }

    void update(FloatBuffer fb) {
        float[] floats = (float[]) this.set.get();
        fb.position(offset);

        for(float f : floats) {
            fb.put(f);
        }
    }

    void update(ByteBuffer buffer) {
        float[] floats = (float[]) this.set.get();
        buffer.position(offset * 4);

        for(float f : floats) {
            buffer.putFloat(f);
        }
    }
}
