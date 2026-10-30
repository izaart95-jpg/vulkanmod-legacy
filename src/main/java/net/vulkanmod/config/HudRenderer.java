package net.vulkanmod.config;

import com.mojang.blaze3d.platform.GlStateManager;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.options.KeyBinding;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.util.ArrayList;
import java.util.List;

/**
 * Draws the movable overlays, for both the live HUD and the edit screen.
 *
 * <p>One renderer serves both so that what you drag around is literally what you will see in game,
 * down to the width of the text. Each draw also records the size it came out as, which is the only
 * way the edit screen can put a handle box around something whose width depends on the number
 * inside it.
 */
public final class HudRenderer {

    private HudRenderer() {}

    private static final int TEXT = 0xFFECECF0;
    private static final int ACCENT = 0xFFFF5B2E;

    private static final int KEY = 0x60000000;
    private static final int KEY_DOWN = 0xC0FF5B2E;
    private static final int KEY_TEXT = 0xFFFFFFFF;
    private static final int KEY_TEXT_DOWN = 0xFF15151A;

    // Keystroke layout, in unscaled pixels.
    private static final int KEY_SIZE = 18;
    private static final int KEY_GAP = 2;
    private static final int KEYS_WIDTH = KEY_SIZE * 3 + KEY_GAP * 2;
    private static final int SPACE_HEIGHT = 10;

    private static final List<String> LINES = new ArrayList<>();

    /**
     * Draws one element at its stored position and records the size it occupied.
     *
     * @param force draw even when the element's switch is off, which is what the edit screen wants
     *              so that a disabled overlay can still be positioned
     */
    public static void render(MinecraftClient mc, HudElement element,
                              int screenWidth, int screenHeight, boolean force) {
        if (!force && !element.toggle.value) return;

        // Measure first: the position depends on the size, and the size depends on the content,
        // which changes every frame for a frame rate or a coordinate readout.
        measure(mc, element);

        int x = element.pixelX(screenWidth);
        int y = element.pixelY(screenHeight);

        GlStateManager.pushMatrix();
        GlStateManager.translatef(x, y, 0.0f);
        GlStateManager.scalef(element.scale, element.scale, 1.0f);
        draw(mc, element);
        GlStateManager.popMatrix();
    }

    /** The element's on screen size at its current scale, from the content it would draw. */
    private static void measure(MinecraftClient mc, HudElement element) {
        if (element == VulkanModConfig.HUD_KEYSTROKES) {
            element.width = Math.round(KEYS_WIDTH * element.scale);
            element.height = Math.round((KEY_SIZE * 3 + KEY_GAP * 3 + SPACE_HEIGHT) * element.scale);
            return;
        }

        lines(mc, element);
        int widest = 0;
        for (String line : LINES) {
            widest = Math.max(widest, mc.textRenderer.getStringWidth(line));
        }
        element.width = Math.round(widest * element.scale);
        element.height = Math.round(Math.max(1, LINES.size()) * 10 * element.scale);
    }

    /** Draws at the origin; the caller has already translated and scaled. */
    private static void draw(MinecraftClient mc, HudElement element) {
        if (element == VulkanModConfig.HUD_KEYSTROKES) {
            drawKeystrokes(mc);
            return;
        }

        lines(mc, element);
        int y = 0;
        for (String line : LINES) {
            mc.textRenderer.drawWithShadow(line, 0, y, TEXT);
            y += 10;
        }
    }

    /**
     * The text an element shows, into the shared buffer.
     *
     * <p>Shared rather than returned so that measuring and drawing the same frame does not allocate
     * a list per element per frame.
     */
    private static void lines(MinecraftClient mc, HudElement element) {
        LINES.clear();

        if (element == VulkanModConfig.HUD_FPS) {
            LINES.add(MinecraftClient.getCurrentFps() + " FPS");
        } else if (element == VulkanModConfig.HUD_COORDS) {
            if (mc.player == null) {
                LINES.add("0.0 0.0 0.0");
            } else {
                LINES.add(String.format("%.1f %.1f %.1f",
                        mc.player.x, mc.player.getBoundingBox().minY, mc.player.z));
            }
        } else if (element == VulkanModConfig.HUD_CPS) {
            LINES.add(ClickCounter.leftCps() + " | " + ClickCounter.rightCps() + " CPS");
        }
    }

    private static void drawKeystrokes(MinecraftClient mc) {
        int row = KEY_SIZE + KEY_GAP;

        key(mc, KEY_SIZE + KEY_GAP, 0, KEY_SIZE, KEY_SIZE, "W", down(mc.options.keyForward));
        key(mc, 0, row, KEY_SIZE, KEY_SIZE, "A", down(mc.options.keyLeft));
        key(mc, KEY_SIZE + KEY_GAP, row, KEY_SIZE, KEY_SIZE, "S", down(mc.options.keyBack));
        key(mc, (KEY_SIZE + KEY_GAP) * 2, row, KEY_SIZE, KEY_SIZE, "D", down(mc.options.keyRight));

        // The mouse buttons take half the row each, so the block stays a clean rectangle.
        int half = (KEYS_WIDTH - KEY_GAP) / 2;
        key(mc, 0, row * 2, half, KEY_SIZE, "LMB", Mouse.isButtonDown(0));
        key(mc, half + KEY_GAP, row * 2, KEYS_WIDTH - half - KEY_GAP, KEY_SIZE, "RMB",
            Mouse.isButtonDown(1));

        key(mc, 0, row * 3, KEYS_WIDTH, SPACE_HEIGHT, "", down(mc.options.keyJump));
    }

    /**
     * Whether a bound key is physically held.
     *
     * <p>Polled rather than read from KeyBinding.isPressed: that flag is cleared while a screen is
     * open, and the edit screen is a screen -- the keys would all read up while you were arranging
     * them.
     */
    private static boolean down(KeyBinding binding) {
        int code = binding.getCode();
        if (code < 0) return Mouse.isButtonDown(code + 100);
        return Keyboard.isKeyDown(code);
    }

    private static void key(MinecraftClient mc, int x, int y, int w, int h,
                            String label, boolean pressed) {
        DrawableHelper.fill(x, y, x + w, y + h, pressed ? KEY_DOWN : KEY);
        if (label.isEmpty()) {
            // The space bar is drawn as a bar rather than a letter, the way a keyboard looks.
            int inset = Math.max(2, w / 6);
            DrawableHelper.fill(x + inset, y + h / 2, x + w - inset, y + h / 2 + 1,
                                pressed ? KEY_TEXT_DOWN : KEY_TEXT);
            return;
        }
        int textWidth = mc.textRenderer.getStringWidth(label);
        mc.textRenderer.drawWithShadow(label,
                x + (w - textWidth) / 2.0f, y + (h - 8) / 2.0f,
                pressed ? KEY_TEXT_DOWN : KEY_TEXT);
    }

    /** The accent colour, so the edit screen's handles match the rest of the client. */
    public static int accent() {
        return ACCENT;
    }
}
