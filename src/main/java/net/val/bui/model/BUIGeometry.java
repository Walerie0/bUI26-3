package net.val.bui.model;

import java.util.Map;

import static net.val.bui.model.BUIValues.clamp;
import static net.val.bui.model.BUIValues.number;

/**
 * Shared design-space geometry helpers used by importing and rendering.
 */
public final class BUIGeometry {

    /**
     * Prevents construction; use the static methods of this utility class.
     */
    private BUIGeometry() {}

    /**
     * Axis-aligned design-space bounds. Accessors return the four edges without transforming them.
     */
    public record Bounds(float left, float top, float right, float bottom) {
        /**
         * Returns the width of these design-space bounds.
         *
         */
        public float width() {
            return right - left;
        }

        /**
         * Returns the height of these design-space bounds.
         *
         */
        public float height() {
            return bottom - top;
        }

        /**
         * Returns the smallest bounds containing both this rectangle and the other rectangle.
         *
         * @param other second bounds or geometry value
         */
        public Bounds union(Bounds other) {
            return new Bounds(Math.min(left, other.left), Math.min(top, other.top),
                    Math.max(right, other.right), Math.max(bottom, other.bottom));
        }
    }

    /**
     * Immutable FIG affine transform: x maps to a*x + b*y + tx and y maps to c*x + d*y + ty.
     */
    public record Transform(float a, float b, float tx, float c, float d, float ty) {
        public static final Transform IDENTITY = new Transform(1, 0, 0, 0, 1, 0);
        /**
         * Reads a FIG affine matrix or returns the identity when no matrix is supplied.
         *
         * @param value value or state to inspect or assign
         */
        public static Transform from(Object value) {
            if (!(value instanceof Map<?, ?> matrix)) return IDENTITY;
            return new Transform(number(matrix.get("m00"), 1), number(matrix.get("m01"), 0),
                    number(matrix.get("m02"), 0), number(matrix.get("m10"), 0),
                    number(matrix.get("m11"), 1), number(matrix.get("m12"), 0));
        }

        /**
         * Transforms the horizontal coordinate of a local point.
         *
         * @param x horizontal coordinate in the method coordinate space
         * @param y vertical coordinate in the method coordinate space
         */
        public float x(float x, float y) {
            return a * x + b * y + tx;
        }

        /**
         * Transforms the vertical coordinate of a local point.
         *
         * @param x horizontal coordinate in the method coordinate space
         * @param y vertical coordinate in the method coordinate space
         */
        public float y(float x, float y) {
            return c * x + d * y + ty;
        }

        /**
         * Returns the signed transform determinant, used to detect mirrored geometry.
         *
         */
        public float determinant() {
            return a * d - b * c;
        }

        /**
         * Composes this parent transform with a child transform: the child is applied first.
         *
         * @param child child transform to apply before this parent transform
         */
        public Transform then(Transform child) {
            return new Transform(a * child.a + b * child.c, a * child.b + b * child.d,
                    x(child.tx, child.ty), c * child.a + d * child.c, c * child.b + d * child.d,
                    y(child.tx, child.ty));
        }

        /**
         * Transforms all four corners of a local rectangle and returns its conservative axis-aligned bounds.
         *
         * @param width width in design or GUI units as appropriate
         * @param height height in design or GUI units as appropriate
         */
        public Bounds bounds(float width, float height) {
            return bounds(0, 0, width, height);
        }

        /**
         * Transforms all four corners of a local rectangle and returns its conservative axis-aligned bounds.
         *
         * @param left left edge
         * @param top top edge
         * @param right right edge
         * @param bottom bottom edge
         */
        public Bounds bounds(float left, float top, float right, float bottom) {
            float x0 = x(left, top), y0 = y(left, top);
            float x1 = x(right, top), y1 = y(right, top);
            float x2 = x(left, bottom), y2 = y(left, bottom);
            float x3 = x(right, bottom), y3 = y(right, bottom);
            return new Bounds(Math.min(Math.min(x0, x1), Math.min(x2, x3)),
                    Math.min(Math.min(y0, y1), Math.min(y2, y3)),
                    Math.max(Math.max(x0, x1), Math.max(x2, x3)),
                    Math.max(Math.max(y0, y1), Math.max(y2, y3)));
        }
    }

    /**
     * Independent top-left, top-right, bottom-right and bottom-left corner radii in design units.
     */
    public record Corners(float tl, float tr, float br, float bl) {
        /**
         * Reads and clamps independent or uniform corner radii to the node dimensions.
         *
         * @param node decoded FIG node properties
         * @param width width in design or GUI units as appropriate
         * @param height height in design or GUI units as appropriate
         */
        public static Corners from(Map<?, ?> node, float width, float height) {
            float radius = Math.max(0, number(node.get("cornerRadius"), 0));
            float cap = Math.min(width, height) / 2;
            if (!Boolean.TRUE.equals(node.get("rectangleCornerRadiiIndependent"))) {
                float uniform = clamp(radius, 0, cap);
                return new Corners(uniform, uniform, uniform, uniform);
            }
            return new Corners(clamp(number(node.get("rectangleTopLeftCornerRadius"), 0), 0, cap),
                    clamp(number(node.get("rectangleTopRightCornerRadius"), 0), 0, cap),
                    clamp(number(node.get("rectangleBottomRightCornerRadius"), 0), 0, cap),
                    clamp(number(node.get("rectangleBottomLeftCornerRadius"), 0), 0, cap));
        }

        /**
         * Returns the rounded rectangle left edge at a local vertical coordinate.
         *
         * @param y vertical coordinate in the method coordinate space
         * @param height height in design or GUI units as appropriate
         */
        public float left(float y, float height) {
            if (tl > 0 && y < tl) return tl - arc(tl, y - tl);
            if (bl > 0 && y > height - bl) return bl - arc(bl, y - (height - bl));
            return 0;
        }

        /**
         * Returns the rounded rectangle right edge at a local vertical coordinate.
         *
         * @param y vertical coordinate in the method coordinate space
         * @param width width in design or GUI units as appropriate
         * @param height height in design or GUI units as appropriate
         */
        public float right(float y, float width, float height) {
            if (tr > 0 && y < tr) return width - tr + arc(tr, y - tr);
            if (br > 0 && y > height - br) return width - br + arc(br, y - (height - br));
            return width;
        }

        /**
         * Computes the horizontal circle extent for a rounded corner scanline.
         *
         * @param radius corner or filter radius
         * @param dy vertical distance from the circle center
         */
        private static float arc(float radius, float dy) {
            return (float) Math.sqrt(Math.max(0, radius * radius - dy * dy));
        }
    }

    /**
     * Reads the design-space width of a decoded node, defaulting to zero.
     *
     * @param node decoded FIG node properties
     */
    public static float width(Map<?, ?> node) {
        return node.get("size") instanceof Map<?, ?> size ? number(size.get("x"), 0) : 0;
    }

    /**
     * Reads the design-space height of a decoded node, defaulting to zero.
     *
     * @param node decoded FIG node properties
     */
    public static float height(Map<?, ?> node) {
        return node.get("size") instanceof Map<?, ?> size ? number(size.get("y"), 0) : 0;
    }
}
