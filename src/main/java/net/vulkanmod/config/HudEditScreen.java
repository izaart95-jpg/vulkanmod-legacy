package net.vulkanmod.config;

import net.minecraft.client.gui.screen.Screen;
import org.lwjgl.input.Keyboard;

/**
 * Arranges the overlays this mod draws.
 *
 * <p>Only the things this mod adds are shown, each inside a box sized to what it actually draws,
 * with a dot at every corner. Dragging the body moves it; dragging a dot scales it, from the
 * distance to the opposite corner, so the element grows towards the corner you are pulling.
 *
 * <p>Elements whose switch is off are still shown, dimmed: a player laying out their HUD should be
 * able to place something before turning it on.
 */
public class HudEditScreen extends Screen {

    private static final int DIM = 0x90000000;
    private static final int BOX = 0x40FFFFFF;
    private static final int BOX_ACTIVE = 0xFFFF5B2E;
    private static final int HANDLE = 0xFFFF5B2E;

    /** Half the side of a corner dot, so a click within this distance grabs it. */
    private static final int HANDLE_REACH = 3;

    private static final float MIN_SCALE = 0.5f;
    private static final float MAX_SCALE = 3.0f;

    private final Screen parent;

    private HudElement dragging;
    /** Which corner is held, 0..3 as top left, top right, bottom left, bottom right; -1 to move. */
    private int handle = -1;
    private int grabOffsetX, grabOffsetY;

    public HudEditScreen(Screen parent) {
        this.parent = parent;
    }

    @Override
    public void render(int mouseX, int mouseY, float tickDelta) {
        fill(0, 0, this.width, this.height, DIM);

        for (HudElement element : VulkanModConfig.HUD_ELEMENTS) {
            HudRenderer.render(this.client, element, this.width, this.height, true);
        }

        // Drawn after every element so no overlay paints over another's handles.
        for (HudElement element : VulkanModConfig.HUD_ELEMENTS) {
            int x = element.pixelX(this.width);
            int y = element.pixelY(this.height);
            int w = Math.max(8, element.width);
            int h = Math.max(8, element.height);
            boolean active = element == dragging
                    || (mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h);

            outline(x - 1, y - 1, x + w + 1, y + h + 1, active ? BOX_ACTIVE : BOX);
            for (int corner = 0; corner < 4; corner++) {
                int cx = cornerX(x, w, corner);
                int cy = cornerY(y, h, corner);
                fill(cx - HANDLE_REACH, cy - HANDLE_REACH,
                     cx + HANDLE_REACH, cy + HANDLE_REACH, HANDLE);
            }

            if (active && element != dragging) {
                drawCenteredString(this.textRenderer, element.name, x + w / 2, y - 12,
                                   element.toggle.value ? 0xFFECECF0 : 0xFF8B8B99);
            }
        }

        String hint = "Drag to move  -  drag a corner to resize  -  Esc to finish";
        drawCenteredString(this.textRenderer, hint, this.width / 2, this.height - 16, 0xFF8B8B99);
    }

    private static int cornerX(int x, int w, int corner) {
        return (corner & 1) == 0 ? x : x + w;
    }

    private static int cornerY(int y, int h, int corner) {
        return (corner & 2) == 0 ? y : y + h;
    }

    private void outline(int x0, int y0, int x1, int y1, int color) {
        fill(x0, y0, x1, y0 + 1, color);
        fill(x0, y1 - 1, x1, y1, color);
        fill(x0, y0, x0 + 1, y1, color);
        fill(x1 - 1, y0, x1, y1, color);
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        if (button != 0) return;

        // Back to front, so the element drawn last -- the one on top -- is the one you grab.
        for (int i = VulkanModConfig.HUD_ELEMENTS.size() - 1; i >= 0; i--) {
            HudElement element = VulkanModConfig.HUD_ELEMENTS.get(i);
            int x = element.pixelX(this.width);
            int y = element.pixelY(this.height);
            int w = Math.max(8, element.width);
            int h = Math.max(8, element.height);

            for (int corner = 0; corner < 4; corner++) {
                int cx = cornerX(x, w, corner);
                int cy = cornerY(y, h, corner);
                if (Math.abs(mouseX - cx) <= HANDLE_REACH && Math.abs(mouseY - cy) <= HANDLE_REACH) {
                    dragging = element;
                    handle = corner;
                    return;
                }
            }

            if (mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h) {
                dragging = element;
                handle = -1;
                grabOffsetX = mouseX - x;
                grabOffsetY = mouseY - y;
                return;
            }
        }
    }

    @Override
    protected void mouseDragged(int mouseX, int mouseY, int button, long heldMs) {
        if (dragging == null) return;

        if (handle < 0) {
            dragging.setPixelPosition(mouseX - grabOffsetX, mouseY - grabOffsetY,
                                      this.width, this.height);
            return;
        }

        // Scale from the distance to the corner opposite the one being held, so the anchored
        // corner stays put and the element grows the way the cursor is pulling.
        int x = dragging.pixelX(this.width);
        int y = dragging.pixelY(this.height);
        int w = Math.max(8, dragging.width);
        int h = Math.max(8, dragging.height);
        int anchorX = cornerX(x, w, 3 - handle);
        int anchorY = cornerY(y, h, 3 - handle);

        int reach = Math.max(Math.abs(mouseX - anchorX), Math.abs(mouseY - anchorY));
        int current = Math.max(w, h);
        if (current <= 0) return;

        float scale = dragging.scale * reach / current;
        dragging.scale = Math.max(MIN_SCALE, Math.min(MAX_SCALE, scale));
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int button) {
        if (dragging != null) {
            dragging = null;
            handle = -1;
            VulkanModConfig.save();
        }
    }

    @Override
    protected void keyPressed(char chr, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            VulkanModConfig.save();
            this.client.openScreen(parent);
        }
    }

    /** Positioning against the real game behind it is the point, so it keeps running. */
    @Override
    public boolean shouldPauseGame() {
        return false;
    }
}
