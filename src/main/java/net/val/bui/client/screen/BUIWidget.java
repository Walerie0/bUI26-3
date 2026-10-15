package net.val.bui.client.screen;

import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import net.neoforged.neoforge.client.network.ClientPacketDistributor;
import net.val.bui.client.render.BUIRenderer;
import net.val.bui.component.BUIComponentInstance;
import net.val.bui.component.BUINodeAction;
import net.val.bui.network.BUIPressPayload;
import net.val.bui.error.BUIErrors;

/**
 * Client-only adapter that lets a standard Minecraft Screen host a BUIComponentInstance. Register it with
 * addRenderableWidget from your screen init method.
 */
public class BUIWidget extends AbstractWidget {

    private final BUIComponentInstance instance;

    private BUINodeAction hovered;
    private BUINodeAction pressed;

    /**
     * Wraps a configured instance as a Minecraft widget. In an existing Screen subclass, use
     * {@code addRenderableWidget(new BUIWidget(instance))} from {@code init()}; extending BUIScreen is optional.
     *
     * @param instance component placement to host or render
     */
    public BUIWidget(BUIComponentInstance instance) {
        super(BUIErrors.requireNonNull(instance, BUIWidget.class, "BUIWidget", "instance").getX(), instance.getY(), instance.getWidth(), instance.getHeight(),
                Component.literal(instance.getComponent().getName()));
        this.instance = instance;
    }

    /**
     * Returns the hosted component instance so a consuming screen can update its position, size or actions.
     *
     * @return the component instance hosted by this widget
     */
    public BUIComponentInstance getInstance() {
        return instance;
    }

    /**
     * Synchronizes the Minecraft widget bounds with the instance layout before extracting a frame.
     */
    private void syncBounds() {
        setRectangle(instance.getWidth(), instance.getHeight(), instance.getX(), instance.getY());
    }

    /**
     * Finds the enabled action at a mouse position, returning no target when the widget is inactive or hidden.
     *
     * @param mouseX mouse horizontal GUI coordinate
     * @param mouseY mouse vertical GUI coordinate
     */
    private BUINodeAction targetAt(double mouseX, double mouseY) {
        if (!active || !visible) return null;
        return instance.hit(mouseX, mouseY);
    }

    /**
     * Transfers hover between action targets and notifies each changed target exactly once.
     *
     * @param mouseX mouse horizontal GUI coordinate
     * @param mouseY mouse vertical GUI coordinate
     */
    private void updateHover(double mouseX, double mouseY) {
        BUINodeAction next = targetAt(mouseX, mouseY);
        if (hovered == next) return;
        if (hovered != null) hovered.updateHover(false);
        hovered = next;
        if (hovered != null) hovered.updateHover(true);
    }

    /**
     * Synchronizes layout and hover, then submits this instance to the current GUI frame. Minecraft invokes this
     * during screen rendering.
     *
     * @param graphics current frame GUI graphics extractor
     * @param mouseX mouse horizontal GUI coordinate
     * @param mouseY mouse vertical GUI coordinate
     * @param partialTick Minecraft partial-tick interpolation value
     */
    @Override
    protected void extractWidgetRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        syncBounds();
        updateHover(mouseX, mouseY);
        BUIRenderer.render(graphics, instance);
    }

    /**
     * Updates the hovered action when Minecraft forwards a mouse-move event.
     *
     * @param mouseX mouse horizontal GUI coordinate
     * @param mouseY mouse vertical GUI coordinate
     */
    @Override
    public void mouseMoved(double mouseX, double mouseY) {
        updateHover(mouseX, mouseY);
    }

    /**
     * Reports whether a configured bUI action, rather than merely the widget rectangle, accepts this mouse position.
     *
     * @param mouseX mouse horizontal GUI coordinate
     * @param mouseY mouse vertical GUI coordinate
     * @return true when an eligible action accepts this mouse position
     */
    @Override
    public boolean isMouseOver(double mouseX, double mouseY) {
        return targetAt(mouseX, mouseY) != null;
    }

    /**
     * Captures a left-button press on an enabled action, invokes its client callback, and sends its configured
     * server action when connected.
     *
     * @param event Minecraft or NeoForge event being handled
     * @param doubleClick whether Minecraft classified the press as a double click
     * @return true when this widget captured the press
     */
    @Override
    public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) return false;
        BUINodeAction target = targetAt(event.x(), event.y());
        if (target == null) return false;
        pressed = target;
        pressed.press();
        String serverAction = pressed.getServerAction();
        if (serverAction != null && Minecraft.getInstance().getConnection() != null) {
            ClientPacketDistributor.sendToServer(new BUIPressPayload(serverAction));
        }

        return true;
    }

    /**
     * Releases the captured action and confirms a click only when release occurs on the same target.
     *
     * @param event Minecraft or NeoForge event being handled
     * @return true when this widget released a captured action
     */
    @Override
    public boolean mouseReleased(MouseButtonEvent event) {
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT || pressed == null) return false;
        BUINodeAction previous = pressed;
        pressed = null;
        previous.release();
        if (targetAt(event.x(), event.y()) == previous) previous.click();
        updateHover(event.x(), event.y());
        return true;
    }

    /**
     * Provides Minecraft button narration for the hosted component name.
     *
     * @param output destination property map
     */
    @Override
    protected void updateWidgetNarration(NarrationElementOutput output) {
        defaultButtonNarrationText(output);
    }
}
