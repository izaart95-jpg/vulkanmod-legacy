package net.vulkanmod.vulkan.util;

/**
 * Minimal open-addressed long -> long hash map with linear probing.
 *
 * <p>Exists to keep the per-draw descriptor-set lookup allocation free: a
 * {@code HashMap<Long, Long>} boxes the key on every {@code get}, which on a
 * busy frame is thousands of short lived objects straight into the nursery.
 *
 * <p>Not thread safe, and only supports the operations the render path needs.
 */
public class LongLongMap {
    /** Returned by {@link #get} when the key is absent. */
    public static final long NOT_FOUND = -1L;

    private static final long FREE = 0L;

    private long[] keys;
    private long[] values;
    private int mask;
    private int size;
    private int maxFill;

    // 0 is a legal key but doubles as the empty-slot marker, so it lives outside the table.
    private boolean hasZeroKey;
    private long zeroValue;

    public LongLongMap() {
        this(64);
    }

    public LongLongMap(int expected) {
        int capacity = 4;
        while (capacity < expected * 2) capacity <<= 1;
        this.keys = new long[capacity];
        this.values = new long[capacity];
        this.mask = capacity - 1;
        this.maxFill = capacity >> 1; // keep load factor at 0.5 so probes stay short
    }

    private static int index(long key, int mask) {
        // Murmur3 finalizer: handles and their products cluster in the low bits otherwise.
        long h = key;
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        return (int) h & mask;
    }

    public long get(long key) {
        if (key == FREE) return hasZeroKey ? zeroValue : NOT_FOUND;

        final long[] k = this.keys;
        int i = index(key, this.mask);
        long curr;
        while ((curr = k[i]) != FREE) {
            if (curr == key) return this.values[i];
            i = (i + 1) & this.mask;
        }
        return NOT_FOUND;
    }

    public void put(long key, long value) {
        if (key == FREE) {
            hasZeroKey = true;
            zeroValue = value;
            return;
        }

        int i = index(key, this.mask);
        long curr;
        while ((curr = this.keys[i]) != FREE) {
            if (curr == key) {
                this.values[i] = value;
                return;
            }
            i = (i + 1) & this.mask;
        }

        this.keys[i] = key;
        this.values[i] = value;
        if (++this.size > this.maxFill) rehash();
    }

    private void rehash() {
        final long[] oldKeys = this.keys;
        final long[] oldValues = this.values;
        int capacity = oldKeys.length << 1;

        final long[] newKeys = new long[capacity];
        final long[] newValues = new long[capacity];
        final int newMask = capacity - 1;

        for (int j = 0; j < oldKeys.length; j++) {
            long key = oldKeys[j];
            if (key == FREE) continue;
            int i = index(key, newMask);
            while (newKeys[i] != FREE) i = (i + 1) & newMask;
            newKeys[i] = key;
            newValues[i] = oldValues[j];
        }

        this.keys = newKeys;
        this.values = newValues;
        this.mask = newMask;
        this.maxFill = capacity >> 1;
    }

    public void clear() {
        java.util.Arrays.fill(this.keys, FREE);
        this.size = 0;
        this.hasZeroKey = false;
    }

    public int size() {
        return this.size + (this.hasZeroKey ? 1 : 0);
    }
}
