package net.val.bui.component;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.val.bui.animation.BUIAnimationController;
import net.val.bui.error.BUIErrors;

/**
 * Mutable layout, animation and callback state for one component placement. Configure it through pos, size, root
 * and child; do not share an instance between active widgets.
 */
public class BUIComponentInstance {

    private final BUIComponent component;

    private int x, y, width, height;

    private final BUIAnimationController animation;

    private final Map<Map<?, ?>, BUINodeAction> actions = new IdentityHashMap<>();

    /**
     * Creates independent layout, animation and interaction state for a component. Normally use
     * {@link BUIComponent#createInstance()} or {@link net.val.bui.client.screen.BUIScreen#add(BUIComponent)}.
     *
     * @param component reusable component definition
     */
    public BUIComponentInstance(BUIComponent component) {
        this.component = BUIErrors.requireNonNull(component, BUIComponentInstance.class, "BUIComponentInstance", "component");

        if (component.getDesign().get("size") instanceof Map<?, ?> size) {
            this.width = number(size.get("x"));
            this.height = number(size.get("y"));
        }

        this.animation = BUIAnimationController.fromDesign(component.getDesign());
    }

    /**
     * Sets the instance position in Minecraft GUI coordinates and returns this instance for chaining. For example,
     * {@code component.createInstance().pos(100, 50)}.
     *
     * @param x horizontal coordinate in the method coordinate space
     * @param y vertical coordinate in the method coordinate space
     * @return this instance for fluent configuration
     */
    public BUIComponentInstance pos(int x, int y) {
        this.x = x;
        this.y = y;
        return this;
    }

    /**
     * Sets the displayed width and height in GUI units and returns this instance. Negative dimensions are rejected;
     * zero dimensions hide its hit area.
     *
     * @param width width in design or GUI units as appropriate
     * @param height height in design or GUI units as appropriate
     * @return this instance for fluent configuration
     * @throws IllegalArgumentException if either dimension is negative
     */
    public BUIComponentInstance size(int width, int height) {
        if (width < 0 || height < 0) {
            throw new IllegalArgumentException(BUIErrors.message(BUIComponentInstance.class, "size", "Negative component size", "Pass a non-negative width and height to size(width, height)."));
        }

        this.width = width;
        this.height = height;
        return this;
    }

    /**
     * Returns the action configuration for the component root. Call
     * {@code root().setPressable(true).onClick(callback)} to make the component interactive.
     *
     * @return the root action configuration for this instance
     */
    public BUINodeAction root() {
        return action(component.getDesign());
    }

    /**
     * Finds a layer by its exact Figma name and returns its action configuration. Names must be unique within the
     * component; narrow the search with {@code child("Panel").child("Button")} when the imported hierarchy allows
     * it. Animated variants currently route input through the root.
     *
     * @param name exact component, layer or schema name
     * @return the action for the uniquely named imported layer
     * @throws IllegalArgumentException if the name is null, missing or ambiguous
     */
    public BUINodeAction child(String name) {
        return findChild(name, null);
    }

    /**
     * Resolves a uniquely named node within the requested ancestor and reports missing or ambiguous layer names.
     *
     * @param name exact component, layer or schema name
     * @param ancestor ancestor restricting the layer search, or null for the component
     * @return the unique matching action within the selected ancestor
     */
    BUINodeAction findChild(String name, Map<?, ?> ancestor) {
        BUIErrors.requireNonNull(name, BUIComponentInstance.class, "child", "name");
        Map<?, ?> found = null;

        for (Map<?, ?> node : nodes()) {
            if (!name.equals(node.get("name"))) continue;

            if (ancestor != null && !isDescendant(node, ancestor)) {
                continue;
            }

            if (found != null) {
                throw new IllegalArgumentException(BUIErrors.message(BUIComponentInstance.class, "findChild", "Multiple FIG children named: " + name, "Check the Figma layer name; names must be unique within the selected parent. Use child(parentName).child(name) for nested targets."));
            }

            found = node;
        }

        if (found == null) {
            throw new IllegalArgumentException(BUIErrors.message(BUIComponentInstance.class, "findChild", "FIG child not found: " + name, "Check the Figma layer name; names must be unique within the selected parent. Use child(parentName).child(name) for nested targets."));
        }

        return action(found);
    }

    /**
     * Returns the existing action configuration for the exact decoded node, or creates it once.
     *
     * @param node decoded FIG node properties
     * @return the action configuration associated with this exact decoded node
     */
    private BUINodeAction action(Map<?, ?> node) {
        return actions.computeIfAbsent(node, key -> new BUINodeAction(this, key));
    }

