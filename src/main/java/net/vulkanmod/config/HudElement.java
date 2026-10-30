package net.vulkanmod.config;

/**
 * One thing this mod draws on top of the game, and where the player put it.
 *
 * <p>Position is stored as a fraction of the screen rather than in pixels so that a window resize,
 * or a change of GUI scale, keeps an element in the corner the player left it in instead of
 * shoving it off the edge.
 *
 * <p>{@code width} and {@code height} are filled in by the renderer each frame from what it
 * actually drew. The edit screen needs them to size the handle box, and only the renderer knows
 * how wide "13 | 4 CPS" turned out to be.
 */
public class HudElement {

    public final String id;
    public final String name;
    /** The switch in the HUD category that decides whether this is drawn at all. */
    public final Option toggle;

    public float x;
    public float y;
    public float scale;

    public int width;
    public int height;

    HudElement(String id, String name, Option toggle, float x, float y, float scale) {
        this.id = id;
        this.name = name;
        this.toggle = toggle;
        this.x = x;
        this.y = y;
        this.scale = scale;
    }

    /** Top left corner in screen pixels, kept far enough inside that it cannot be dragged away. */
    public int pixelX(int screenWidth) {
        return Math.round(x * Math.max(1, screenWidth - width));
    }

    public int pixelY(int screenHeight) {
        return Math.round(y * Math.max(1, screenHeight - height));
    }

    public void setPixelPosition(int px, int py, int screenWidth, int screenHeight) {
        x = clamp01(px / (float) Math.max(1, screenWidth - width));
        y = clamp01(py / (float) Math.max(1, screenHeight - height));
    }

    private static float clamp01(float v) {
        return Math.max(0.0f, Math.min(1.0f, v));
    }
}
