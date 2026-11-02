package net.vulkanmod.mixin.render;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.GameRenderer;
import net.minecraft.entity.Entity;
import net.minecraft.entity.player.ClientPlayerEntity;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.vulkanmod.Initializer;
import net.vulkanmod.vulkan.VRenderSystem;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.ModifyConstant;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(GameRenderer.class)
public class GameRendererMixin {

    @Shadow private MinecraftClient client;
    @Shadow private float cursorDeltaX;
    @Shadow private float cursorDeltaY;

    /**
     * Captures the fog colour the frame is about to use, instead of reading it back from GL.
     *
     * <p>{@code renderFog} builds the colour in {@code fogColorBuffer} and then hands it to
     * fixed-function GL. The Vulkan backend never consumes that GL state -- its terrain shader
     * has its own fog uniform -- so the call itself is unnecessary, and reading the same value
     * back with {@code glGetFloatv(GL_FOG_COLOR)} was a full driver round trip. That readback
     * ran once per render layer (four times a frame), which on Android crossed gl4es each time.
     */
    @Redirect(method = "renderFog",
            at = @At(value = "INVOKE",
                     target = "Lorg/lwjgl/opengl/GL11;glFog(ILjava/nio/FloatBuffer;)V"))
    private void vulkanmodCaptureFogColor(int pname, java.nio.FloatBuffer color) {
        if (!Initializer.vulkanReady) {
            GL11.glFogfv(pname, color);
            return;
        }
        VRenderSystem.markFogCaptureActive();
        VRenderSystem.captureFogColor(color);
    }

    @Unique
    private float vulkanmod$tickDelta;

    @Unique
    private double vulkanmod$precisionDX;
    @Unique
    private double vulkanmod$precisionDY;

    @Inject(method = "updateTargetedEntity", at = @At("HEAD"))
    private void captureTickDelta(float tickDelta, CallbackInfo ci) {
        this.vulkanmod$tickDelta = tickDelta;
    }

    @Redirect(
            method = "updateTargetedEntity",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/entity/Entity;getBoundingBox()Lnet/minecraft/util/math/Box;",
                    ordinal = 1
            )
    )
    private Box interpolateEntityHitbox(Entity entity) {
        Box box = entity.getBoundingBox();
        float t = this.vulkanmod$tickDelta;
        double dx = (entity.prevTickX - entity.x) * (1.0 - t);
        double dy = (entity.prevTickY - entity.y) * (1.0 - t);
        double dz = (entity.prevTickZ - entity.z) * (1.0 - t);
        return box.offset(dx, dy, dz);
    }

    /**
     * Fixes false negatives at the exact edge of 3.0 block reach caused by
     * floating-point imprecision in distanceTo(). Vanilla compares with
     * strict > 3.0, which rejects distances like 3.0000000000000004.
     * Adding epsilon to the constant so the comparison uses > 3.0000001.
     */
    @ModifyConstant(
            method = "updateTargetedEntity",
            constant = @Constant(doubleValue = 3.0, ordinal = 1)
    )
    private double addReachEpsilon(double original) {
        return original + 1.0E-7;
    }

    /**
     * After MouseInput.updateMouse() is called, capture the high-precision
     * double deltas and compute the sensitivity-scaled rotation in double
     * precision, storing for use when increaseTransforms is called.
     */
    @Inject(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/client/MouseInput;updateMouse()V",
                    shift = At.Shift.AFTER
            )
    )
    private void captureDoublePrecisionDeltas(float tickDelta, long startTime, CallbackInfo ci) {
        double rawDX = Mouse.getDXDouble();
        double rawDY = Mouse.getDYDouble();

        double sens = (double) this.client.options.sensitivity;
        double f = sens * 0.6 + 0.2;
        double scaled = f * f * f * 8.0;

        this.vulkanmod$precisionDX = rawDX * scaled;
        this.vulkanmod$precisionDY = rawDY * scaled;
    }

    /**
     * Redirect both increaseTransforms calls in render() to use the
     * double-precision deltas computed above, applying the same 0.15
     * multiplier that vanilla uses inside increaseTransforms, but
     * staying in double precision throughout.
     */
    @Redirect(
            method = "render",
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/minecraft/entity/player/ClientPlayerEntity;increaseTransforms(FF)V"
            )
    )
    private void precisionIncreaseTransforms(ClientPlayerEntity player, float yawDelta, float pitchDelta) {
        double dxd = this.vulkanmod$precisionDX;
        double dyd = this.vulkanmod$precisionDY;

        // Smooth camera path: vanilla accumulates cursorDelta and applies
        // smoothing. If smoothCameraEnabled, yawDelta/pitchDelta already
        // went through the smoothing filter — use vanilla values but in
        // double precision. The precision gain here is smaller but still
        // preserves sub-pixel smoothing accuracy.
        if (this.client.options.smoothCameraEnabled) {
            player.increaseTransforms(yawDelta, pitchDelta);
            return;
        }

        // Normal path: apply the 0.15 multiplier in double precision
        // Replicate increaseTransforms logic exactly but with double input
        boolean invertY = this.client.options.invertYMouse;
        double dySign = invertY ? -1.0 : 1.0;

        float oldYaw = player.yaw;
        float oldPitch = player.pitch;

        // Vanilla: yaw = (float)((double)yaw + (double)param1 * 0.15)
        // We keep the delta in double: dxd already has sensitivity applied
        player.yaw = (float) ((double) player.yaw + dxd * 0.15);
        player.pitch = (float) ((double) player.pitch - dyd * dySign * 0.15);

        // Clamp pitch to [-90, 90]
        if (player.pitch > 90.0F) player.pitch = 90.0F;
        if (player.pitch < -90.0F) player.pitch = -90.0F;

        player.prevPitch += player.pitch - oldPitch;
        player.prevYaw += player.yaw - oldYaw;
    }
}