    /**
     * Finds the frontmost enabled, pressable action at the given screen position. Widgets call this during mouse
     * handling; it returns {@code null} outside configured targets.
     *
     * @param mouseX mouse horizontal GUI coordinate
     * @param mouseY mouse vertical GUI coordinate
     * @return the frontmost eligible action, or null if no action accepts the mouse position
     */
    public BUINodeAction hit(double mouseX, double mouseY) {
        if (animation != null) {
            BUINodeAction rootAction = actions.get(component.getDesign());

            if (rootAction == null || !rootAction.isPressable()) {
                return null;
            }

            List<Map<String, Object>> shapes = animation.renderNodes();
            // Hit-test frontmost layers first, reversing the paint order.
            for (int i = shapes.size() - 1; i >= 0; i--) {
                Map<String, Object> shape = shapes.get(i);

                if (animation.isHitTarget(shape) && contains(shape, mouseX, mouseY)) {
                    return rootAction;
                }
            }

            return null;
        }

        List<Map<?, ?>> shapes = nodes();

        for (int i = shapes.size() - 1; i >= 0; i--) {
            Map<?, ?> shape = shapes.get(i);

            if (!contains(shape, mouseX, mouseY)) {
                continue;
            }

            Map<?, ?> current = shape;

            while (current != null) {
                BUINodeAction candidate = actions.get(current);

                if (candidate != null && candidate.isPressable()) {
                    return candidate;
                }

                current = parentOf(current);
            }

            BUINodeAction rootAction = actions.get(component.getDesign());

            if (rootAction != null && rootAction.isPressable()) {
                return rootAction;
            }
        }

        return null;
    }

    /**
     * Returns a snapshot of the action configurations already created for this instance. This does not create
     * actions for untouched layers.
     *
     * @return an immutable snapshot of configured actions
     */
    public List<BUINodeAction> configuredActions() {
        return List.copyOf(actions.values());
    }

    /**
     * Collects the static imported nodes used for action lookup and hit testing.
     *
     */
    private List<Map<?, ?>> nodes() {
        Object raw = component.getDesign().get("buiChildren");

        if (!(raw instanceof List<?> list)) {
            return List.of(component.getDesign());
        }

        List<Map<?, ?>> result = new ArrayList<>();

        for (Object item : list) {
            if (item instanceof Map<?, ?> node) {
                result.add(node);
            }
        }

        return result;
    }

    /**
     * Resolves a node parent by its FIG GUID among the imported static nodes.
     *
     * @param node decoded FIG node properties
     */
    private Map<?, ?> parentOf(Map<?, ?> node) {
        if (!(node.get("parentIndex") instanceof Map<?, ?> index)) {
            return null;
        }

        Object parentGuid = index.get("guid");

        if (parentGuid == null) return null;

        for (Map<?, ?> candidate : nodes()) {
            if (Objects.equals(candidate.get("guid"), parentGuid)) {
                return candidate;
            }
        }

        return null;
    }

    /**
     * Checks whether a node belongs below the requested ancestor, with a bounded parent walk.
     *
     * @param node decoded FIG node properties
     * @param ancestor ancestor restricting the layer search, or null for the component
     */
    private boolean isDescendant(Map<?, ?> node, Map<?, ?> ancestor) {
        if (ancestor == component.getDesign()) return true;
        Map<?, ?> current = parentOf(node);
        int safety = 0;

        while (current != null && safety++ < 128) {
            if (current == ancestor) return true;
            current = parentOf(current);
        }

        return false;
    }

    /**
     * Rounds a decoded numeric design dimension to a GUI integer, using zero when absent.
     *
     * @param value value or state to inspect or assign
     */
    private static int number(Object value) {
        return value instanceof Number n ? Math.round(n.floatValue()) : 0;
    }

    /**
     * Returns the reusable component definition backing this instance.
     *
     * @return the shared definition backing this placement
     */
    public BUIComponent getComponent() {
        return component;
    }

    /**
     * Returns the left GUI coordinate set by {@link #pos(int, int)}.
     *
     * @return the instance left position in GUI units
     */
    public int getX() {
        return x;
    }

    /**
     * Returns the top GUI coordinate set by {@link #pos(int, int)}.
     *
     * @return the instance top position in GUI units
     */
    public int getY() {
        return y;
    }

    /**
     * Returns the current display width in GUI units.
     *
     * @return the display width in GUI units
     */
    public int getWidth() {
        return width;
    }

    /**
     * Returns the current display height in GUI units.
     *
     * @return the display height in GUI units
     */
    public int getHeight() {
        return height;
    }

    /**
     * Returns the instance animation controller, or {@code null} for a static design. Widgets dispatch root hover
     * and mouse transitions automatically.
     *
     * @return the instance controller, or null for a static component
     */
    public BUIAnimationController getAnimation() {
        return animation;
    }

