package net.vulkanmod.mixin.feature;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.client.util.Window;
import net.vulkanmod.config.ClickCounter;
import net.vulkanmod.config.HudElement;
import net.vulkanmod.config.HudRenderer;
import net.vulkanmod.config.VulkanModConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The HUD category, plus the vanilla overlays that can be switched off.
 *
 * <p>The movable readouts are drawn after vanilla's own HUD so nothing paints over them, and hide
 * while the debug screen is open rather than fighting it for the corner.
 */
@Mixin(InGameHud.class)
public class InGameHudMixin {

    @Shadow private long heartJumpEndTick;

    @Inject(method = "renderBossBar", at = @At("HEAD"), cancellable = true)
    private void vulkanmodBossBar(CallbackInfo ci) {
        if (!VulkanModConfig.BOSS_BAR.value) ci.cancel();
    }

    @Inject(method = "renderVignetteOverlay", at = @At("HEAD"), cancellable = true)
    private void vulkanmodVignette(CallbackInfo ci) {
        if (!VulkanModConfig.VIGNETTE.value) ci.cancel();
    }

    /**
     * The 1.7 health bar.
     *
     * <p>1.8 added a bounce: taking damage sets heartJumpEndTick and the hearts jitter up a pixel
     * until it passes. Clearing it before the bar is drawn leaves the row still, which is the whole
     * of the difference from 1.7.
     */
    @Inject(method = "renderStatusBars", at = @At("HEAD"))
    private void vulkanmodHealthBar(CallbackInfo ci) {
        if (VulkanModConfig.HEALTH_BAR.value) this.heartJumpEndTick = 0L;
    }

    /**
     * Draws the movable overlays after vanilla's HUD, so nothing paints over them.
     *
     * <p>Positions and sizes live on the elements themselves and are shared with the edit screen,
     * so what you arranged there is what appears here.
     */
    @Inject(method = "render", at = @At("RETURN"))
    private void vulkanmodOverlays(float tickDelta, CallbackInfo ci) {
        // Per frame rather than per tick: at 20 Hz the counter would miss most individual clicks.
        ClickCounter.poll();

        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.options == null || mc.options.debugEnabled) return;

        Window window = new Window(mc);
        int width = (int) window.getScaledWidth();
        int height = (int) window.getScaledHeight();
        for (HudElement element : VulkanModConfig.HUD_ELEMENTS) {
            HudRenderer.render(mc, element, width, height, false);
        }
    }
}
