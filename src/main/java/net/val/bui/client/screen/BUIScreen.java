package net.val.bui.client.screen;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.val.bui.component.BUIComponent;
import net.val.bui.component.BUIComponentInstance;
import net.val.bui.error.BUIErrors;

/**
 * Optional client-only Minecraft Screen base class with component registration helpers. Existing Screen
 * subclasses may use BUIWidget directly instead.
 */
public abstract class BUIScreen extends Screen {

    /**
     * Creates a screen capable of hosting bUI instances. Subclass it and add components inside {@code init()} after
     * calling {@code super.init()}. Keep the subclass in client-only code.
     *
     * @param title screen title used by Minecraft
     */
    protected BUIScreen(Component title) {
        super(BUIErrors.requireNonNull(title, BUIScreen.class, "BUIScreen", "title"));
    }

    /**
     * Registers a bUI widget with this screen and returns the hosted instance for layout and callback configuration.
     * Call this from {@code init()} so widgets are recreated after a resize. A fresh instance is created from the
     * definition.
     *
     * @param component reusable component definition
     * @return the registered instance for layout and callback configuration
     */
    public BUIComponentInstance add(BUIComponent component) {
        return add(BUIErrors.requireNonNull(component, BUIScreen.class, "add", "component").createInstance());
    }

    /**
     * Registers a bUI widget with this screen and returns the hosted instance for layout and callback configuration.
     * Call this from {@code init()} so widgets are recreated after a resize. This overload retains the supplied
     * instance and its existing callbacks.
     *
     * @param instance component placement to host or render
     * @return the registered instance for layout and callback configuration
     */
    public BUIComponentInstance add(BUIComponentInstance instance) {
        BUIErrors.requireNonNull(instance, BUIScreen.class, "add", "instance");
        this.addRenderableWidget(new BUIWidget(instance));

        return instance;
    }
}