    /**
     * Maps a mouse position into node-local coordinates and checks its transformed, rounded hit area.
     *
     * @param node decoded FIG node properties
     * @param mouseX mouse horizontal GUI coordinate
     * @param mouseY mouse vertical GUI coordinate
     */
    private boolean contains(Map<?, ?> node, double mouseX, double mouseY) {
        if (Boolean.FALSE.equals(node.get("visible"))) {
            return false;
        }

        if (!(node.get("size") instanceof Map<?, ?> size) || !(node.get("transform") instanceof Map<?, ?> m)) {
            return false;
        }

        double w = n(size.get("x"), 0);
        double h = n(size.get("y"), 0);

        if (w <= 0 || h <= 0) return false;
        Map<String, Object> root = getComponent().getDesign();
        double a = n(m.get("m00"), 1);
        double b = n(m.get("m01"), 0);
        double c = n(m.get("m10"), 0);
        double d = n(m.get("m11"), 1);
        double tx = n(m.get("m02"), 0);
        double ty = n(m.get("m12"), 0);
        double minX, minY, totalW, totalH;

        if (root.get("buiChildren") instanceof List<?>) {
            Map<?, ?> rootSize = (Map<?, ?>) root.get("size");
            minX = n(root.get("buiMinX"), 0);
            minY = n(root.get("buiMinY"), 0);
            totalW = n(rootSize.get("x"), 0);
            totalH = n(rootSize.get("y"), 0);
        } else {
            double[] xs = {tx, tx + a * w, tx + b * h, tx + a * w + b * h};
            double[] ys = {ty, ty + c * w, ty + d * h, ty + c * w + d * h};
            minX = Math.min(Math.min(xs[0], xs[1]), Math.min(xs[2], xs[3]));
            minY = Math.min(Math.min(ys[0], ys[1]), Math.min(ys[2], ys[3]));
            totalW = Math.max(Math.max(xs[0], xs[1]), Math.max(xs[2], xs[3])) - minX;
            totalH = Math.max(Math.max(ys[0], ys[1]), Math.max(ys[2], ys[3])) - minY;
        }

        if (totalW <= 0 || totalH <= 0 || getWidth() <= 0 || getHeight() <= 0) return false;


        double docX = minX + (mouseX - getX()) * totalW / getWidth();
        double docY = minY + (mouseY - getY()) * totalH / getHeight();
        double det = a * d - b * c;

        if (Math.abs(det) < 1e-8) return false;
        double dx = docX - tx;
        double dy = docY - ty;
        double lx = (d * dx - b * dy) / det;
        double ly = (-c * dx + a * dy) / det;

        if (lx < 0 || ly < 0 || lx > w || ly > h) return false;


        double uniform = Math.max(0, n(node.get("cornerRadius"), 0));
        boolean independent = Boolean.TRUE.equals(node.get("rectangleCornerRadiiIndependent"));
        double tl = uniform, tr = uniform;
        double br = uniform, bl = uniform;

        if (independent) {
            tl = n(node.get("rectangleTopLeftCornerRadius"), 0);
            tr = n(node.get("rectangleTopRightCornerRadius"), 0);
            br = n(node.get("rectangleBottomRightCornerRadius"), 0);
            bl = n(node.get("rectangleBottomLeftCornerRadius"), 0);
        }

        double cap = Math.min(w, h) / 2.0;
        tl = clamp(tl, 0, cap);
        tr = clamp(tr, 0, cap);
        br = clamp(br, 0, cap);
        bl = clamp(bl, 0, cap);

        if (lx < tl && ly < tl) return circle(lx, ly, tl, tl, tl);


        if (lx > w - tr && ly < tr) return circle(lx, ly, w - tr, tr, tr);


        if (lx > w - br && ly > h - br) return circle(lx, ly, w - br, h - br, br);


        if (lx < bl && ly > h - bl) return circle(lx, ly, bl, h - bl, bl);


        return true;
    }

    /**
     * Checks whether a point is inside a corner circle used by rounded hit testing.
     *
     * @param x horizontal coordinate in the method coordinate space
     * @param y vertical coordinate in the method coordinate space
     * @param cx corner circle center X coordinate
     * @param cy corner circle center Y coordinate
     * @param radius corner or filter radius
     */
    private static boolean circle(double x, double y, double cx, double cy, double radius) {
        double dx = x - cx;
        double dy = y - cy;
        return dx * dx + dy * dy <= radius * radius;
    }

    /**
     * Reads a double-precision coordinate from a decoded property or uses the supplied fallback.
     *
     * @param value value or state to inspect or assign
     * @param fallback value used when the decoded property is absent or non-numeric
     */
    private static double n(Object value, double fallback) {
        return value instanceof Number number ? number.doubleValue() : fallback;
    }

    /**
     * Constrains a double-precision hit-test value to its inclusive interval.
     *
     * @param x horizontal coordinate in the method coordinate space
     * @param min inclusive lower bound
     * @param max inclusive upper bound
     */
    private static double clamp(double x, double min, double max) {
        return Math.max(min, Math.min(max, x));
    }
}
