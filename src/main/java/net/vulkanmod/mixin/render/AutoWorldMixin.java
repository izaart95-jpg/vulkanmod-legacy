package net.vulkanmod.mixin.render;

import net.minecraft.client.MinecraftClient;
import net.minecraft.world.level.LevelGeneratorType;
import net.minecraft.world.level.LevelInfo;
import net.vulkanmod.Initializer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Development harness: loads a world automatically so the renderer can be exercised without a
 * human at the keyboard. Enabled only by -Dvulkanmod.autoworld=true, and inert otherwise.
 *
 * <p>Every rendering bug this mod has had showed up in the world, not the menus, so being able to
 * run `gradlew runClient` straight into a world is what makes the diagnostics worth anything.
 */
@Mixin(MinecraftClient.class)
public class AutoWorldMixin {

    @Unique private static final boolean AUTO_WORLD = Boolean.getBoolean("vulkanmod.autoworld");

    /**
     * Window size to force, as WIDTHxHEIGHT, from -Dvulkanmod.window=1920x1080.
     *
     * <p>Measurements are meaningless without it. GPU time scales with the number of pixels, so a
     * run in whatever size the window manager happened to give the window cannot be compared with
     * another run at a different size -- and a small window flatters the numbers badly.
     */
    @Unique private static final String WINDOW_SIZE = System.getProperty("vulkanmod.window", "");
    /** Test-only: render the player in third person so the model (and its hurt flash) is visible. */
    @Unique private static final boolean THIRD_PERSON = Boolean.getBoolean("vulkanmod.thirdperson");
    /** Test-only: force a full-strength hurt flash on the player every frame. */
    @Unique private static final boolean HURT_TEST = Boolean.getBoolean("vulkanmod.hurttest");
    /** Camera pitch for the test pose; negative looks up at the cloud layer. */
    @Unique private static final float CAMERA_PITCH = vulkanmod$floatProperty("vulkanmod.pitch", 5.0f);
    @Unique private static final float CAMERA_YAW = vulkanmod$floatProperty("vulkanmod.yaw", 45.0f);
    /** Optional fixed camera height, for cloud/sky screenshots above the tree line. */
    @Unique private static final float CAMERA_Y = vulkanmod$floatProperty("vulkanmod.y", Float.NaN);
    @Unique private static boolean vulkanmod$resized = false;
    @Unique private static int vulkanmod$ticks = 0;
    @Unique private static boolean vulkanmod$started = false;
    @Unique private static int vulkanmod$posed = 0;

    @Inject(method = "runGameLoop", at = @At("RETURN"))
    private void vulkanmod$autoLoadWorld(CallbackInfo ci) {
        if (!AUTO_WORLD || vulkanmod$started) return;
        if (!Initializer.vulkanReady) return;

        // Give the title screen a moment so resource loading has settled.
        if (++vulkanmod$ticks < 120) return;
        vulkanmod$started = true;

        MinecraftClient client = (MinecraftClient) (Object) this;
        vulkanmod$forceWindowSize();
        Initializer.LOGGER.warn("[AutoWorld] starting a flat test world");
        client.startIntegratedServer("vulkanmod_test", "vulkanmod_test",
                new LevelInfo(12345L, LevelInfo.GameMode.CREATIVE, false, false, LevelGeneratorType.FLAT));
    }

    /**
     * Points the camera at the horizon in daylight once the world is up. Looking at the sky at
     * night measures nothing: distant-terrain filtering and how many chunks the indirect batch
     * actually collects only show up with the horizon in view.
     */
    @Inject(method = "runGameLoop", at = @At("RETURN"))
    private void vulkanmod$poseCamera(CallbackInfo ci) {
        if (!AUTO_WORLD || !vulkanmod$started) return;

        MinecraftClient client = (MinecraftClient) (Object) this;
        if (client.player == null || client.world == null) return;
        if (vulkanmod$posed > 100000) return;

        vulkanmod$posed++;
        vulkanmod$forceWindowSize();

        // Re-applied every frame rather than once: a single nudge can be undone by the first
        // input poll or a physics tick, and two runs whose camera differs by a few degrees are
        // not an A/B of anything.
        client.world.setTimeOfDay(6000L);   // noon
        client.player.pitch = CAMERA_PITCH;
        client.player.yaw = CAMERA_YAW;
        if (THIRD_PERSON) {
            // Third person back. Re-applied every frame so the input poll cannot undo it.
            client.options.perspective = 1;
        }
        if (HURT_TEST) {
            // LivingEntityRenderer.method_10252 reads hurtTime every frame; keeping it at the
            // vanilla damage value keeps the flash on for the whole screenshot window.
            client.player.hurtTime = 10;
            client.player.deathTime = 0;
        }
        // Fly rather than fall: a fixed camera height is the whole point of the pose, and the
        // integrated server rejects large position deltas unless the player is flying.
        client.player.abilities.flying = true;
        client.player.abilities.allowFlying = true;
        client.player.fallDistance = 0.0f;
        if (!Float.isNaN(CAMERA_Y)) {
            double y = CAMERA_Y;
            client.player.updatePosition(client.player.x, y, client.player.z);
            client.player.prevX = client.player.x;
            client.player.prevY = y;
            client.player.prevZ = client.player.z;
            client.player.prevTickX = client.player.x;
            client.player.prevTickY = y;
            client.player.prevTickZ = client.player.z;
            client.player.setVelocityClient(0.0, 0.0, 0.0);
        }
    }

    @Unique
    private static float vulkanmod$floatProperty(String name, float fallback) {
        try {
            String value = System.getProperty(name);
            return value == null ? fallback : Float.parseFloat(value);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @Unique
    private static void vulkanmod$forceWindowSize() {
        if (vulkanmod$resized || WINDOW_SIZE.isEmpty()) return;
        vulkanmod$resized = true;

        int split = WINDOW_SIZE.indexOf('x');
        if (split <= 0) {
            Initializer.LOGGER.warn("[AutoWorld] ignoring vulkanmod.window={}, expected WIDTHxHEIGHT",
                                    WINDOW_SIZE);
            return;
        }
        try {
            int width = Integer.parseInt(WINDOW_SIZE.substring(0, split).trim());
            int height = Integer.parseInt(WINDOW_SIZE.substring(split + 1).trim());
            org.lwjgl.opengl.Display.setWindowSize(width, height);
            Initializer.LOGGER.warn("[AutoWorld] window forced to {}x{}", width, height);
        } catch (Exception e) {
            Initializer.LOGGER.warn("[AutoWorld] could not apply vulkanmod.window={}", WINDOW_SIZE, e);
        }
    }
}
