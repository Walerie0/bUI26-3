package net.val.bui.component;

import java.util.Map;
import net.val.bui.error.BUIErrors;

/**
 * Reusable imported design definition. Load it once in your mod component registry, then create an independent
 * instance for each screen. Do not mutate its shared decoded design values.
 */
public class BUIComponent {

    private final String name;
    private final Map<String, Object> design;

    /**
     * Creates a reusable component definition from a decoded design. Prefer
     * {@link net.val.bui.fig.FigLoader#load(String)} for FIG resources. The top-level map is copied; treat all
     * nested design data as read-only.
     *
     * @param name display name identifying this reusable component
     * @param design decoded component design; treat shared nested values as read-only
     */
    public BUIComponent(String name, Map<String,Object> design) {
        this.name = BUIErrors.requireNonNull(name, BUIComponent.class, "BUIComponent", "name");
        BUIErrors.requireNonNull(design, BUIComponent.class, "BUIComponent", "design");
        if (design.entrySet().stream().anyMatch(entry -> entry.getKey() == null || entry.getValue() == null)) {
            throw BUIErrors.argument(BUIComponent.class, "BUIComponent", "The design contains a null key or value",
                    "Load a component through FigLoader or remove null entries from the top-level design map.");
        }
        this.design = Map.copyOf(design);
    }

    /**
     * Creates an independent instance with its own position, dimensions, animation state and callbacks. Keep the
     * component definition in your registry and create a fresh instance for each screen.
     *
     * @return a fresh instance with independent layout, actions and animation
     */
    public BUIComponentInstance createInstance() {
        return new BUIComponentInstance(this);
    }

    /**
     * Returns the imported component name for identification and widget narration.
     *
     * @return the imported component name
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the read-only top-level design map. Nested maps, lists and image bytes are shared by instances and
     * must not be modified.
     *
     * @return the top-level design view; nested values must be treated as read-only
     */
    public Map<String, Object> getDesign() {
        return design;
    }
}
