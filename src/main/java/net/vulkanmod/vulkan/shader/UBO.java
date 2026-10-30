package net.vulkanmod.vulkan.shader;

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

public class UBO extends AlignedStruct {
    private final int binding;
    private final int flags;
    private ByteBuffer buffer;
    private List<Field> fields = new ArrayList<>();
    // Array copy for hot-path iteration — avoids ArrayList.get() bounds checks + iterator alloc
    private Field[] fieldsArray;
    private int fieldCount;
    private int currentOffset = 0;
    private int size = 0;
    private int bufferLimit = 0; // precomputed limit for update()

    public UBO(int binding, int type) {
        this.binding = binding;
        this.flags = type;
        this.buffer = MemoryUtil.memAlloc(1024);
    }

    public void addField(Field field) {
        fields.add(field);
        this.size = field.getOffset() + field.getSize();
        // Rebuild array cache
        this.fieldsArray = fields.toArray(new Field[0]);
        this.fieldCount = fieldsArray.length;
        this.bufferLimit = (field.getOffset() + field.getSize()) * Float.BYTES;
    }

    public void update() {
        // Direct array iteration — no iterator allocation, no bounds checks
        Field[] fa = this.fieldsArray;
        int n = this.fieldCount;
        for (int i = 0; i < n; i++) {
            fa[i].update(buffer);
        }
        buffer.limit(bufferLimit);
        buffer.position(0);
    }

    public int getBinding() {
        return binding;
    }

    public int getFlags() {
        return flags;
    }

    public int getCurrentOffset() {
        return currentOffset;
    }

    public void setCurrentOffset(int offset) {
        currentOffset = offset;
    }

    public int getSize() {
        return size * 4;
    }

    public ByteBuffer getBuffer() {
        return buffer;
    }

    public FloatBuffer getFloatBuffer() {
        return buffer.asFloatBuffer();
    }

    public void resetOffset() { currentOffset = 0;}

}
