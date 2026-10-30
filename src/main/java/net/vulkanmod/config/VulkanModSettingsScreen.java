package net.vulkanmod.config;

import com.mojang.blaze3d.platform.GlStateManager;
import net.minecraft.client.gui.screen.Screen;
import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import java.util.ArrayList;
import java.util.List;

/**
 * The Right Shift menu.
 *
 * <p>Drawn from primitives rather than vanilla's widget set: the vanilla button texture is a fixed
 * 200x20 stone slab and cannot express a card grid, a sidebar or a toggle. Everything here is
 * filled rectangles and text, which the Vulkan backend already handles, so no new texture or
 * pipeline is involved.
 *
 * <p>The content is generated from {@link VulkanModConfig#OPTIONS}; this class knows about
 * categories, cards and the two kinds of control, not about individual features.
 */
public class VulkanModSettingsScreen extends Screen {

    // Panel chrome.
    private static final int DIM = 0xB0000000;
    private static final int PANEL = 0xF41A1A1F;
    private static final int HEADER = 0xFF212128;
    private static final int SIDEBAR = 0xFF1E1E24;
    private static final int LINE = 0xFF33333C;

    // Cards.
    private static final int CARD = 0xFF26262D;
    private static final int CARD_HOVER = 0xFF303039;
    private static final int TRACK = 0xFF3B3B45;

    // Text.
    private static final int TEXT = 0xFFECECF0;
    private static final int MUTED = 0xFF8B8B99;
    private static final int DISABLED = 0xFF5A5A66;

    private static final int ACCENT = 0xFFFF5B2E;

    private static final int HEADER_H = 30;
    private static final int SIDEBAR_W = 134;
    private static final int PAD = 8;
    private static final int CARD_H = 46;
    private static final int CARD_GAP = 6;
    private static final int ROW_H = 27;

    private int panelX, panelY, panelW, panelH;
    private int contentX, contentY, contentW, contentH;

    private Option.Category category = Option.Category.PERFORMANCE;
    private String search = "";
    private boolean searchFocused = false;

    /** How far the card grid is scrolled, in pixels. Clamped against {@link #maxScroll()}. */
    private int scroll = 0;

    /** The slider being dragged, so the value keeps following the cursor outside the card. */
    private Option draggingSlider;

    private final List<Option> visible = new ArrayList<>();

    @Override
    public void init() {
        Keyboard.enableRepeatEvents(true);

        panelW = Math.min(this.width - 30, 520);
        panelH = Math.min(this.height - 30, 300);
        panelX = (this.width - panelW) / 2;
        panelY = (this.height - panelH) / 2;

        contentX = panelX + SIDEBAR_W + PAD;
        contentY = panelY + HEADER_H + PAD;
        contentW = panelW - SIDEBAR_W - PAD * 2;
        contentH = panelH - HEADER_H - PAD * 2;
    }

    @Override
    public void removed() {
        Keyboard.enableRepeatEvents(false);
        VulkanModConfig.save();
    }

    // === content ===

    /**
     * The cards for the current category, filtered by the search box.
     *
     * <p>A non-empty search looks across every category rather than only the selected one: typing a
     * feature name should find it wherever it lives, which is the only reason to put a search box
     * next to a five item category list.
     */
    private void collect() {
        visible.clear();
        for (Option option : VulkanModConfig.OPTIONS) {
            if (!search.isEmpty()) {
                if (option.matches(search)) visible.add(option);
            } else if (option.category == category) {
                visible.add(option);
            }
        }
    }

    /**
     * The card above the grid for this category, or null.
     *
     * <p>Two things do not fit the toggle-or-slider grid: chunk loading cycles through named levels,
     * and GUI edit is an action rather than a setting. Both get a full width card at the top of
     * their own category instead of being forced into a control they are not.
     */
    private Option.Category topCard() {
        if (!search.isEmpty()) return null;
        if (category == Option.Category.PERFORMANCE) return Option.Category.PERFORMANCE;
        if (category == Option.Category.HUD) return Option.Category.HUD;
        return null;
    }

    private int gridTop() {
        return contentY + (topCard() != null ? CARD_H + CARD_GAP : 0);
    }

