package net.vulkanmod.mixin.perf;

import net.minecraft.entity.Entity;
import net.minecraft.entity.mob.MobEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.ExperienceOrbEntity;
import net.vulkanmod.vulkan.FrustumCuller;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Throttle entity ticking based on distance from the player.
 * Distant entities tick at reduced frequency — they still update physics
 * and AI, just less often. Players are never throttled.
 */
@Mixin(Entity.class)
public abstract class EntityTickMixin {

    @Shadow public double x;
    @Shadow public double y;
    @Shadow public double z;
    @Shadow public int ticksAlive;
    @Shadow public boolean removed;

    @Inject(method = "baseTick", at = @At("HEAD"), cancellable = true)
    private void onBaseTick(CallbackInfo ci) {
        Entity self = (Entity)(Object) this;

        // Never throttle players, items, or XP orbs
        if (self instanceof PlayerEntity) return;
        if (self instanceof ItemEntity) return;
        if (self instanceof ExperienceOrbEntity) return;

        double distSq = FrustumCuller.getDistanceSq(this.x, this.y, this.z);

        // > 48 blocks: tick every 2nd tick
        // > 80 blocks: tick every 4th tick
        int interval;
        if (distSq > 80.0 * 80.0) {
            interval = 4;
        } else if (distSq > 48.0 * 48.0) {
            interval = 2;
        } else {
            return; // close enough, full tick rate
        }

        if ((this.ticksAlive % interval) != 0) {
            ci.cancel();
        }
    }
}
