package net.vulkanmod.vulkan.util;

import net.vulkanmod.vulkan.Vulkan;
import org.joml.Matrix4f;
import org.joml.Vector4f;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.util.vma.Vma.vmaMapMemory;
import static org.lwjgl.util.vma.Vma.vmaUnmapMemory;

public class VUtil {
    public static final int UINT32_MAX = 0xFFFFFFFF;
    public static final long UINT64_MAX = 0xFFFFFFFFFFFFFFFFL;

    public static void memcpy(ByteBuffer buffer, short[] indices) {

        for(short index : indices) {
            buffer.putShort(index);
        }

        buffer.rewind();
    }

    public static void memcpy(ByteBuffer buffer, short[] indices, long offset) {
        buffer.position((int) offset);

        for(short index : indices) {
            buffer.putShort(index);
        }

        buffer.rewind();
    }

    public static void memcpy(ByteBuffer dst, ByteBuffer src) {
        MemoryUtil.memCopy(src, dst);
        src.limit(src.capacity()).rewind();
    }

    public static void memcpy(ByteBuffer dst, ByteBuffer src, long offset) {
        dst.position((int)offset);
        MemoryUtil.memCopy(src, dst);
        src.limit(src.capacity()).rewind();
    }

    public static void memcpy(ByteBuffer dst, ByteBuffer src, int size, long offset) {
        dst.position((int)offset);
        src.limit(size);
        MemoryUtil.memCopy(src, dst);
        src.limit(src.capacity()).rewind();
    }

    public static void memcpyImage(ByteBuffer dst, ByteBuffer src, int width, int height, int channels, int unpackSkipRows, int unpackSkipPixels, int unpackRowLenght) {
        int offset = (unpackSkipRows * unpackRowLenght + unpackSkipPixels) * channels;
        for (int i = 0; i < height; ++i) {
            src.limit(offset + width * channels);
            src.position(offset);
            dst.put(src);
            offset += unpackRowLenght * channels;
        }
    }

    public static void memcpy(ByteBuffer buffer, FloatBuffer floatBuffer) {
        while(floatBuffer.hasRemaining()) {
            float f = floatBuffer.get();
            buffer.putFloat(f);
        }
        floatBuffer.position(0);
    }

    public static void memcpy(ByteBuffer buffer, FloatBuffer floatBuffer, long offset) {
        buffer.position((int) offset);
        while(floatBuffer.hasRemaining()) {
            float f = floatBuffer.get();
            buffer.putFloat(f);
        }
        floatBuffer.position(0);
    }

    public static int align(int num, int align) {
        int mask = align - 1;
        return (num + mask) & ~mask;
    }

    // Adapted: accepts a column-major FloatBuffer instead of MC's Matrix4f
    public static Matrix4f convertFromColumnMajor(FloatBuffer columnMajor) {
        return new Matrix4f(columnMajor);
    }

    public static int packColor(float r, float g, float b, float a) {
        int color = 0;
        color += (int)(a * 255) << 24;
        color += (int)(r * 255) << 16;
        color += (int)(g * 255) << 8;
        color += (int)(b * 255);

        return color;
    }
}