    private int gridHeight() {
        return contentH - (topCard() != null ? CARD_H + CARD_GAP : 0);
    }

    private int maxScroll() {
        int rows = (visible.size() + 1) / 2;
        return Math.max(0, rows * (CARD_H + CARD_GAP) - CARD_GAP - gridHeight());
    }

    /** Bounds of card {@code i} in the grid, or null when it is scrolled out of view. */
    private int[] cardBounds(int i) {
        int w = (contentW - CARD_GAP) / 2;
        int x = contentX + (i % 2) * (w + CARD_GAP);
        int y = gridTop() + (i / 2) * (CARD_H + CARD_GAP) - scroll;
        // Cards are drawn only when they fit entirely: there is no scissor rectangle here, so a
        // half scrolled card would spill past the panel edge instead of being clipped.
        if (y < gridTop() || y + CARD_H > gridTop() + gridHeight()) return null;
        return new int[] { x, y, x + w, y + CARD_H };
    }

    /** The slider's track inside a card: x0, y, x1. */
    private static int[] sliderTrack(int[] card) {
        return new int[] { card[0] + 7, card[1] + 33, card[2] - 7 };
    }

    // === rendering ===

    @Override
    public void render(int mouseX, int mouseY, float tickDelta) {
        collect();
        scroll = Math.min(scroll, maxScroll());

        fill(0, 0, this.width, this.height, DIM);

        roundRect(panelX, panelY, panelX + panelW, panelY + panelH, 4, PANEL);
        roundRect(panelX, panelY, panelX + panelW, panelY + HEADER_H - 1, 4, HEADER);
        fill(panelX, panelY + HEADER_H - 6, panelX + panelW, panelY + HEADER_H - 1, HEADER);
        fill(panelX, panelY + HEADER_H - 1, panelX + panelW, panelY + HEADER_H, LINE);
        fill(panelX, panelY + HEADER_H, panelX + SIDEBAR_W, panelY + panelH - 4, SIDEBAR);
        fill(panelX + SIDEBAR_W, panelY + HEADER_H, panelX + SIDEBAR_W + 1, panelY + panelH - 4, LINE);

        renderHeader();
        renderSidebar(mouseX, mouseY);
        renderContent(mouseX, mouseY);
    }

    private void renderHeader() {
        int y = panelY + 9;

        // The mark: an accent square with the initial, then the wordmark beside it.
        roundRect(panelX + 9, panelY + 8, panelX + 23, panelY + 22, 3, ACCENT);
        drawScaled("V", panelX + 14, panelY + 12, 0.9f, 0xFF15151A);

        this.textRenderer.drawWithShadow("VULKANMOD", panelX + 28, y, TEXT);
        drawScaled("CLIENT", panelX + 28, y + 10, 0.6f, ACCENT);

        String hint = "Right Shift to close";
        drawScaled(hint,
                   panelX + panelW - 9 - (int) (this.textRenderer.getStringWidth(hint) * 0.7f),
                   y + 2, 0.7f, MUTED);
    }

    private void renderSidebar(int mouseX, int mouseY) {
        int x = panelX + PAD;
        int w = SIDEBAR_W - PAD * 2;
        int y = panelY + HEADER_H + PAD;

        roundRect(x, y, x + w, y + 15, 3, searchFocused ? 0xFF2E2E38 : 0xFF25252C);
        if (searchFocused) fill(x + 3, y + 14, x + w - 3, y + 15, ACCENT);

        boolean placeholder = search.isEmpty() && !searchFocused;
        String caret = searchFocused && (System.currentTimeMillis() / 500) % 2 == 0 ? "_" : "";
        drawScaled(placeholder ? "Search..." : search + caret,
                   x + 5, y + 5, 0.75f, placeholder ? MUTED : TEXT);

        y += 22;
        for (Option.Category c : Option.Category.values()) {
            boolean selected = search.isEmpty() && c == category;
            boolean hovered = mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + ROW_H;

            if (selected || hovered) {
                roundRect(x, y, x + w, y + ROW_H, 3, selected ? 0xFF2C2C35 : 0xFF26262E);
            }
            if (selected) fill(x, y + 3, x + 2, y + ROW_H - 3, ACCENT);

            this.textRenderer.drawWithShadow(c.title, x + 8, y + 5, selected || hovered ? TEXT : MUTED);
            drawScaled(c.subtitle, x + 8, y + 16, 0.6f, MUTED);
            y += ROW_H + 2;
        }
    }

