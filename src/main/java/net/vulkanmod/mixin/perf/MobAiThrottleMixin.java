package net.vulkanmod.mixin.perf;

import net.minecraft.entity.mob.MobEntity;
import net.vulkanmod.vulkan.FrustumCuller;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Throttle AI goal ticking for distant mobs.
 * tickNewAi() runs GoalSelector.tick() which is one of the heaviest
 * per-entity operations (pathfinding, target selection, etc.).
 * For distant mobs, run AI every Nth tick instead of every tick.
 */
@Mixin(MobEntity.class)
public abstract class MobAiThrottleMixin {

    @Inject(method = "tickNewAi", at = @At("HEAD"), cancellable = true)
    private void onTickNewAi(CallbackInfo ci) {
        MobEntity self = (MobEntity)(Object) this;

        double distSq = FrustumCuller.getDistanceSq(self.x, self.y, self.z);

        // > 32 blocks: AI every 2nd tick
        // > 64 blocks: AI every 4th tick
        int interval;
        if (distSq > 64.0 * 64.0) {
            interval = 4;
        } else if (distSq > 32.0 * 32.0) {
            interval = 2;
        } else {
            return; // close, full AI rate
        }

        if ((self.ticksAlive % interval) != 0) {
            ci.cancel();
        }
    }
}
