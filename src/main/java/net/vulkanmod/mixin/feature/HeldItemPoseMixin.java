package net.vulkanmod.mixin.feature;

import com.mojang.blaze3d.platform.GlStateManager;
import net.minecraft.client.render.item.HeldItemRenderer;
import net.minecraft.client.render.model.json.ModelTransformation;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.item.SwordItem;
import net.vulkanmod.config.VulkanModConfig;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * The 1.7 first person poses, from the Animations category.
 *
 * <p>1.8 added a set of transformations on top of the plain "hold the item out" pose that 1.7 had:
 * the bow tips towards the camera as it is drawn, blocking swings the sword across the screen, and
 * eating rocks the food back and forth. Each of those is a self contained private method, so
 * restoring the 1.7 look is a matter of not applying them.
 *
 * <p>The rod and sword options work the other way round -- 1.8 moved those two items rather than
 * animating them, so they get an offset back towards where 1.7 held them.
 */
@Mixin(HeldItemRenderer.class)
public class HeldItemPoseMixin {

    @Inject(method = "applyBowTransformation", at = @At("HEAD"), cancellable = true)
    private void vulkanmodArcPosition(CallbackInfo ci) {
        if (VulkanModConfig.ARC_POSITION.value) ci.cancel();
    }

    @Inject(method = "applySwordBlockTransformation", at = @At("HEAD"), cancellable = true)
    private void vulkanmodGuardLook(CallbackInfo ci) {
        if (VulkanModConfig.GUARD_LOOK.value) ci.cancel();
    }

    @Inject(method = "applyEatOrDrinkTransformation", at = @At("HEAD"), cancellable = true)
    private void vulkanmodFood(CallbackInfo ci) {
        if (VulkanModConfig.FOOD.value) ci.cancel();
    }

    /**
     * Nudges the fishing rod and swords back to their 1.7 resting position.
     *
     * <p>Applied at the head of the item render so it composes with whatever transformation the
     * item's model declares, rather than replacing it: the offsets are small and relative, so a
     * resource pack that moves the item still moves it.
     */
    @Inject(method = "renderItem(Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/item/ItemStack;Lnet/minecraft/client/render/model/json/ModelTransformation$Mode;)V",
            at = @At("HEAD"))
    private void vulkanmodItemPose(LivingEntity entity, ItemStack stack,
                                   ModelTransformation.Mode mode, CallbackInfo ci) {
        if (stack == null || mode == null || !mode.name().startsWith("FIRST_PERSON")) return;

        if (VulkanModConfig.ROD_POSITION.value && stack.getItem() == Items.FISHING_ROD) {
            // 1.7 held the rod lower and further forward, with the tip angled down.
            GlStateManager.translatef(0.0f, -0.06f, 0.08f);
            GlStateManager.rotatef(-9.0f, 1.0f, 0.0f, 0.0f);
        }

        if (VulkanModConfig.SWORD_MOVES.value && stack.getItem() instanceof SwordItem) {
            // 1.7 carried the sword flatter and slightly further from the centre of the screen.
            GlStateManager.translatef(0.02f, -0.02f, 0.0f);
            GlStateManager.rotatef(-6.0f, 0.0f, 0.0f, 1.0f);
        }
    }
}
