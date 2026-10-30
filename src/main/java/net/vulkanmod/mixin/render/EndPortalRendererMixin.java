package net.vulkanmod.mixin.render;

import com.mojang.blaze3d.platform.GlStateManager;
import net.minecraft.block.entity.EndPortalBlockEntity;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.render.block.entity.EndPortalBlockEntityRenderer;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.vulkanmod.Initializer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Draws the end portal without fixed function texture coordinate generation.
 *
 * <p>Vanilla builds the portal's starfield out of sixteen layers whose texture coordinates come
 * from glTexGen in GL_EYE_LINEAR mode, each layer offset by its own GL_TEXTURE matrix. Texgen is a
 * fixed function stage that disappeared with programmable pipelines and has no counterpart in this
 * backend, so none of the sixteen layers produced usable coordinates and the portal came out
 * blank -- the block appeared not to load at all.
 *
 * <p>This draws the same idea with coordinates computed on the CPU: a black base, then a handful
 * of scrolling, additively blended copies of the portal texture at different scales and speeds.
 * The coordinates are anchored to the block's world position, so the pattern stays put as you walk
 * around rather than swimming with the camera.
 */
@Mixin(EndPortalBlockEntityRenderer.class)
public class EndPortalRendererMixin {

    @Unique private static final Identifier VULKANMOD_PORTAL_TEXTURE =
            new Identifier("textures/entity/end_portal.png");

    @Unique private static final Identifier VULKANMOD_SKY_TEXTURE =
            new Identifier("textures/environment/end_sky.png");

    /** Top of the portal surface, matching vanilla's inset below the full block. */
    @Unique private static final double VULKANMOD_SURFACE = 0.75;

    @Unique private static final int VULKANMOD_LAYERS = 6;

    @Inject(method = "render(Lnet/minecraft/block/entity/EndPortalBlockEntity;DDDFI)V",
            at = @At("HEAD"), cancellable = true)
    private void vulkanmodRenderPortal(EndPortalBlockEntity entity, double x, double y, double z,
                                       float tickDelta, int destroyStage, CallbackInfo ci) {
        if (!Initializer.vulkanReady) return;
        ci.cancel();

        BlockPos pos = entity.getPos();
        // Wrapped so the pattern still lines up between neighbouring blocks but the float
        // coordinates stay small far from spawn.
        float u0 = Math.floorMod(pos.getX(), 16);
        float v0 = Math.floorMod(pos.getZ(), 16);

        // Wall clock rather than world time: the layers only need a monotonic scroll, and world
        // time wraps every Minecraft day, which would make the pattern jump.
        double time = System.nanoTime() / 1.0e9;

        GlStateManager.disableLighting();
        GlStateManager.enableBlend();
        GlStateManager.depthMask(false);

        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();

        // The end sky as an opaque floor, darkened, so the portal reads as a hole rather than as
        // the block below it. This is the same texture vanilla uses for its own bottom layer.
        GlStateManager.blendFunc(770, 771);
        MinecraftClient.getInstance().getTextureManager().bindTexture(VULKANMOD_SKY_TEXTURE);
        buffer.begin(7, VertexFormats.POSITION_TEXTURE_COLOR);
        texturedQuad(buffer, x, y + VULKANMOD_SURFACE, z,
                     u0 / 16.0f, v0 / 16.0f, 1.0f / 16.0f,
                     0.12f, 0.10f, 0.18f, 1.0f);
        tessellator.draw();

        // Starfield: additive layers so they build up brightness where they overlap, which is what
        // gives the portal its depth without any real parallax.
        MinecraftClient.getInstance().getTextureManager().bindTexture(VULKANMOD_PORTAL_TEXTURE);
        GlStateManager.blendFunc(770, 1);

        for (int layer = 0; layer < VULKANMOD_LAYERS; layer++) {
            float scale = 4.0f + layer * 3.0f;
            float speed = 0.01f + layer * 0.012f;
            float offset = (float) ((time * speed) % 1.0);
            float fade = 1.0f - layer / (float) VULKANMOD_LAYERS;

            // Vanilla tints the deeper layers towards blue and purple; the same ramp keeps the
            // colour recognisable.
            float r = 0.10f + 0.55f * fade;
            float g = 0.05f + 0.25f * fade;
            float b = 0.35f + 0.65f * fade;
            float a = 0.25f + 0.45f * fade;

            buffer.begin(7, VertexFormats.POSITION_TEXTURE_COLOR);
            texturedQuad(buffer, x, y + VULKANMOD_SURFACE, z,
                         (u0 / 16.0f) * scale + offset, (v0 / 16.0f) * scale - offset,
                         scale / 16.0f, r, g, b, a);
            tessellator.draw();
        }

        GlStateManager.blendFunc(770, 771);
        GlStateManager.depthMask(true);
        GlStateManager.disableBlend();
        GlStateManager.enableLighting();
    }

    @Unique
    private static void texturedQuad(BufferBuilder buffer, double x, double y, double z,
                                     float u, float v, float span,
                                     float r, float g, float b, float a) {
        buffer.vertex(x, y, z).texture(u, v).color(r, g, b, a).next();
        buffer.vertex(x, y, z + 1.0).texture(u, v + span).color(r, g, b, a).next();
        buffer.vertex(x + 1.0, y, z + 1.0).texture(u + span, v + span).color(r, g, b, a).next();
        buffer.vertex(x + 1.0, y, z).texture(u + span, v).color(r, g, b, a).next();
    }
}
