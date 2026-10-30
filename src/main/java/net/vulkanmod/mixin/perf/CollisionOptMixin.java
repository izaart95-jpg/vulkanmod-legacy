package net.vulkanmod.mixin.perf;

import net.minecraft.entity.Entity;
import net.minecraft.entity.player.PlayerEntity;
import net.vulkanmod.vulkan.FrustumCuller;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Simplify collision for distant entities.
 * Entity.move() calls World.doesBoxCollide() which iterates block AABBs
 * in the movement path — very expensive for distant entities nobody sees.
 * For distant mobs, skip the full collision and just do a simple offset.
 */
@Mixin(Entity.class)
public abstract class CollisionOptMixin {

    @Shadow public double x;
    @Shadow public double y;
    @Shadow public double z;
    @Shadow public boolean onGround;
    @Shadow public boolean noClip;
    @Shadow public double velocityX;
    @Shadow public double velocityY;
    @Shadow public double velocityZ;

    @Shadow public abstract net.minecraft.util.math.Box getBoundingBox();
    @Shadow public abstract void setBoundingBox(net.minecraft.util.math.Box box);
    @Shadow public abstract void updatePosition(double x, double y, double z);

    @Inject(method = "move", at = @At("HEAD"), cancellable = true)
    private void onMove(double dx, double dy, double dz, CallbackInfo ci) {
        Entity self = (Entity)(Object) this;

        // Never simplify for players or noClip entities
        if (self instanceof PlayerEntity) return;
        if (this.noClip) return;

        double distSq = FrustumCuller.getDistanceSq(this.x, this.y, this.z);

        // Only simplify for entities > 48 blocks away
        if (distSq <= 48.0 * 48.0) return;

        // Simple move: offset bounding box and position directly.
        // Apply gravity approximation — keep onGround state reasonable.
        net.minecraft.util.math.Box box = this.getBoundingBox();
        this.setBoundingBox(box.offset(dx, dy, dz));

        // Update entity position from bounding box center
        double newX = (this.getBoundingBox().minX + this.getBoundingBox().maxX) / 2.0;
        double newY = this.getBoundingBox().minY;
        double newZ = (this.getBoundingBox().minZ + this.getBoundingBox().maxZ) / 2.0;
        this.updatePosition(newX, newY, newZ);

        // Approximate ground check: if moving down and was on ground, stay on ground
        if (dy < 0 && this.onGround) {
            this.onGround = true;
        } else if (dy != 0) {
            this.onGround = false;
        }

        ci.cancel();
    }
}
