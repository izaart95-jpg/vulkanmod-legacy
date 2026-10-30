package net.vulkanmod.config;

/**
 * One setting in the Right Shift menu.
 *
 * <p>The menu is driven entirely off the {@link VulkanModConfig#OPTIONS} registry: the categories,
 * the cards and the search index all read these, so adding a feature means adding one entry here
 * and one mixin that reads the value. Nothing in the screen code needs to change.
 *
 * <p>Values are plain fields on a static final object rather than map lookups because several of
 * these are read from the render path on every entity, item or frame.
 */
public class Option {

    public enum Category {
        PERFORMANCE("Performance", "Frame rate and chunk streaming"),
        ANIMATIONS("Animations", "Reverting animations from 1.7"),
        VISUAL("Visual", "What the world and items look like"),
        FOV("Field of View", "Fixed FOV per movement state"),
        HUD("HUD", "Overlays drawn on top of the game");

        public final String title;
        public final String subtitle;

        Category(String title, String subtitle) {
            this.title = title;
            this.subtitle = subtitle;
        }
    }

    /** A switch, or a number with a bar. Decides how the card draws and what a click does. */
    public enum Kind { TOGGLE, SLIDER }

    public final String id;
    public final String name;
    public final String description;
    public final Category category;
    public final Kind kind;

    public final boolean defaultValue;
    /** Read directly from render code; written only from the menu. */
    public boolean value;

    public final int min;
    public final int max;
    public final int defaultNumber;
    public int number;

    /** When set, the card is greyed out and ignores clicks unless this option is on. */
    public Option dependsOn;

    private Option(Category category, Kind kind, String id, String name, String description,
                   boolean defaultValue, int min, int max, int defaultNumber) {
        this.category = category;
        this.kind = kind;
        this.id = id;
        this.name = name;
        this.description = description;
        this.defaultValue = defaultValue;
        this.value = defaultValue;
        this.min = min;
        this.max = max;
        this.defaultNumber = defaultNumber;
        this.number = defaultNumber;
    }

    static Option toggle(Category category, String id, String name, String description,
                         boolean defaultValue) {
        return new Option(category, Kind.TOGGLE, id, name, description, defaultValue, 0, 0, 0);
    }

    static Option slider(Category category, String id, String name, String description,
                         int min, int max, int defaultNumber) {
        return new Option(category, Kind.SLIDER, id, name, description, false, min, max, defaultNumber);
    }

    Option requires(Option other) {
        this.dependsOn = other;
        return this;
    }

    public boolean enabled() {
        return dependsOn == null || dependsOn.value;
    }

    /** Where the slider's handle sits, 0 at the minimum and 1 at the maximum. */
    public float fraction() {
        return max == min ? 0.0f : (number - min) / (float) (max - min);
    }

    public void setFraction(float fraction) {
        float clamped = Math.max(0.0f, Math.min(1.0f, fraction));
        number = min + Math.round(clamped * (max - min));
    }

    /** Case insensitive, so the search box matches the way a player would type. */
    public boolean matches(String query) {
        if (query.isEmpty()) return true;
        String q = query.toLowerCase();
        return name.toLowerCase().contains(q) || description.toLowerCase().contains(q);
    }
}
