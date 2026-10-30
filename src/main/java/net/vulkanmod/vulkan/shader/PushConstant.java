package net.vulkanmod.vulkan.shader;

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

public class PushConstant extends AlignedStruct {
    private ByteBuffer buffer;
    private List<Field> fields = new ArrayList<>();
    // Array copy for hot-path iteration — avoids ArrayList.get() bounds checks + iterator alloc
    private Field[] fieldsArray = new Field[0];
    private int fieldCount;
    private int size;

    public void allocateBuffer() {
        buffer = MemoryUtil.memAlloc(size);
    }

    public void update() {
        // Direct array iteration — no iterator allocation, no bounds checks
        Field[] fa = this.fieldsArray;
        int n = this.fieldCount;
        for (int i = 0; i < n; i++) {
            fa[i].update(buffer);
        }

        buffer.position(0);
    }

    public void addField(Field field) {
        fields.add(field);
        this.size = (field.getOffset() + field.getSize()) * 4;
        // Rebuild array cache
        this.fieldsArray = fields.toArray(new Field[0]);
        this.fieldCount = fieldsArray.length;
    }

    public ByteBuffer getBuffer() {
        return buffer;
    }

    public int getSize() { return size; }
}
