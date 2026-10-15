package net.val.bui.client.render;

import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.val.bui.component.BUIComponentInstance;
import net.val.bui.model.BUIGeometry.Bounds;
import net.val.bui.model.BUIGeometry.Transform;
import net.val.bui.model.BUIGeometry;

import static net.val.bui.model.BUIValues.number;

/**
 * Client-only component renderer used by BUIWidget. It submits GUI extraction states rather than issuing immediate rendering commands.
 */
public final class BUIRenderer {

    /**
     * Prevents construction; use the static methods of this utility class.
     */
    private BUIRenderer() {}

    /**
     * Submits one component instance to the current Minecraft GUI frame. BUIWidget calls this automatically; custom GUI integrations may call it from their own extraction method on the client thread.
     *
     * @param graphics current frame GUI graphics extractor
     * @param instance component placement to host or render
     */
    public static void render(GuiGraphicsExtractor graphics, BUIComponentInstance instance) {
        Map<String, Object> design = instance.getComponent().getDesign();
        List<?> nodes;
        Bounds bounds;

        if (instance.getAnimation() != null) {
            nodes = instance.getAnimation().renderNodes();
            bounds = new Bounds(0, 0, BUIGeometry.width(design), BUIGeometry.height(design));
        } else if (design.get("buiChildren") instanceof List<?> children) {
            nodes = children;
            float left = number(design.get("buiMinX"), 0);
            float top = number(design.get("buiMinY"), 0);
            bounds = new Bounds(left, top, left + BUIGeometry.width(design), top + BUIGeometry.height(design));
        } else {
            if (!(design.get("size") instanceof Map<?, ?>) || !(design.get("transform") instanceof Map<?, ?>)) return;
            nodes = List.of(design);
            bounds = Transform.from(design.get("transform")).bounds(BUIGeometry.width(design), BUIGeometry.height(design));
        }

        if (bounds.width() <= 0 || bounds.height() <= 0) return;
        float sx = instance.getWidth() / bounds.width();
        float sy = instance.getHeight() / bounds.height();
        Transform viewport = new Transform(sx, 0, instance.getX() - bounds.left() * sx,
                0, sy, instance.getY() - bounds.top() * sy);

        for (Object entry : nodes) {
            if (!(entry instanceof Map<?, ?> node) || !(node.get("transform") instanceof Map<?, ?>)) continue;
            Transform matrix = viewport.then(Transform.from(node.get("transform")));
            if (BUIEffectRenderState.submit(graphics, design, node, matrix)) continue;
            BUIShapeRenderState.submit(graphics, node, matrix);
            BUIImageRenderState.submit(graphics, design, node, matrix);
            BUIShapeRenderState.submitStrokes(graphics, node, matrix);
        }
    }
}
