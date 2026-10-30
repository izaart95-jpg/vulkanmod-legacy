package net.vulkanmod.vulkan.shader;

import net.vulkanmod.vulkan.VRenderSystem;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

public class Mat4f extends Field {

    protected Mat4f(String name, AlignedStruct ubo) {
        super(name, 16, 4, ubo.getCurrentOffset());

        ubo.setCurrentOffset(offset + size);
        setFunction();
    }

    protected void setFunction() {
        if ("ModelViewMat".equals(this.name)) {
            this.set = VRenderSystem::getModelViewMatrix;
        } else if ("ProjMat".equals(this.name)) {
            this.set = VRenderSystem::getProjectionMatrix;
        } else if ("MVP".equals(this.name)) {
            this.set = VRenderSystem::getMVP;
        } else if ("TextureMat".equals(this.name)) {
            this.set = VRenderSystem::getTextureMatrix;
        }
    }

    public void update(FloatBuffer fb) {
        ByteBuffer src = (ByteBuffer) set.get();
        FloatBuffer srcFloat = src.asFloatBuffer();
        fb.position(offset);
        for (int i = 0; i < 16; i++) {
            fb.put(srcFloat.get(i));
        }
    }

    public void update(ByteBuffer buffer) {
        ByteBuffer src = (ByteBuffer) set.get();
        buffer.position(offset << 2);
        MemoryUtil.memCopy(src, buffer);
    }
}
