package net.val.bui.component;

import java.util.Map;
import java.util.function.Consumer;
import net.val.bui.error.BUIErrors;
import net.val.bui.network.BUIPressPayload;

/**
 * Fluent mouse-action configuration for one component node. Enable targeting with setPressable(true), then
 * attach client callbacks or a registered namespaced server action.
 */
public final class BUINodeAction {

    private final BUIComponentInstance owner;
    private final Map<?, ?> node;

    private boolean pressable;
    private boolean enabled = true;
    private boolean hovered;
    private boolean pressed;

    private Consumer<Boolean> hoverCallback = value -> {};
    private Runnable pressCallback = () -> {};
    private Runnable releaseCallback = () -> {};
    private Runnable clickCallback = () -> {};

    private String serverAction;

    /**
     * Binds callback state to a node owned by one component instance. Obtain actions through
     * {@link BUIComponentInstance#root()} or {@link BUIComponentInstance#child(String)}.
     *
     * @param owner owning class, instance or resource module
     * @param node decoded FIG node properties
     */
    BUINodeAction(BUIComponentInstance owner, Map<?, ?> node) {
        this.owner = owner;
        this.node = node;
    }

    /**
     * Looks up a uniquely named descendant below this action target. For example,
     * {@code instance.child("Panel").child("Confirm")} selects a named nested target when present in the imported
     * static hierarchy.
     *
     * @param name exact component, layer or schema name
     * @return the matching descendant action
     * @throws IllegalArgumentException if the name is null, missing or ambiguous
     */
    public BUINodeAction child(String name) {
        return owner.findChild(name, node);
    }

    /**
     * Enables or disables mouse targeting for this node. Call {@code setPressable(true)} before expecting hover,
     * press or click callbacks.
     *
     * @param value value or state to inspect or assign
     * @return this action for fluent configuration
     */
    public BUINodeAction setPressable(boolean value) {
        this.pressable = value;
        return this;
    }

    /**
     * Temporarily enables or disables a configured action without removing its callbacks.
     *
     * @param value value or state to inspect or assign
     * @return this action for fluent configuration
     */
    public BUINodeAction setEnabled(boolean value) {
        this.enabled = value;
        return this;
    }

    /**
     * Sets the client callback invoked when this action enters or leaves hover. The callback receives {@code true}
     * on entry and {@code false} on exit.
     *
     * @param callback callback invoked for this action; must not be null
     * @return this action for fluent configuration
     */
    public BUINodeAction onHover(Consumer<Boolean> callback) {
        this.hoverCallback = BUIErrors.requireNonNull(callback, BUINodeAction.class, "onHover", "callback");
        return this;
    }

    /**
     * Sets a client callback invoked when the left mouse button is pressed on this action. Use
     * {@link #onClick(Runnable)} for a click confirmed on release.
     *
     * @param callback callback invoked for this action; must not be null
     * @return this action for fluent configuration
     */
    public BUINodeAction onPressClient(Runnable callback) {
        this.pressCallback = BUIErrors.requireNonNull(callback, BUINodeAction.class, "onPressClient", "callback");
        return this;
    }

    /**
     * Assigns the namespaced server action sent on mouse press, for example {@code yourmod:open_panel}. Register
     * the same ID through
     * {@link net.val.bui.network.BUIServerActions#registerAction(String, java.util.function.Consumer)} on the
     * server. The server callback must validate player permissions and game state.
     *
     * @param actionId namespaced action ID such as yourmod:open_panel
     * @return this action for fluent configuration
     */
    public BUINodeAction onPressServer(String actionId) {
        this.serverAction = BUIPressPayload.validateActionId(actionId);
        return this;
    }

    /**
     * Sets the client callback invoked when a captured mouse press is released, including releases outside the
     * original target.
     *
     * @param callback callback invoked for this action; must not be null
     * @return this action for fluent configuration
     */
    public BUINodeAction onRelease(Runnable callback) {
        this.releaseCallback = BUIErrors.requireNonNull(callback, BUINodeAction.class, "onRelease", "callback");
        return this;
    }

    /**
     * Sets the client callback invoked after a left-button press and release on the same action. Enable targeting
     * with {@link #setPressable(boolean)}.
     *
     * @param callback callback invoked for this action; must not be null
     * @return this action for fluent configuration
     */
    public BUINodeAction onClick(Runnable callback) {
        this.clickCallback = BUIErrors.requireNonNull(callback, BUINodeAction.class, "onClick", "callback");
        return this;
    }

    /**
     * Reports whether this action is both configured as pressable and currently enabled.
     *
     * @return true when targeting is enabled and the action is not disabled
     */
    public boolean isPressable() {
        return pressable && enabled;
    }

    /**
     * Reports whether the widget currently considers this action hovered.
     *
     * @return true while this action is hovered
     */
    public boolean isHovered() {
        return hovered;
    }

    /**
     * Reports whether this action currently owns a captured mouse press.
     *
     * @return true while this action owns a captured mouse press
     */
    public boolean isPressed() {
        return pressed;
    }

    /**
     * Returns the configured server action ID, or {@code null} when no server action is assigned.
     *
     * @return the assigned action ID, or null when no server action is configured
     */
    public String getServerAction() {
        return serverAction;
    }

    /**
     * Updates hover state, dispatches a root variant transition when present, and invokes the hover callback only
     * when the value changes. Widgets call this automatically.
     *
     * @param value value or state to inspect or assign
     */
    public void updateHover(boolean value) {
        if (hovered == value) return;
        hovered = value;

        if (animatesRoot()) {
            owner.getAnimation().onHover(value);
        }

        hoverCallback.accept(value);
    }

    /**
     * Marks the action pressed, dispatches a root press transition, and invokes the client press callback. Network
     * transmission is handled by the widget.
     */
    public void press() {
        pressed = true;

        if (animatesRoot()) {
            owner.getAnimation().onPress();
        }

        pressCallback.run();
    }

    /**
     * Clears the captured press state, dispatches a root release transition, and invokes the release callback.
     */
    public void release() {
        pressed = false;

        if (animatesRoot()) {
            owner.getAnimation().onRelease();
        }

        releaseCallback.run();
    }

    /**
     * Dispatches the root click transition and invokes the click callback. The widget calls this only after a
     * matching press and release.
     */
    public void click() {
        if (animatesRoot()) {
            owner.getAnimation().onClick();
        }

        clickCallback.run();
    }

    /**
     * Reports whether this action targets the animated component root rather than an individual decoded node.
     *
     */
    private boolean animatesRoot() {
        return node == owner.getComponent().getDesign() && owner.getAnimation() != null;
    }
}
