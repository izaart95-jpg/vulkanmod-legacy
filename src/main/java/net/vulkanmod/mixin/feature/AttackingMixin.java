package net.vulkanmod.mixin.feature;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.ClientPlayerEntity;
import net.vulkanmod.config.VulkanModConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Restores hitting blocks while eating, blocking or drawing a bow.
 *
 * <p>1.8 gates block breaking on {@code !player.isUsingItem()}, so holding right click stops left
 * click from doing anything. 1.7 had no such gate. Redirecting the single test is enough; the rest
 * of the break path is unchanged, so timing and the server side handshake stay vanilla.
 */
@Mixin(MinecraftClient.class)
public class AttackingMixin {

    @Redirect(method = "handleBlockBreaking",
            at = @At(value = "INVOKE",
                     target = "Lnet/minecraft/entity/player/ClientPlayerEntity;isUsingItem()Z"))
    private boolean vulkanmodAllowAttackWhileUsing(ClientPlayerEntity player) {
        return !VulkanModConfig.ATTACKING.value && player.isUsingItem();
    }
}
