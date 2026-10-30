package net.vulkanmod.mixin.perf;

import net.minecraft.entity.ItemEntity;
import net.minecraft.util.math.Box;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * Increase item merge search radius from 0.5 blocks to 3.0 blocks.
 * Items on the ground will combine from further away, reducing entity count
 * and tick overhead in farms or mob grinders.
 */
@Mixin(ItemEntity.class)
public abstract class ItemEntityMergeMixin {

    private static final double MERGE_RADIUS = 3.0;

    @Redirect(method = "tryMerge()V",
              at = @At(value = "INVOKE",
                       target = "Lnet/minecraft/util/math/Box;expand(DDD)Lnet/minecraft/util/math/Box;"))
    private Box widenMergeSearch(Box box, double x, double y, double z) {
        return box.expand(MERGE_RADIUS, MERGE_RADIUS, MERGE_RADIUS);
    }
}
