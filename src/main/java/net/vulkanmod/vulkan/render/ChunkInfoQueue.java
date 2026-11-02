package net.vulkanmod.vulkan.render;

import java.util.ArrayDeque;
import java.util.LinkedList;

/**
 * Array-backed replacement for the BFS queue in {@code WorldRenderer.setupTerrain}.
 *
 * <p>Vanilla allocates a fresh {@link LinkedList} for every terrain rebuild and then makes a node
 * object for every enqueued section. A rebuild happens on any camera movement, including pure
 * rotation, so at high frame rates that is thousands of short-lived nodes per second. The
 * declaration type has to stay {@code LinkedList} because the call sites are compiled against it,
 * hence the subclass: the three methods the traversal actually uses are overridden to an
 * {@link ArrayDeque}, and {@link #reset()} drops the contents when the queue is reused.
 */
public final class ChunkInfoQueue extends LinkedList<Object> {

    private final ArrayDeque<Object> delegate = new ArrayDeque<>(4096);

    public void reset() {
        delegate.clear();
    }

    @Override
    public boolean add(Object value) {
        delegate.addLast(value);
        return true;
    }

    @Override
    public Object poll() {
        return delegate.pollFirst();
    }

    @Override
    public boolean isEmpty() {
        return delegate.isEmpty();
    }
}