    private void renderContent(int mouseX, int mouseY) {
        Option.Category top = topCard();
        if (top == Option.Category.PERFORMANCE) renderChunkCard(mouseX, mouseY);
        else if (top == Option.Category.HUD) renderGuiEditCard(mouseX, mouseY);

        for (int i = 0; i < visible.size(); i++) {
            int[] b = cardBounds(i);
            if (b == null) continue;
            renderOptionCard(visible.get(i), b, mouseX, mouseY);
        }

        if (visible.isEmpty() && top == null) {
            drawScaled("Nothing matches \"" + search + "\"", contentX + 4, contentY + 6, 0.8f, MUTED);
        }

        int max = maxScroll();
        if (max > 0) {
            int trackX = panelX + panelW - 4;
            int trackTop = gridTop();
            int trackH = gridHeight();
            fill(trackX, trackTop, trackX + 2, trackTop + trackH, 0xFF2A2A32);
            int thumbH = Math.max(16, trackH * trackH / (trackH + max));
            int thumbY = trackTop + (trackH - thumbH) * scroll / max;
            roundRect(trackX, thumbY, trackX + 2, thumbY + thumbH, 1, ACCENT);
        }
    }

    private void renderChunkCard(int mouseX, int mouseY) {
        int x0 = contentX, y0 = contentY, x1 = contentX + contentW, y1 = contentY + CARD_H;
        boolean hovered = mouseX >= x0 && mouseX < x1 && mouseY >= y0 && mouseY < y1;
        roundRect(x0, y0, x1, y1, 3, hovered ? CARD_HOVER : CARD);

        this.textRenderer.drawWithShadow("Lazy Chunk Loading", x0 + 8, y0 + 7, TEXT);
        drawScaled("Meshes queued sections as one batch instead of a trickle every frame",
                   x0 + 8, y0 + 19, 0.6f, MUTED);

        String value = VulkanModConfig.lazyChunkLoading.display();
        int vw = (int) (this.textRenderer.getStringWidth(value) * 0.7f);
        int bx = Math.max(x0 + 8, x1 - 8 - vw - 8);
        roundRect(bx, y0 + 29, x1 - 8, y0 + 41, 3, 0xFF1C1C22);
        drawScaled(value, bx + 4, y0 + 32, 0.7f,
                   VulkanModConfig.lazyChunkLoading.ticks == 0 ? MUTED : ACCENT);
    }

    private void renderGuiEditCard(int mouseX, int mouseY) {
        int x0 = contentX, y0 = contentY, x1 = contentX + contentW, y1 = contentY + CARD_H;
        boolean hovered = mouseX >= x0 && mouseX < x1 && mouseY >= y0 && mouseY < y1;
        roundRect(x0, y0, x1, y1, 3, hovered ? CARD_HOVER : CARD);
        if (hovered) fill(x0, y0 + 4, x0 + 2, y1 - 4, ACCENT);

        this.textRenderer.drawWithShadow("GUI EDIT", x0 + 8, y0 + 9, TEXT);
        drawScaled("Place and resize everything this client draws on your screen",
                   x0 + 8, y0 + 22, 0.6f, MUTED);

        String action = "OPEN";
        int aw = (int) (this.textRenderer.getStringWidth(action) * 0.7f);
        roundRect(x1 - 12 - aw - 8, y0 + 16, x1 - 8, y0 + 30, 3, hovered ? ACCENT : 0xFF1C1C22);
        drawScaled(action, x1 - 12 - aw - 4, y0 + 20, 0.7f, hovered ? 0xFF15151A : MUTED);
    }

