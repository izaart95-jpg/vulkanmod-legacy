package net.vulkanmod.mixin.perf;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Fixes the 1.8.9 sprint reset bug: vanilla unconditionally calls
 * setSprinting(false) during PlayerEntity.attack(), which breaks
 * combos (W-Tap) by cancelling sprint and sending a STOP_SPRINTING
 * packet even when the player is still holding forward.
 *
 * This redirect preserves sprint state if the forward key is held,
 * only allowing the reset when the player has actually stopped moving.
 */
@Mixin(PlayerEntity.class)
public abstract class SprintFixMixin {

    @Redirect(
            method = "attack",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/entity/player/PlayerEntity;setSprinting(Z)V"
            )
    )
    private void preventSprintReset(PlayerEntity self, boolean sprinting) {
        // Vanilla always passes false here. Only allow the reset if
        // the player isn't actively pressing forward.
        if (sprinting) {
            // If vanilla ever calls setSprinting(true) in attack, let it through
            self.setSprinting(true);
            return;
        }

        MinecraftClient mc = MinecraftClient.getInstance();
        boolean forwardHeld = mc.options.keyForward.isPressed();

        if (!forwardHeld) {
            // Player released W — allow the sprint cancel
            self.setSprinting(false);
        }
        // Otherwise: W is held, keep sprinting — don't send STOP_SPRINTING
    }
}
