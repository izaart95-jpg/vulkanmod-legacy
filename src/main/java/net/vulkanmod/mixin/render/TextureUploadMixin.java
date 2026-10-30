package net.vulkanmod.mixin.render;

import net.minecraft.client.texture.TextureUtil;
import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.Drawer;
import net.vulkanmod.vulkan.VRenderSystem;
import net.vulkanmod.vulkan.texture.GLTextureCache;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Feeds int[]-backed texture uploads straight into the Vulkan texture cache.
 *
 * <p>{@code TextureUtil.method_5861} is the choke point every {@code NativeImageBackedTexture}
 * goes through, the lightmap included. Catching the pixels here means the Vulkan copy is built
 * from the array MC already has in memory, instead of being recovered afterwards with
 * {@code glGetTexImage} -- which is a hard pipeline sync against the GL driver, and for the
 * lightmap was happening in the middle of the frame twice a second.
 */
@Mixin(TextureUtil.class)
public class TextureUploadMixin {

    @Inject(method = "method_5861", at = @At("RETURN"))
    private static void onUploadPixels(int glId, int[] pixels, int width, int height, CallbackInfo ci) {
        if (!Initializer.vulkanReady) return;
        GLTextureCache.uploadFromPixels(glId, pixels, width, height);
    }

    /**
     * Animated sprite frames. This is the mipmap upload path -- it writes a sub-rectangle of the
     * currently bound texture, which for animations is the block atlas. All the levels
     * Minecraft supplies are mirrored, so distant animated blocks stay in step with near ones.
     */
    @Inject(method = "method_7027", at = @At("RETURN"))
    private static void onUploadMipmap(int[][] data, int width, int height, int xOffset, int yOffset,
                                       boolean blur, boolean clamp, CallbackInfo ci) {
        if (!Initializer.vulkanReady) return;
        if (data == null || data.length == 0) return;

        // Nothing is on screen: skip the transfer entirely rather than pushing frames nobody
        // will see. This is the cheap half of OptiFine's SmartAnimations; the other half needs
        // per-sprite visibility plumbed through the chunk builder.
        if (!Drawer.getInstance().isRenderPassActive()) return;

        GLTextureCache.uploadSubRegion(VRenderSystem.getBoundTextureId(), data,
                width, height, xOffset, yOffset);
    }
}