    private void renderOptionCard(Option option, int[] b, int mouseX, int mouseY) {
        boolean usable = option.enabled();
        boolean hovered = usable
                && mouseX >= b[0] && mouseX < b[2] && mouseY >= b[1] && mouseY < b[3];
        roundRect(b[0], b[1], b[2], b[3], 3, hovered ? CARD_HOVER : CARD);

        int titleColor = usable ? TEXT : DISABLED;
        int bodyColor = usable ? MUTED : DISABLED;

        if (option.kind == Option.Kind.SLIDER) {
            this.textRenderer.drawWithShadow(
                    this.textRenderer.trimToWidth(option.name, b[2] - b[0] - 40),
                    b[0] + 7, b[1] + 6, titleColor);

            String value = Integer.toString(option.number);
            drawScaled(value,
                       b[2] - 7 - (int) (this.textRenderer.getStringWidth(value) * 0.8f),
                       b[1] + 7, 0.8f, usable ? ACCENT : DISABLED);

            drawScaled(this.textRenderer.trimToWidth(option.description,
                                                     (int) ((b[2] - b[0] - 14) / 0.6f)),
                       b[0] + 7, b[1] + 19, 0.6f, bodyColor);

            int[] track = sliderTrack(b);
            roundRect(track[0], track[1], track[2], track[1] + 4, 2, TRACK);
            int filled = track[0] + Math.round((track[2] - track[0]) * option.fraction());
            if (filled > track[0]) {
                roundRect(track[0], track[1], filled, track[1] + 4, 2, usable ? ACCENT : DISABLED);
            }
            roundRect(filled - 3, track[1] - 3, filled + 3, track[1] + 7, 3,
                      usable ? 0xFFFFFFFF : DISABLED);
            return;
        }

        this.textRenderer.drawWithShadow(
                this.textRenderer.trimToWidth(option.name, b[2] - b[0] - 46),
                b[0] + 7, b[1] + 7, titleColor);

        // Descriptions are sentences, so they wrap rather than being cut off.
        List<String> lines = this.textRenderer.wrapLines(option.description,
                                                        (int) ((b[2] - b[0] - 14) / 0.6f));
        for (int i = 0; i < Math.min(3, lines.size()); i++) {
            drawScaled(lines.get(i), b[0] + 7, b[1] + 20 + i * 7, 0.6f, bodyColor);
        }

        drawToggle(b[2] - 30, b[1] + 7, option.value, usable);
    }

    /** A pill track with a knob that slides to the accent colour when on. */
    private void drawToggle(int x, int y, boolean on, boolean usable) {
        int w = 22, h = 11;
        roundRect(x, y, x + w, y + h, h / 2, on ? (usable ? ACCENT : DISABLED) : TRACK);
        int knobX = on ? x + w - h + 1 : x + 1;
        roundRect(knobX, y + 1, knobX + h - 2, y + h - 1, (h - 2) / 2,
                  on ? 0xFFFFFFFF : 0xFF8E8E9A);
    }

    // === primitives ===

    /**
     * A filled rectangle with its corners cut back along a circle.
     *
     * <p>Everything here is drawn from {@code fill}, so the rounding is a stack of horizontal
     * bands: each of the r rows at the top and bottom is inset by the horizontal distance from
     * the circle to the edge.
     */
    private static void roundRect(int x0, int y0, int x1, int y1, int r, int color) {
        if (x1 <= x0 || y1 <= y0) return;
        r = Math.min(r, Math.min((x1 - x0) / 2, (y1 - y0) / 2));
        if (r <= 0) {
            fill(x0, y0, x1, y1, color);
            return;
        }
        fill(x0, y0 + r, x1, y1 - r, color);
        for (int i = 0; i < r; i++) {
            double dy = r - i - 0.5;
            int inset = r - (int) Math.round(Math.sqrt(Math.max(0.0, r * r - dy * dy)));
            fill(x0 + inset, y0 + i, x1 - inset, y0 + i + 1, color);
            fill(x0 + inset, y1 - i - 1, x1 - inset, y1 - i, color);
        }
    }

    /**
     * Text below the font's natural size.
     *
     * <p>The bitmap font has one size, so anything smaller comes from the matrix. The position is
     * divided by the scale because the scale is applied before the draw.
     */
    private void drawScaled(String text, float x, float y, float scale, int color) {
        GlStateManager.pushMatrix();
        GlStateManager.scalef(scale, scale, 1.0f);
        this.textRenderer.drawWithShadow(text, x / scale, y / scale, color);
        GlStateManager.popMatrix();
    }

