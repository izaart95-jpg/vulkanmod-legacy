package net.vulkanmod.mixin.render;

import net.minecraft.client.MinecraftClient;
import net.vulkanmod.config.VulkanModSettingsScreen;
import org.lwjgl.input.Keyboard;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Opens the settings menu on Right Shift. */
@Mixin(MinecraftClient.class)
public class SettingsKeyMixin {

    // Edge triggered: without this the menu would reopen every tick the key is held, and the
    // press that opens it would immediately be seen again and close it.
    @Unique private boolean vulkanmodKeyWasDown = false;
    @Unique private boolean vulkanmodForcedShadowsOff = false;
    @Unique private boolean vulkanmodSavedEntityShadows = true;
    @Unique private int vulkanmodSavedAo = 2;
    @Unique private boolean vulkanmodForcedFullbright = false;
    @Unique private float vulkanmodSavedGamma = 0.0f;

    /** Ambient occlusion is baked into chunk meshes, so changing it means rebuilding them. */
    @Unique
    private void vulkanmodReloadTerrain(MinecraftClient mc) {
        if (mc.worldRenderer != null) mc.worldRenderer.reload();
    }

    @Inject(method = "tick", at = @At("HEAD"))
    private void vulkanmodSettingsKey(CallbackInfo ci) {
        net.vulkanmod.config.VulkanModConfig.tickCounter++;
        net.vulkanmod.config.WindowModeController.tick();

        MinecraftClient mc = (MinecraftClient) (Object) this;

        // Drive vanilla's own switches rather than only cancelling the shadow render.
        //
        // Two separate things read as shadows in 1.8.9. entityShadows is the blob under mobs and
        // players; the dispatcher tests it before it ever calls the shadow code, so clearing it
        // cannot be missed. Ambient occlusion is the darkening in corners and under overhangs,
        // which is baked into the chunk mesh, so it needs the terrain rebuilt to take effect.
        //
        // Turning ambient occlusion off is also the single largest saving available here: it is
        // about 95% of chunk meshing time, and with it off the mesher takes the flat lighting
        // path instead.
        //
        // The vanilla values are saved before being overridden and put back when this menu turns
        // shadows on again, so using this menu does not silently rewrite the video settings.
        if (mc.options != null) {
            if (!net.vulkanmod.config.VulkanModConfig.SHADOWS.value) {
                if (!vulkanmodForcedShadowsOff) {
                    vulkanmodSavedEntityShadows = mc.options.entityShadows;
                    vulkanmodSavedAo = mc.options.ao;
                    vulkanmodForcedShadowsOff = true;
                    vulkanmodReloadTerrain(mc);
                }
                mc.options.entityShadows = false;
                mc.options.ao = 0;
            } else if (vulkanmodForcedShadowsOff) {
                mc.options.entityShadows = vulkanmodSavedEntityShadows;
                mc.options.ao = vulkanmodSavedAo;
                vulkanmodForcedShadowsOff = false;
                vulkanmodReloadTerrain(mc);
            }
        }

        // Fullbright rides on the brightness slider rather than on the light texture: the slider
        // already feeds the lightmap the renderer samples, so pinning it high needs no new state
        // and is undone exactly by putting the player's own value back.
        if (mc.options != null) {
            if (net.vulkanmod.config.VulkanModConfig.FULLBRIGHT.value) {
                if (!vulkanmodForcedFullbright) {
                    vulkanmodSavedGamma = mc.options.gamma;
                    vulkanmodForcedFullbright = true;
                }
                mc.options.gamma = 100.0f;
            } else if (vulkanmodForcedFullbright) {
                mc.options.gamma = vulkanmodSavedGamma;
                vulkanmodForcedFullbright = false;
            }
        }

        // Right Shift toggles the menu, and this is the only place that decides so: handling the
        // close inside the screen instead would see the very press that opened it, still queued in
        // the keyboard buffer, and shut the menu in the same frame it appeared.
        boolean down = Keyboard.isKeyDown(Keyboard.KEY_RSHIFT);
        if (down && !vulkanmodKeyWasDown) {
            if (mc.currentScreen instanceof VulkanModSettingsScreen) {
                mc.openScreen(null);
            } else if (mc.currentScreen == null && mc.world != null) {
                // Only from in-game, so it cannot open over another screen or a text field.
                mc.openScreen(new VulkanModSettingsScreen());
            }
        }
        vulkanmodKeyWasDown = down;
    }
}
