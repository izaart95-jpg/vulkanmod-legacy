package net.vulkanmod.vulkan.render;

import net.minecraft.client.world.BuiltChunk;

import java.util.Iterator;
import java.util.List;
import java.util.Set;

/**
 * Walks a reordered snapshot of the pending set while removing from the set itself.
 *
 * <p>A top level class rather than an anonymous one inside the mixin: mixins here are compiled at
 * language level 8, which does not allow nested classes. It also has to live outside
 * net.vulkanmod.mixin.*, since classes in a mixin package cannot be referenced directly.
 */
public class VisibleFirstIterator implements Iterator<BuiltChunk> {

    private final List<BuiltChunk> ordered;
    private final Set<BuiltChunk> backing;
    private int index;
    private BuiltChunk last;

    public VisibleFirstIterator(List<BuiltChunk> ordered, Set<BuiltChunk> backing) {
        this.ordered = ordered;
        this.backing = backing;
    }

    @Override
    public boolean hasNext() {
        return index < ordered.size();
    }

    @Override
    public BuiltChunk next() {
        last = ordered.get(index++);
        return last;
    }

    /** updateChunks removes each section it hands off; that has to reach the real set. */
    @Override
    public void remove() {
        backing.remove(last);
    }
}
