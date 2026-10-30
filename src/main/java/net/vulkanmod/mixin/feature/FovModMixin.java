package net.vulkanmod.mixin.feature;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.entity.player.ClientPlayerEntity;
import net.vulkanmod.config.VulkanModConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * A fixed field of view per movement state.
 *
 * <p>Vanilla scales the FOV continuously with how fast you are moving, easing between values over
 * several ticks. That easing is the wobble players turn off: it makes the world appear to breathe
 * whenever you start or stop sprinting, and it moves everything on screen slightly while you are
 * trying to aim at it. With this on, each state maps to one number and switching states switches
 * the value outright.
 *
 * <p>Injected at the return so vanilla's own work -- the third person and hand variants, and the
 * nausea and underwater distortions layered on afterwards -- is left alone; only the number this
 * method arrived at is replaced.
 */
@Mixin(GameRenderer.class)
public class FovModMixin {

    /** Effect ids in 1.8.9; there is no named constant for these on this version. */
    private static final int SPEED = 1;
    private static final int SLOWNESS = 2;

    @Inject(method = "getFov", at = @At("RETURN"), cancellable = true)
    private void vulkanmodFixedFov(float tickDelta, boolean changingFov,
                                   CallbackInfoReturnable<Float> cir) {
        if (!VulkanModConfig.FOV_MOD.value) return;

        ClientPlayerEntity player = MinecraftClient.getInstance().player;
        if (player == null) return;

        // Most specific state first: a potion is a deliberate, visible condition, so it should not
        // be masked by the fact that you also happen to be sprinting under it.
        int fov;
        if (player.hasStatusEffect(SLOWNESS)) {
            fov = VulkanModConfig.FOV_SLOWNESS.number;
        } else if (player.hasStatusEffect(SPEED)) {
            fov = VulkanModConfig.FOV_SPEED.number;
        } else if (player.isSprinting()) {
            fov = VulkanModConfig.FOV_SPRINTING.number;
        } else if (player.abilities != null && player.abilities.flying) {
            fov = VulkanModConfig.FOV_FLYING.number;
        } else {
            fov = VulkanModConfig.FOV_DEFAULT.number;
        }

        cir.setReturnValue((float) fov);
    }
}
