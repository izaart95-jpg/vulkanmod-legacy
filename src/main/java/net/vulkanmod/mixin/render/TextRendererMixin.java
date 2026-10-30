package net.vulkanmod.mixin.render;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.texture.TextureManager;
import net.minecraft.util.Identifier;
import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.Drawer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(TextRenderer.class)
public class TextRendererMixin {

    @Shadow private float red;
    @Shadow private float green;
    @Shadow private float blue;
    @Shadow private float alpha;
    @Shadow private boolean bold;
    @Shadow private int[] characterWidths;
    @Shadow private float x;
    @Shadow private float y;
    @Shadow private TextureManager textureManager;
    @Shadow private Identifier fontTexture;

    @Unique private static boolean batchActive = false;
    @Unique private static int batchQuadCount = 0;
    @Unique private static int batchDepth = 0;

    @Inject(method = "draw(Ljava/lang/String;Z)V", at = @At("HEAD"))
    private void onDrawStringHead(String text, boolean shadow, CallbackInfo ci) {
        if (!Initializer.vulkanReady) return;
        Drawer drawer = Drawer.getInstance();
        if (!drawer.isRenderPassActive()) return;

        batchDepth++;
        if (batchActive) return;

        this.textureManager.bindTexture(this.fontTexture);

        batchActive = true;
        batchQuadCount = 0;
        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();
        buf.begin(7, VertexFormats.POSITION_TEXTURE_COLOR);
    }

    @Inject(method = "draw(Ljava/lang/String;Z)V", at = @At("RETURN"))
    private void onDrawStringReturn(String text, boolean shadow, CallbackInfo ci) {
        if (batchDepth > 0) batchDepth--;
        if (batchDepth > 0) return;
        if (!batchActive) return;
        batchActive = false;

        if (batchQuadCount > 0) {
            Tessellator.getInstance().draw();
        } else {
            try { Tessellator.getInstance().getBuffer().end(); } catch (Exception e) {}
        }
        batchQuadCount = 0;
    }

    @Inject(method = "drawLayerNormal", at = @At("HEAD"), cancellable = true)
    private void onDrawLayerNormal(int charIndex, boolean italic, CallbackInfoReturnable<Float> cir) {
        if (!Initializer.vulkanReady) {
            // If batch was somehow started, cancel to prevent vanilla from corrupting it
            if (batchActive) {
                cir.setReturnValue(charIndex >= 0 && charIndex < 256 ? (float) characterWidths[charIndex] : 0.0f);
            }
            return;
        }

        if (charIndex < 0 || charIndex >= 256) {
            if (batchActive) cir.setReturnValue(0.0f);
            return;
        }

        float charWidth = characterWidths[charIndex];
        if (charWidth <= 0) {
            cir.setReturnValue(charWidth);
            return;
        }

        // If batch is active, ALWAYS cancel the original method to prevent vanilla
        // begin/end calls from corrupting the shared buffer mid-batch.
        if (batchActive) {
            try {
                addCharQuads(charIndex, charWidth, italic);
            } catch (Exception e) {
                // Buffer overflow or invalid state — skip this character safely
            }
            cir.setReturnValue(charWidth);
            return;
        }

        // Unbatched fallback — only when no batch is active
        Drawer drawer = Drawer.getInstance();
        if (!drawer.isRenderPassActive()) return; // let vanilla handle it

        this.textureManager.bindTexture(this.fontTexture);
        Tessellator tess = Tessellator.getInstance();
        BufferBuilder buf = tess.getBuffer();
        try {
            buf.begin(7, VertexFormats.POSITION_TEXTURE_COLOR);
            addCharQuadsToBuffer(buf, charIndex, charWidth, italic);
            tess.draw();

            if (this.bold) {
                buf.begin(7, VertexFormats.POSITION_TEXTURE_COLOR);
                addBoldQuadsToBuffer(buf, charIndex, charWidth, italic);
                tess.draw();
            }
        } catch (Exception e) {
            // Recover from any buffer state issues
            try { buf.end(); } catch (Exception ignored) {}
        }

        cir.setReturnValue(charWidth);
    }

    @Unique
    private void addCharQuads(int charIndex, float charWidth, boolean italic) {
        BufferBuilder buf = Tessellator.getInstance().getBuffer();
        addCharQuadsToBuffer(buf, charIndex, charWidth, italic);
        batchQuadCount++;

        if (this.bold) {
            addBoldQuadsToBuffer(buf, charIndex, charWidth, italic);
            batchQuadCount++;
        }
    }

    @Unique
    private void addCharQuadsToBuffer(BufferBuilder buf, int charIndex, float charWidth, boolean italic) {
        float f = (float)(charIndex % 16 * 8);
        float g = (float)(charIndex / 16 * 8);
        float h = italic ? 1.0f : 0.0f;
        float r = this.red, gr = this.green, b = this.blue;
        float a = this.alpha > 0.0f ? this.alpha : 1.0f;
        float w = charWidth - 1.0f;
        float px = this.x, py = this.y;
        float u0 = f / 128.0f, v0 = g / 128.0f;
        float u1 = (f + w) / 128.0f, v1 = (g + 7.99f) / 128.0f;

        buf.vertex(px + h,     py,        0.0).texture(u0, v0).color(r, gr, b, a).next();
        buf.vertex(px - h,     py + 7.99f, 0.0).texture(u0, v1).color(r, gr, b, a).next();
        buf.vertex(px + w - h, py + 7.99f, 0.0).texture(u1, v1).color(r, gr, b, a).next();
        buf.vertex(px + w + h, py,        0.0).texture(u1, v0).color(r, gr, b, a).next();
    }

    @Unique
    private void addBoldQuadsToBuffer(BufferBuilder buf, int charIndex, float charWidth, boolean italic) {
        float f = (float)(charIndex % 16 * 8);
        float g = (float)(charIndex / 16 * 8);
        float h = italic ? 1.0f : 0.0f;
        float r = this.red, gr = this.green, b = this.blue;
        float a = this.alpha > 0.0f ? this.alpha : 1.0f;
        float w = charWidth - 1.0f;
        float px = this.x, py = this.y;
        float u0 = f / 128.0f, v0 = g / 128.0f;
        float u1 = (f + w) / 128.0f, v1 = (g + 7.99f) / 128.0f;

        buf.vertex(px + 1.0f + h,     py,        0.0).texture(u0, v0).color(r, gr, b, a).next();
        buf.vertex(px + 1.0f - h,     py + 7.99f, 0.0).texture(u0, v1).color(r, gr, b, a).next();
        buf.vertex(px + w + 1.0f - h, py + 7.99f, 0.0).texture(u1, v1).color(r, gr, b, a).next();
        buf.vertex(px + w + 1.0f + h, py,        0.0).texture(u1, v0).color(r, gr, b, a).next();
    }
}
