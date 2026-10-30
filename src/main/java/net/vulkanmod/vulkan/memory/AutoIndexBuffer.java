package net.vulkanmod.vulkan.memory;

import net.vulkanmod.vulkan.memory.MemoryManager;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.ShortBuffer;

public class AutoIndexBuffer {
    int vertexCount;
    DrawType drawType;
    IndexBuffer indexBuffer;

    public AutoIndexBuffer(int vertexCount, DrawType type) {
        this.drawType = type;

        createIndexBuffer(vertexCount);
    }

    private void createIndexBuffer(int vertexCount) {
        this.vertexCount = vertexCount;
        int size;
        ByteBuffer buffer;

        if (drawType == DrawType.QUADS) {
            size = vertexCount * 3 / 2 * IndexBuffer.IndexType.SHORT.size;
            buffer = genQuadIdxs(vertexCount);
        } else if (drawType == DrawType.TRIANGLE_FAN) {
            size = (vertexCount - 2) * 3 * IndexBuffer.IndexType.SHORT.size;
            buffer = genTriangleFanIdxs(vertexCount);
        } else if (drawType == DrawType.TRIANGLE_STRIP) {
            size = (vertexCount - 2) * 3 * IndexBuffer.IndexType.SHORT.size;
            buffer = genTriangleStripIdxs(vertexCount);
        } else if (drawType == DrawType.TRIANGLES) {
            size = vertexCount * IndexBuffer.IndexType.SHORT.size;
            buffer = genTriangleIdxs(vertexCount);
        } else {
            throw new RuntimeException("unknown drawType");
        }

        indexBuffer = new IndexBuffer(size, MemoryTypes.GPU_MEM);
        indexBuffer.copyBuffer(buffer);
        // Free the native staging buffer after GPU upload
        MemoryUtil.memFree(buffer);
    }

    public void checkCapacity(int vertexCount) {
        if(vertexCount > this.vertexCount) {
            int newVertexCount = this.vertexCount * 2;
            System.out.println("Reallocating AutoIndexBuffer from " + this.vertexCount + " to " + newVertexCount);

            // Deferred-free old buffer (GPU may still be reading it)
            if (indexBuffer != null) {
                MemoryManager.addToFreeable(indexBuffer);
            }
            createIndexBuffer(newVertexCount);
        }
    }

    public static ByteBuffer genQuadIdxs(int vertexCount) {
        //short[] idxs = {0, 1, 2, 0, 2, 3};

        int indexCount = vertexCount * 3 / 2;
        ByteBuffer buffer = MemoryUtil.memAlloc(indexCount * Short.BYTES);
        ShortBuffer idxs = buffer.asShortBuffer();
        //short[] idxs = new short[indexCount];

        int j = 0;
        for(int i = 0; i < vertexCount; i += 4) {

            idxs.put(j, (short) i);
            idxs.put(j + 1, (short) (i + 1));
            idxs.put(j + 2, (short) (i + 2));
            idxs.put(j + 3, (short) (i));
            idxs.put(j + 4, (short) (i + 2));
            idxs.put(j + 5, (short) (i + 3));

            j += 6;
        }

        return buffer;
        //this.type.copyIndexBuffer(this, bufferSize, idxs);
    }

    public static ByteBuffer genTriangleFanIdxs(int vertexCount) {
        int indexCount = (vertexCount - 2) * 3;
        ByteBuffer buffer = MemoryUtil.memAlloc(indexCount * Short.BYTES);
        ShortBuffer idxs = buffer.asShortBuffer();

        //short[] idxs = byteBuffer.asShortBuffer().array();

        int j = 0;
        for (int i = 0; i < vertexCount - 2; ++i) {
//            idxs[j] = 0;
//            idxs[j + 1] = (short) (i + 1);
//            idxs[j + 2] = (short) (i + 2);

            idxs.put(j, (short) 0);
            idxs.put(j + 1, (short) (i + 1));
            idxs.put(j + 2, (short) (i + 2));

            j += 3;
        }

        buffer.rewind();
        return buffer;
    }

    public static ByteBuffer genTriangleStripIdxs(int vertexCount) {
        int indexCount = (vertexCount - 2) * 3;

        //TODO: free buffer
        ByteBuffer buffer = MemoryUtil.memAlloc(indexCount * Short.BYTES);
        ShortBuffer idxs = buffer.asShortBuffer();

        //short[] idxs = byteBuffer.asShortBuffer().array();

        int j = 0;
        for (int i = 0; i < vertexCount - 2; ++i) {
//            idxs[j] = 0;
//            idxs[j + 1] = (short) (i + 1);
//            idxs[j + 2] = (short) (i + 2);

            idxs.put(j, (short) i);
            idxs.put(j + 1, (short) (i + 1));
            idxs.put(j + 2, (short) (i + 2));

            j += 3;
        }

        buffer.rewind();
        return buffer;
    }

    public static ByteBuffer genTriangleIdxs(int vertexCount) {
        ByteBuffer buffer = MemoryUtil.memAlloc(vertexCount * Short.BYTES);
        ShortBuffer idxs = buffer.asShortBuffer();

        for (int i = 0; i < vertexCount; i++) {
            idxs.put(i, (short) i);
        }

        return buffer;
    }

    public IndexBuffer getIndexBuffer() { return indexBuffer; }

    public enum DrawType {
        QUADS(7),
        TRIANGLE_FAN(6),
        TRIANGLE_STRIP(5),
        TRIANGLES(4);

        public final int n;

        DrawType (int n) {
            this.n = n;
        }
    }
}
