package net.vulkanmod.config;

import org.lwjgl.input.Mouse;

/**
 * Clicks per second for the two mouse buttons.
 *
 * <p>Kept as a ring of timestamps rather than a counter reset every second so the number moves
 * smoothly instead of stepping: a click counts for exactly one second from when it happened.
 *
 * <p>{@link #poll()} is driven from the HUD render, which is the only place that runs often enough
 * to see individual clicks -- the 20 Hz client tick would miss most of them.
 */
public final class ClickCounter {

    private ClickCounter() {}

    /** A player cannot usefully exceed this, and it bounds the memory the ring needs. */
    private static final int WINDOW = 64;

    private static final long[] left = new long[WINDOW];
    private static final long[] right = new long[WINDOW];
    private static int leftIndex = 0;
    private static int rightIndex = 0;

    private static boolean leftWasDown = false;
    private static boolean rightWasDown = false;

    public static void poll() {
        long now = System.currentTimeMillis();

        boolean leftDown = Mouse.isButtonDown(0);
        if (leftDown && !leftWasDown) {
            left[leftIndex] = now;
            leftIndex = (leftIndex + 1) % WINDOW;
        }
        leftWasDown = leftDown;

        boolean rightDown = Mouse.isButtonDown(1);
        if (rightDown && !rightWasDown) {
            right[rightIndex] = now;
            rightIndex = (rightIndex + 1) % WINDOW;
        }
        rightWasDown = rightDown;
    }

    public static int leftCps() {
        return count(left);
    }

    public static int rightCps() {
        return count(right);
    }

    private static int count(long[] stamps) {
        long cutoff = System.currentTimeMillis() - 1000L;
        int n = 0;
        for (long stamp : stamps) {
            if (stamp > cutoff) n++;
        }
        return n;
    }
}