    // === input ===

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        if (button != 0 && button != 1) return;

        int sx = panelX + PAD;
        int sw = SIDEBAR_W - PAD * 2;
        int sy = panelY + HEADER_H + PAD;

        searchFocused = mouseX >= sx && mouseX < sx + sw && mouseY >= sy && mouseY < sy + 15;
        if (searchFocused) return;

        int y = sy + 22;
        for (Option.Category c : Option.Category.values()) {
            if (mouseX >= sx && mouseX < sx + sw && mouseY >= y && mouseY < y + ROW_H) {
                category = c;
                search = "";
                scroll = 0;
                return;
            }
            y += ROW_H + 2;
        }

        Option.Category top = topCard();
        if (top != null
                && mouseX >= contentX && mouseX < contentX + contentW
                && mouseY >= contentY && mouseY < contentY + CARD_H) {
            if (top == Option.Category.HUD) {
                this.client.openScreen(new HudEditScreen(this));
            } else {
                // Right click steps backwards, so a level overshot by one is one click away rather
                // than five.
                VulkanModConfig.lazyChunkLoading = button == 1
                        ? VulkanModConfig.lazyChunkLoading.previous()
                        : VulkanModConfig.lazyChunkLoading.next();
                VulkanModConfig.save();
            }
            return;
        }

        for (int i = 0; i < visible.size(); i++) {
            int[] b = cardBounds(i);
            if (b == null) continue;
            if (mouseX < b[0] || mouseX >= b[2] || mouseY < b[1] || mouseY >= b[3]) continue;

            Option option = visible.get(i);
            if (!option.enabled()) return;

            if (option.kind == Option.Kind.SLIDER) {
                draggingSlider = option;
                dragSlider(option, b, mouseX);
            } else {
                option.value = !option.value;
            }
            VulkanModConfig.save();
            return;
        }
    }

    @Override
    protected void mouseDragged(int mouseX, int mouseY, int button, long heldMs) {
        if (draggingSlider == null) return;
        // The card is looked up again rather than cached: scrolling during a drag would otherwise
        // leave the slider following a rectangle that has moved.
        for (int i = 0; i < visible.size(); i++) {
            if (visible.get(i) != draggingSlider) continue;
            int[] b = cardBounds(i);
            if (b != null) dragSlider(draggingSlider, b, mouseX);
            return;
        }
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int button) {
        if (draggingSlider != null) {
            draggingSlider = null;
            VulkanModConfig.save();
        }
    }

    private static void dragSlider(Option option, int[] card, int mouseX) {
        int[] track = sliderTrack(card);
        option.setFraction((mouseX - track[0]) / (float) Math.max(1, track[2] - track[0]));
    }

    @Override
    public void handleMouse() {
        super.handleMouse();
        int wheel = Mouse.getEventDWheel();
        if (wheel != 0) {
            scroll = Math.max(0,
                    Math.min(maxScroll(), scroll - Integer.signum(wheel) * (CARD_H / 2)));
        }
    }

    @Override
    protected void keyPressed(char chr, int keyCode) {
        // Right Shift is deliberately not handled here. It is the key that opens this screen, and
        // the press that opened it is still sitting in the keyboard queue when the screen goes up:
        // closing on it would close the menu in the same frame it appeared. Opening and closing
        // both live in SettingsKeyMixin's tick instead, where one edge is one toggle.
        if (keyCode == Keyboard.KEY_ESCAPE) {
            this.client.openScreen(null);
            return;
        }

        if (!searchFocused) return;

        if (keyCode == Keyboard.KEY_BACK) {
            if (!search.isEmpty()) search = search.substring(0, search.length() - 1);
            scroll = 0;
        } else if (keyCode == Keyboard.KEY_RETURN) {
            searchFocused = false;
        } else if (chr >= 32 && chr != 127 && search.length() < 24) {
            search += chr;
            scroll = 0;
        }
    }

    /** Opened mid-game, so the world should keep running behind it. */
    @Override
    public boolean shouldPauseGame() {
        return false;
    }
}
