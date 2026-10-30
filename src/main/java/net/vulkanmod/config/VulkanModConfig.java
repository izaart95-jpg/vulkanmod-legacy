package net.vulkanmod.config;

import net.vulkanmod.Initializer;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * Settings for the Right Shift menu, persisted next to the game's other configuration.
 *
 * <p>Every switch lives in {@link #OPTIONS}; the named constants are there so render code can read
 * a field rather than look an id up in a map. Saving walks the same list, so a new option is
 * persisted without touching the IO code.
 */
public final class VulkanModConfig {

    private VulkanModConfig() {}

    /**
     * How often queued chunk sections are handed to the mesher, in client ticks.
     *
     * <p>The number is the spacing between batches, not a per-section rate: when a batch is due the
     * whole pending group goes through in one go, bounded only by the builder's own queue. Meshing
     * competes with the frame it is streaming into, so gathering it into occasional batches leaves
     * the frames in between clean.
     */
    public enum LazyChunkLoading {
        OFF("OFF", 0),
        LOWEST("Lowest", 30),
        LOW("Low", 15),
        MEDIUM("Medium", 10),
        HIGH("High", 5),
        HIGHEST("Highest", 2);

        public final String label;
        /** Ticks between batches; 0 keeps vanilla's every-frame trickle. */
        public final int ticks;

        LazyChunkLoading(String label, int ticks) {
            this.label = label;
            this.ticks = ticks;
        }

        public String display() {
            return ticks == 0 ? "OFF (Vanilla chunk loading - NO FPS BOOST)"
                              : label + " (Every " + ticks + " ticks)";
        }

        public LazyChunkLoading next() {
            LazyChunkLoading[] all = values();
            return all[(ordinal() + 1) % all.length];
        }

        public LazyChunkLoading previous() {
            LazyChunkLoading[] all = values();
            return all[(ordinal() + all.length - 1) % all.length];
        }
    }

    public static LazyChunkLoading lazyChunkLoading = LazyChunkLoading.OFF;

    public static final List<Option> OPTIONS = new ArrayList<>();

    public static final List<HudElement> HUD_ELEMENTS = new ArrayList<>();

    private static Option add(Option.Category category, String id, String name,
                              String description, boolean defaultValue) {
        Option option = Option.toggle(category, id, name, description, defaultValue);
        OPTIONS.add(option);
        return option;
    }

    private static Option slider(Option.Category category, String id, String name,
                                 String description, int min, int max, int defaultNumber) {
        Option option = Option.slider(category, id, name, description, min, max, defaultNumber);
        OPTIONS.add(option);
        return option;
    }

    private static HudElement hud(String id, String name, Option toggle, float x, float y) {
        HudElement element = new HudElement(id, name, toggle, x, y, 1.0f);
        HUD_ELEMENTS.add(element);
        return element;
    }

    // === Performance ===

    public static final Option SHADOWS = add(Option.Category.PERFORMANCE,
            "shadows", "Shadows",
            "Entity shadows and the ambient occlusion baked into terrain", true);

    public static final Option REBUILD_PRIORITY = add(Option.Category.PERFORMANCE,
            "rebuildPriority", "Visible Chunks First",
            "Mesh the sections you are looking at before the ones behind you", true);

    public static final Option ITEM_GLINT = add(Option.Category.PERFORMANCE,
            "itemGlint", "Enchant Glow",
            "The moving sheen on enchanted items, drawn as a second pass", true);

    public static final Option ENTITY_SHADOW_FADE = add(Option.Category.PERFORMANCE,
            "entityShadowFade", "Shadow Distance Fade",
            "Fade entity shadows out with distance instead of drawing them all", true);

    // === Animations, restoring 1.7 poses ===

    public static final Option ARC_POSITION = add(Option.Category.ANIMATIONS,
            "arcPosition", "Arc Position",
            "Restores bow-holding position from game 1.7", false);

    public static final Option GUARD_LOOK = add(Option.Category.ANIMATIONS,
            "guardLook", "Guard Look",
            "Restores the appearance of the guard from game 1.7", false);

    public static final Option SWORD_MOVES = add(Option.Category.ANIMATIONS,
            "swordMoves", "Sword Moves",
            "Restores the sword attack look from game 1.7", false);

    public static final Option FOOD = add(Option.Category.ANIMATIONS,
            "food", "Food",
            "Restores eating style from game 1.7", false);

    public static final Option ROD_POSITION = add(Option.Category.ANIMATIONS,
            "rodPosition", "Rod Position",
            "Restores rod hold position from version 1.7", false);

    public static final Option ATTACKING = add(Option.Category.ANIMATIONS,
            "attacking", "Attacking",
            "Restores ability to hit blocks while eating, guarding, or drawing a bow", false);

    // === Visual ===

    public static final Option BLOCK_SELECTION = add(Option.Category.VISUAL,
            "blockSelection", "Block Selection",
            "Showing selection around pointed block", true);

    public static final Option BOSS_BAR = add(Option.Category.VISUAL,
            "bossBar", "Boss Bar",
            "Showing the boss health bar at the top of the screen", true);

    public static final Option HEALTH_BAR = add(Option.Category.VISUAL,
            "healthBar", "Health Bar",
            "Restores the appearance of lost hearts on the health bar in game 1.7", false);

    public static final Option FULLBRIGHT = add(Option.Category.VISUAL,
            "fullbright", "Fullbright",
            "Lights the world evenly instead of using the brightness slider", false);

    public static final Option VIGNETTE = add(Option.Category.VISUAL,
            "vignette", "Vignette",
            "The darkening around the edges of the screen", true);

    public static final Option WINDOWED_FULLSCREEN = add(Option.Category.VISUAL,
            "windowedFullscreen", "Windowed Fullscreen",
            "Borderless fullscreen at the desktop resolution. F11 still toggles exclusive fullscreen", false);

    public static final Option USE_WAYLAND = add(Option.Category.VISUAL,
            "useWayland", "Native Wayland",
            "On Linux, prefer the native Wayland backend over XWayland (takes effect after a restart)", true);

    public static final Option CLOUDS = add(Option.Category.VISUAL,
            "clouds", "Clouds",
            "The cloud layer. Measured at 21% of the client thread in a light scene", true);

    public static final Option FIRE_OVERLAY = add(Option.Category.VISUAL,
            "fireOverlay", "Fire Overlay",
            "Lowers the flames drawn over the screen while burning", true);

    // === HUD ===

    public static final Option FPS_DISPLAY = add(Option.Category.HUD,
            "fpsDisplay", "FPS Display",
            "Shows the current frame rate in the corner", false);

    public static final Option COORDINATES = add(Option.Category.HUD,
            "coordinates", "Coordinates",
            "Shows your position without opening the debug screen", false);

    public static final Option CLICK_COUNTER = add(Option.Category.HUD,
            "clickCounter", "Click Counter",
            "Counts your clicks per second for both mouse buttons", false);

    public static final Option KEYSTROKES = add(Option.Category.HUD,
            "keystrokes", "Keystrokes",
            "Shows your movement keys and mouse buttons lighting up as you press them", false);

    // === Field of view ===
    //
    // Vanilla scales the FOV continuously with how fast you are moving, which is exactly the
    // wobble competitive players turn off. With this on, each movement state gets one fixed value
    // and the transition between them is instant.

    public static final Option FOV_MOD = add(Option.Category.FOV,
            "fovMod", "FOV Mod",
            "Replaces vanilla's speed based FOV wobble with a fixed value per state", false);

    public static final Option FOV_DEFAULT = slider(Option.Category.FOV,
            "fovDefault", "Default FOV",
            "Standing still, walking, or anything not covered below", 30, 110, 70)
            .requires(FOV_MOD);

    public static final Option FOV_SLOWNESS = slider(Option.Category.FOV,
            "fovSlowness", "Slowness FOV",
            "While the Slowness effect is on you", 30, 110, 70)
            .requires(FOV_MOD);

    public static final Option FOV_SPRINTING = slider(Option.Category.FOV,
            "fovSprinting", "Sprinting FOV",
            "While sprinting", 30, 110, 80)
            .requires(FOV_MOD);

    public static final Option FOV_SPEED = slider(Option.Category.FOV,
            "fovSpeed", "Speed FOV",
            "While the Speed effect is on you", 30, 110, 80)
            .requires(FOV_MOD);

    public static final Option FOV_FLYING = slider(Option.Category.FOV,
            "fovFlying", "Flying FOV",
            "While flying in creative or spectator", 30, 110, 75)
            .requires(FOV_MOD);

    // The movable overlays, in the order the edit screen cycles through them.
    public static final HudElement HUD_FPS = hud("fps", "FPS Display", FPS_DISPLAY, 0.0f, 0.0f);
    public static final HudElement HUD_COORDS = hud("coords", "Coordinates", COORDINATES, 0.0f, 0.02f);
    public static final HudElement HUD_CPS = hud("cps", "Click Counter", CLICK_COUNTER, 0.0f, 0.04f);
    public static final HudElement HUD_KEYSTROKES = hud("keystrokes", "Keystrokes", KEYSTROKES, 0.02f, 0.5f);

    /** Client ticks since launch, so the chunk batcher can space itself out in game time. */
    public static int tickCounter = 0;

    private static File file;

    private static File file() {
        if (file == null) {
            File dir = new File("config");
            if (!dir.exists()) dir.mkdirs();
            file = new File(dir, "vulkanmod.properties");
        }
        return file;
    }

    public static void load() {
        File f = file();
        if (!f.exists()) return;

        Properties props = new Properties();
        try (FileInputStream in = new FileInputStream(f)) {
            props.load(in);
        } catch (Exception e) {
            Initializer.LOGGER.warn("[Config] could not read {}, using defaults", f, e);
            return;
        }

        String lazy = props.getProperty("lazyChunkLoading");
        if (lazy != null) {
            try {
                lazyChunkLoading = LazyChunkLoading.valueOf(lazy);
            } catch (IllegalArgumentException ignored) {
                // A value from a newer or hand edited file; the default stands.
            }
        }

        for (Option option : OPTIONS) {
            String raw = props.getProperty(option.id);
            if (option.kind == Option.Kind.SLIDER) {
                option.number = option.defaultNumber;
                if (raw != null) {
                    try {
                        option.number = Math.max(option.min, Math.min(option.max, Integer.parseInt(raw)));
                    } catch (NumberFormatException ignored) {
                        // A hand edited file; the default stands.
                    }
                }
            } else {
                option.value = raw == null ? option.defaultValue : Boolean.parseBoolean(raw);
            }
        }

        for (HudElement element : HUD_ELEMENTS) {
            element.x = readFloat(props, "hud." + element.id + ".x", element.x);
            element.y = readFloat(props, "hud." + element.id + ".y", element.y);
            element.scale = Math.max(0.5f, Math.min(3.0f,
                    readFloat(props, "hud." + element.id + ".scale", element.scale)));
        }
    }

    private static float readFloat(Properties props, String key, float fallback) {
        String raw = props.getProperty(key);
        if (raw == null) return fallback;
        try {
            return Float.parseFloat(raw);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    public static void save() {
        Properties props = new Properties();
        props.setProperty("lazyChunkLoading", lazyChunkLoading.name());
        for (Option option : OPTIONS) {
            props.setProperty(option.id, option.kind == Option.Kind.SLIDER
                    ? Integer.toString(option.number)
                    : Boolean.toString(option.value));
        }
        for (HudElement element : HUD_ELEMENTS) {
            props.setProperty("hud." + element.id + ".x", Float.toString(element.x));
            props.setProperty("hud." + element.id + ".y", Float.toString(element.y));
            props.setProperty("hud." + element.id + ".scale", Float.toString(element.scale));
        }

        try (FileOutputStream out = new FileOutputStream(file())) {
            props.store(out, "VulkanMod settings");
        } catch (Exception e) {
            Initializer.LOGGER.warn("[Config] could not write {}", file(), e);
        }
    }
}
