package net.vulkanmod.vulkan.shader;

import net.vulkanmod.vulkan.VRenderSystem;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

public class Vec3f extends Field {

    protected Vec3f(String name, AlignedStruct ubo) {
        super(name, 3, 4, ubo.getCurrentOffset());

        ubo.setCurrentOffset(offset + size);
        setFunction();
    }

    void setFunction() {
        if ("Light0_Direction".equals(this.name)) {
            this.set = () -> VRenderSystem.lightDirection0;
        } else if ("Light1_Direction".equals(this.name)) {
            this.set = () -> VRenderSystem.lightDirection1;
        } else if ("ChunkOffset".equals(this.name)) {
            this.set = () -> VRenderSystem.ChunkOffset;
        }
    }

    void update(FloatBuffer fb) {
//        Vector3f vec3 = (Vector3f) this.set.get();
//        float[] floats = new float[3];
//
//        floats[0] = vec3.x();
//        floats[1] = vec3.y();
//        floats[2] = vec3.z();
//
//        fb.position(offset);
//
//        for(float f : floats) {
//            fb.put(f);
//        }
    }

    void update(ByteBuffer buffer) {
        //update(buffer.asFloatBuffer());
        ByteBuffer src = (ByteBuffer) set.get();
        buffer.position(offset * 4);
        MemoryUtil.memCopy(src, buffer);
    }
}
