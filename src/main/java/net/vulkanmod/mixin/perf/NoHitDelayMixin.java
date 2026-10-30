package net.vulkanmod.mixin.perf;

import net.minecraft.client.MinecraftClient;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Removes the 1.8.9 missed-hit penalty delay (leftClickCounter / attackCooldown).
 * When the player clicks and misses an entity, Minecraft sets a 10-tick cooldown
 * during which further left-clicks are ignored. This mixin resets that cooldown
 * to 0 every time an attack is attempted, ensuring 1:1 CPS registration.
 */
@Mixin(MinecraftClient.class)
public abstract class NoHitDelayMixin {

    @Shadow private int attackCooldown; // Maps to field_3781 / leftClickCounter

    @Inject(method = "doAttack", at = @At("HEAD"))
    private void removeMissDelay(CallbackInfo ci) {
        // Reset the click cooldown to 0 immediately when a click happens.
        this.attackCooldown = 0;
    }
}
