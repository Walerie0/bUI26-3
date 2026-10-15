package net.val.bui.client.render;

import com.mojang.blaze3d.vertex.VertexConsumer;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.render.TextureSetup;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.renderer.state.gui.GuiElementRenderState;
import net.val.bui.model.BUIGeometry.Corners;
import net.val.bui.model.BUIGeometry.Transform;
import net.val.bui.model.BUIPaint;
import org.joml.Matrix3x2f;
import org.jspecify.annotations.Nullable;

import static net.val.bui.model.BUIValues.clamp;
import static net.val.bui.model.BUIValues.mix;
import static net.val.bui.model.BUIValues.number;

/**
 * Internal GUI draw state for supported solid/gradient rectangle fills and stroke rings.
 */
public final class BUIShapeRenderState implements GuiElementRenderState {

    /**
     * A local polygon point used when clipping linear-gradient strips.
     */
    private record GradientPoint(float x, float y) {
    }

    private static final float EDGE = 0.25f;
    private static final float CURVE_STEP = 0.2f;
    private static final float FILL_STEP = 0.5f;
    private static final float STROKE_AA = 0.5f;
    private static final int CORNER_SEGMENTS = 12;

    private final float outside, inside;
    private final boolean stroke;

    /**
     * A normalized gradient stop position and its ARGB color.
     */
    private record Stop(float position, int color) {
    }

    private final Matrix3x2f pose;
    private final @Nullable ScreenRectangle scissor;
    private final @Nullable ScreenRectangle bounds;

    private final float width, height;
    private final Transform transform;
    private final float tl, tr, br, bl;
    private final Corners corners;
    private final int paintType;
    private final Transform paintTransform;
    private final List<Stop> stops;

    /**
     * Captures immutable frame geometry, paint sampling and optional stroke alignment for a GUI draw state.
     *
     * @param pose captured Minecraft GUI pose
     * @param scissor captured clipping rectangle, or null
     * @param node decoded FIG node properties
     * @param paint decoded fill or stroke paint
     * @param transform node-to-GUI affine transform
     * @param stops ordered gradient color stops
     * @param paintType normalized Kiwi paint type
     * @param weight stroke width in design units
     * @param alignment normalized stroke alignment
     */
    private BUIShapeRenderState(Matrix3x2f pose, @Nullable ScreenRectangle scissor, Map<?, ?> node, Map<?, ?> paint,
                                Transform transform, List<Stop> stops, int paintType, float weight, int alignment) {
        this.stroke = weight > 0;
        this.outside = alignment == 1 ? 0 : alignment == 2 ? weight : weight / 2;
        this.inside = weight - outside;
        this.transform = transform;
        this.pose = new Matrix3x2f(pose);
        this.scissor = scissor;
        Map<?, ?> size = (Map<?, ?>) node.get("size");
        this.width = number(size.get("x"), 0f);
        this.height = number(size.get("y"), 0f);
        this.paintType = paintType;
        this.stops = List.copyOf(stops);
        this.paintTransform = Transform.from(paint.get("transform"));
        Map<?, ?> cornerNode = node;
        if (stroke && Boolean.TRUE.equals(node.get("rectangleCornerRadiiIndependent"))) {
            var defaults = net.val.bui.model.BUIValues.copy(node);
            float radius = number(node.get("cornerRadius"), 0);
            for (String key : List.of("rectangleTopLeftCornerRadius", "rectangleTopRightCornerRadius",
                    "rectangleBottomRightCornerRadius", "rectangleBottomLeftCornerRadius")) {
                defaults.putIfAbsent(key, radius);
            }
            cornerNode = defaults;
        }
        this.corners = Corners.from(cornerNode, width, height);
        this.tl = corners.tl();
        this.tr = corners.tr();
        this.br = corners.br();
        this.bl = corners.bl();
        float padding = stroke ? outside + STROKE_AA : 0;
        var box = transform
                .bounds(-padding, -padding, width + padding, height + padding);
        int left = (int) Math.floor(box.left());
        int top = (int) Math.floor(box.top());
        int right = (int) Math.ceil(box.right());
        int bottom = (int) Math.ceil(box.bottom());
        ScreenRectangle transformed = new ScreenRectangle(left, top, right - left, bottom - top).transformMaxBounds(pose);
        this.bounds = scissor == null ? transformed : scissor.intersection(transformed);
    }

    /**
     * Submits the visible solid and gradient fills of a decoded node. Normal integrations render through BUIWidget rather than invoking this low-level entry point.
     *
     * @param graphics current frame GUI graphics extractor
     * @param node decoded FIG node properties
     * @param transform node-to-GUI affine transform
     */
    public static void submit(GuiGraphicsExtractor graphics, Map<?, ?> node, Transform transform) {
        submitPaints(graphics, node, transform, false);
    }

    /**
     * Submits visible stroke paints with their alignment, width and gradient colors.
     *
     * @param graphics current frame GUI graphics extractor
     * @param node decoded FIG node properties
     * @param transform node-to-GUI affine transform
     */
    public static void submitStrokes(GuiGraphicsExtractor graphics, Map<?, ?> node, Transform transform) {
        submitPaints(graphics, node, transform, true);
    }

    /**
     * Validates node dimensions and paint layers, then creates GUI states for supported fills or strokes.
     *
     * @param graphics current frame GUI graphics extractor
     * @param node decoded FIG node properties
     * @param transform node-to-GUI affine transform
     * @param stroke whether this operation draws a stroke rather than a fill
     */
    private static void submitPaints(GuiGraphicsExtractor graphics, Map<?, ?> node, Transform transform, boolean stroke) {
        if (Boolean.FALSE.equals(node.get("visible"))) return;
        if (!(node.get("size") instanceof Map<?, ?> size)) return;
        if (number(size.get("x"), 0) <= 0 || number(size.get("y"), 0) <= 0) return;
        if (!(node.get(stroke ? "strokePaints" : "fillPaints") instanceof List<?> paints)) return;

        float weight = stroke ? Math.max(0, number(node.get("strokeWeight"), 1)) : 0;
        if (stroke && weight <= 0) return;
        float opacity = clamp(number(node.get("opacity"), 1), 0, 1);
        int alignment = alignment(node.get("strokeAlign"));

        for (int paintIndex = paints.size() - 1; paintIndex >= 0; paintIndex--) {
            Object entry = paints.get(paintIndex);
            if (!(entry instanceof Map<?, ?> paint) || Boolean.FALSE.equals(paint.get("visible"))) continue;
            int type = BUIPaint.type(paint.get("type"));
            List<Stop> stops;
            if (stroke) {
                if (type < 0 || type > 4) continue;
                stops = parseStops(paint, type, opacity * clamp(number(paint.get("opacity"), 1), 0, 1));
                if (stops.isEmpty()) continue;
            } else {
                if (type < 0 || type > 4) continue;
                float alpha = opacity * clamp(number(paint.get("opacity"), 1), 0, 1);
                stops = parseStops(paint, type, alpha);
                if (stops.isEmpty()) continue;
            }
            graphics.submitGuiElementRenderState(new BUIShapeRenderState(
                    graphics.pose(), graphics.peekScissorStack(), node, paint, transform, stops, type, weight, alignment));
        }
    }

    /**
     * Returns conservative screen-space bounds used by Minecraft GUI overlap ordering and culling.
     *
     */
    @Override
    public @Nullable ScreenRectangle bounds() {
        return bounds;
    }

    /**
     * Returns the active screen-space clipping rectangle, or null when no scissor was captured.
     *
     */
    @Override
    public @Nullable ScreenRectangle scissorArea() {
        return scissor;
    }

    /**
     * Returns the Minecraft GUI pipeline used to draw this state.
     *
     */
    @Override
    public RenderPipeline pipeline() {
        return RenderPipelines.GUI;
    }

    /**
     * Resolves the texture bindings and sampler required by this GUI draw state.
     *
     */
    @Override
    public TextureSetup textureSetup() {
        return TextureSetup.noTexture();
    }

    /**
     * Emits this state vertex data into Minecraft GUI batching; the engine invokes this after frame extraction.
     *
     * @param consumer Minecraft GUI vertex consumer
     */
    @Override
    public void buildVertices(VertexConsumer consumer) {
        if (bounds == null || stops.isEmpty()) {
            return;
        }

        if (stroke) {
            buildStrokeVertices(consumer);
            return;
        }

        float topCurve = Math.max(tl, tr);
        float bottomCurve = height - Math.max(bl, br);
        float y = 0f;

        while (y < height) {
            boolean nearCurve = y < topCurve || y >= bottomCurve;
            float step = nearCurve
                    ? CURVE_STEP
                    : (paintType <= 1 ? 8f : FILL_STEP);
            float nextY = Math.min(height, y + step);
            // Keep strip boundaries aligned with the rounded corner transitions.
            if (y < topCurve && nextY > topCurve) {
                nextY = topCurve;
            }

            if (y < bottomCurve && nextY > bottomCurve) {
                nextY = bottomCurve;
            }

            // Keep vertical antialiasing confined to the edge rather than the entire strip.
            if (y < EDGE) {
                nextY = Math.min(nextY, EDGE);
            }

            if (y < height - EDGE && nextY > height - EDGE) {
                nextY = height - EDGE;
            }

            if (nextY <= y) {
                nextY = Math.min(height, y + 0.25f);
            }

            emitStrip(consumer, y, nextY);
            y = nextY;
        }
    }

    /**
     * Emits one antialiased horizontal strip through the rounded fill outline.
     *
     * @param consumer Minecraft GUI vertex consumer
     * @param y0 local Y coordinate of vertex 0
     * @param y1 local Y coordinate of vertex 1
     */
    private void emitStrip(VertexConsumer consumer, float y0, float y1) {
        float l0 = left(y0);
        float l1 = left(y1);
        float r0 = right(y0);
        float r1 = right(y1);
        float band0 = Math.min(EDGE, Math.max(0f, (r0 - l0) / 2f));
        float band1 = Math.min(EDGE, Math.max(0f, (r1 - l1) / 2f));
        float il0 = l0 + band0;
        float il1 = l1 + band1;
        float ir0 = r0 - band0;
        float ir1 = r1 - band1;

        if (paintType == 0) {
            // Solid fills need one interior quad per strip.
            quad(consumer, il0, y0, il1, y1, ir1, y1, ir0, y0, 1f, 1f, 1f, 1f);
        } else if (paintType == 1) {
            // Split linear gradients at their color stops rather than at every pixel.
            emitLinearStrip(consumer, il0, il1, ir0, ir1, y0, y1);
        } else {
            // RADIAL, ANGULAR, DIAMOND:
            int steps = Math.max(1, (int) Math.ceil(Math.max(ir0 - il0, ir1 - il1) / FILL_STEP));

            for (int i = 0; i < steps; i++) {
                float p0 = i / (float) steps;
                float p1 = (i + 1f) / steps;
                quad(consumer, mix(il0, ir0, p0), y0, mix(il1, ir1, p0), y1, mix(il1, ir1, p1), y1, mix(il0, ir0, p1), y0, 1f, 1f, 1f, 1f);
            }
        }

        quad(consumer, Math.max(0f, l0 - EDGE), y0, Math.max(0f, l1 - EDGE), y1, il1, y1, il0, y0, 0f, 0f, 1f, 1f);
        quad(consumer, ir0, y0, ir1, y1, Math.min(width, r1 + EDGE), y1, Math.min(width, r0 + EDGE), y0, 1f, 1f, 0f, 0f);
    }

    /**
     * Splits a strip at linear-gradient stops before emitting its clipped polygons.
     *
     * @param consumer Minecraft GUI vertex consumer
     * @param leftTop left edge at the top of this strip
     * @param leftBottom left edge at the bottom of this strip
     * @param rightTop right edge at the top of this strip
     * @param rightBottom right edge at the bottom of this strip
     * @param y0 local Y coordinate of vertex 0
     * @param y1 local Y coordinate of vertex 1
     */
    private void emitLinearStrip(VertexConsumer consumer, float leftTop, float leftBottom, float rightTop, float rightBottom, float y0, float y1) {
        List<GradientPoint> polygon = List.of(new GradientPoint(leftTop, y0), new GradientPoint(leftBottom, y1), new GradientPoint(rightBottom, y1), new GradientPoint(rightTop, y0));
        // N color stops define at most N + 1 clipped intervals.
        for (int section = 0; section <= stops.size(); section++) {
            List<GradientPoint> clipped = polygon;

            if (section > 0) {
                clipped = clipLinear(clipped, stops.get(section - 1).position(), true);
            }

            if (section < stops.size()) {
                clipped = clipLinear(clipped, stops.get(section).position(), false);
            }

            if (clipped.size() < 3) {
                continue;
            }

            // Emit clipped triangles as quads with a repeated final vertex.
            GradientPoint first = clipped.getFirst();

            for (int i = 1; i < clipped.size() - 1; i++) {
                GradientPoint second = clipped.get(i);
                GradientPoint third = clipped.get(i + 1);
                quad(consumer, first.x(), first.y(), second.x(), second.y(), third.x(), third.y(), third.x(), third.y(), 1f, 1f, 1f, 1f);
            }
        }
    }

    /**
     * Clips a polygon against one linear-gradient stop boundary.
     *
     * @param polygon local polygon to clip
     * @param threshold gradient parameter at the clipping boundary
     * @param keepAbove whether to keep gradient parameters above the threshold
     */
    private List<GradientPoint> clipLinear(List<GradientPoint> polygon, float threshold, boolean keepAbove) {
        if (polygon.isEmpty()) {
            return List.of();
        }

        List<GradientPoint> result = new ArrayList<>(polygon.size() + 2);
        GradientPoint previous = polygon.getLast();
        float previousDiff = linearT(previous.x(), previous.y()) - threshold;
        boolean previousInside = keepAbove ? previousDiff >= 0f : previousDiff <= 0f;

        for (GradientPoint current : polygon) {
            float currentDiff = linearT(current.x(), current.y()) - threshold;
            boolean currentInside = keepAbove ? currentDiff >= 0f : currentDiff <= 0f;
            // Intersect the edge with the current gradient stop boundary.
            if (previousInside != currentInside) {
                float denominator = previousDiff - currentDiff;

                if (Math.abs(denominator) > 0.0000001f) {
                    float t = Math.max(0f, Math.min(1f, previousDiff / denominator));
                    result.add(new GradientPoint(mix(previous.x(), current.x(), t), mix(previous.y(), current.y(), t)));
                }
            }

            if (currentInside) {
                result.add(current);
            }

            previous = current;
            previousDiff = currentDiff;
            previousInside = currentInside;
        }

        return result;
    }

    /**
     * Evaluates the linear-gradient parameter at a node-local point.
     *
     * @param x horizontal coordinate in the method coordinate space
     * @param y vertical coordinate in the method coordinate space
     */
    private float linearT(float x, float y) {
        return paintTransform.x(x / width, y / height);
    }

    /**
     * Samples the local rounded fill left edge.
     *
     * @param y vertical coordinate in the method coordinate space
     */
    private float left(float y) {
        return corners.left(y, height);
    }

    /**
     * Samples the local rounded fill right edge.
     *
     * @param y vertical coordinate in the method coordinate space
     */
    private float right(float y) {
        return corners.right(y, width, height);
    }

    /**
     * Emits a quad with winding corrected for its local order and mirrored geometry or GUI pose.
     *
     * @param consumer Minecraft GUI vertex consumer
     * @param x0 local X coordinate of vertex 0
     * @param y0 local Y coordinate of vertex 0
     * @param x1 local X coordinate of vertex 1
     * @param y1 local Y coordinate of vertex 1
     * @param x2 local X coordinate of vertex 2
     * @param y2 local Y coordinate of vertex 2
     * @param x3 local X coordinate of vertex 3
     * @param y3 local Y coordinate of vertex 3
     * @param a0 coverage multiplier for vertex 0
     * @param a1 coverage multiplier for vertex 1
     * @param a2 coverage multiplier for vertex 2
     * @param a3 coverage multiplier for vertex 3
     */
    private void quad(VertexConsumer consumer, float x0, float y0, float x1, float y1, float x2, float y2, float x3, float y3, float a0, float a1, float a2, float a3) {
        // Ring segments and fill strips do not have the same local winding.
        float area = x0 * y1 - y0 * x1 + x1 * y2 - y1 * x2
                + x2 * y3 - y2 * x3 + x3 * y0 - y3 * x0;
        if (area * transform.determinant() * pose.determinant() > 0f) {
            vertex(consumer, x0, y0, a0);
            vertex(consumer, x3, y3, a3);
            vertex(consumer, x2, y2, a2);
            vertex(consumer, x1, y1, a1);
        } else {
            vertex(consumer, x0, y0, a0);
            vertex(consumer, x1, y1, a1);
            vertex(consumer, x2, y2, a2);
            vertex(consumer, x3, y3, a3);
        }
    }

    /**
     * Transforms a local vertex and assigns paint color with edge coverage.
     *
     * @param consumer Minecraft GUI vertex consumer
     * @param px local horizontal sample coordinate
     * @param py local vertical sample coordinate
     * @param coverage antialias coverage multiplier
     */
    private void vertex(VertexConsumer consumer, float px, float py, float coverage) {
        int color = sample(px, py);
        float top = clamp(py / EDGE, 0f, 1f);
        float bottom = clamp((height - py) / EDGE, 0f, 1f);
        int alpha = Math.round(((color >>> 24) & 255) * coverage * (stroke ? 1f : Math.min(top, bottom)));
        // Apply the geometry transform to vertices while preserving gradient coordinates.
        consumer.addVertexWith2DPose(pose, transform.x(px, py), transform.y(px, py)).setColor((alpha << 24) | (color & 0xFFFFFF));
    }

    /**
     * Samples the active solid or gradient paint in node coordinates.
     *
     * @param px local horizontal sample coordinate
     * @param py local vertical sample coordinate
     */
    private int sample(float px, float py) {
        if (paintType == 0) {
            return stops.get(0).color();
        }

        float u = px / width;
        float v = py / height;
        // FIG-KIWI: node-normalized -> gradient-unit.
        // The imported gradient matrix maps node coordinates directly; do not invert it.
        float gx = paintTransform.x(u, v);
        float gy = paintTransform.y(u, v);
        float t = switch (paintType) {
            case 1 -> gx;
            case 2 -> 2f * (float) Math.hypot(gx - 0.5f, gy - 0.5f);
            case 3 -> {
                float angle = (float) Math.atan2(gy - 0.5f, gx - 0.5f);
                float turn = angle / (2f * (float) Math.PI);
                yield turn - (float) Math.floor(turn);
            }

            case 4 -> 2f * (Math.abs(gx - 0.5f) + Math.abs(gy - 0.5f));
            default -> 0f;
        };

        if (t <= stops.get(0).position()) {
            return stops.get(0).color();
        }

        for (int i = 1; i < stops.size(); i++) {
            Stop prev = stops.get(i - 1);
            Stop next = stops.get(i);

            if (t <= next.position()) {
                float delta = next.position() - prev.position();
                float factor = delta <= 0f ? 1f : (t - prev.position()) / delta;

                return interpolate(prev.color(), next.color(), factor);
            }
        }

        return stops.get(stops.size() - 1).color();
    }

    /**
     * Interpolates ARGB colors through premultiplied alpha.
     *
     * @param c0 color or coverage at vertex 0
     * @param c1 color or coverage at vertex 1
     * @param t normalized interpolation progress
     */
    private static int interpolate(int c0, int c1, float t) {
        t = clamp(t, 0f, 1f);
        float a0 = ((c0 >>> 24) & 255) / 255f;
        float a1 = ((c1 >>> 24) & 255) / 255f;
        float alpha = mix(a0, a1, t);
        float r = mix(((c0 >>> 16) & 255) * a0, ((c1 >>> 16) & 255) * a1, t);
        float g = mix(((c0 >>> 8) & 255) * a0, ((c1 >>> 8) & 255) * a1, t);
        float b = mix((c0 & 255) * a0, (c1 & 255) * a1, t);

        if (alpha > 0.00001f) {
            r /= alpha;
            g /= alpha;
            b /= alpha;
        }

        return (channel255(alpha * 255f) << 24) | (channel255(r) << 16) | (channel255(g) << 8) | channel255(b);
    }

    /**
     * Reads and sorts solid or gradient color stops with the supplied opacity.
     *
     * @param paint decoded fill or stroke paint
     * @param type numeric or named imported type
     * @param opacity opacity multiplier, normally between zero and one
     */
    private static List<Stop> parseStops(Map<?, ?> paint, int type, float opacity) {
        List<Stop> result = new ArrayList<>();

        if (type == 0) {
            if (paint.get("color") instanceof Map<?, ?> color) {
                result.add(new Stop(0f, rgba(color, opacity)));
            }
        } else if (paint.get("stops") instanceof List<?> list) {
            for (Object element : list) {
                if (element instanceof Map<?, ?> stop && stop.get("color") instanceof Map<?, ?> color) {
                    result.add(new Stop(number(stop.get("position"), 0f), rgba(color, opacity)));
                }
            }
        }

        result.sort(Comparator.comparingDouble(Stop::position));

        return result;
    }

    /**
     * Converts decoded RGBA channels to ARGB with an opacity multiplier.
     *
     * @param color straight-alpha ARGB color or decoded RGBA fields
     * @param opacity opacity multiplier, normally between zero and one
     */
    private static int rgba(Map<?, ?> color, float opacity) {
        int r = channel01(number(color.get("r"), 0f));
        int g = channel01(number(color.get("g"), 0f));
        int b = channel01(number(color.get("b"), 0f));
        int a = channel01(number(color.get("a"), 1f) * opacity);

        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    /**
     * Converts a normalized channel to a clamped eight-bit value.
     *
     * @param value value or state to inspect or assign
     */
    private static int channel01(float value) {
        return Math.round(clamp(value, 0f, 1f) * 255f);
    }

    /**
     * Clamps and rounds an eight-bit channel value.
     *
     * @param value value or state to inspect or assign
     */
    private static int channel255(float value) {
        return Math.round(clamp(value, 0f, 255f));
    }

    /**
     * A local stroke contour point in design coordinates.
     */
    private record Point(float x, float y) {}

    /**
     * An inset or outset rounded stroke contour in local coordinates.
     */
    private record Outline(float x, float y, float width, float height, float tl, float tr, float br, float bl) {}

    /**
     * Emits the stroke ring plus inner and outer antialias bands.
     *
     * @param consumer Minecraft GUI vertex consumer
     */
    private void buildStrokeVertices(VertexConsumer consumer) {
        if (bounds == null) {
            return;
        }

        Outline outerFeather = outline(-outside - STROKE_AA);
        Outline outerEdge = outline(-outside);
        Outline innerEdge = outline(inside);
        Outline innerFeather = outline(inside + STROKE_AA);
        int count = 4 * (CORNER_SEGMENTS + 1);

        for (int i = 0; i < count; i++) {
            int next = (i + 1) % count;
            Point a0 = point(outerFeather, i);
            Point a1 = point(outerFeather, next);
            Point b0 = point(outerEdge, i);
            Point b1 = point(outerEdge, next);
            Point c0 = point(innerEdge, i);
            Point c1 = point(innerEdge, next);
            Point d0 = point(innerFeather, i);
            Point d1 = point(innerFeather, next);
            // Fade from transparent outer coverage to the stroke edge.
            strokeQuad(consumer, a0, a1, b1, b0, 0f, 0f, 1f, 1f);
            // Emit the opaque stroke band.
            strokeQuad(consumer, b0, b1, c1, c0, 1f, 1f, 1f, 1f);
            // Fade the inner edge toward the unpainted interior.
            strokeQuad(consumer, c0, c1, d1, d0, 1f, 1f, 0f, 0f);
        }
    }

    /**
     * Builds a rounded inset or outset contour, collapsing excessive inner strokes at the center.
     *
     * @param inset positive inset or negative outset in design units
     */
    private Outline outline(float inset) {
        float x = Math.min(inset, width / 2);
        float y = Math.min(inset, height / 2);
        float w = Math.max(0, width - x * 2f);
        float h = Math.max(0, height - y * 2f);
        float cap = Math.min(w, h) / 2f;
        float rtl = clamp(tl - inset, 0f, cap);
        float rtr = clamp(tr - inset, 0f, cap);
        float rbr = clamp(br - inset, 0f, cap);
        float rbl = clamp(bl - inset, 0f, cap);

        return new Outline(
                x, y, w, h,
                rtl, rtr, rbr, rbl
        );
    }

    /**
     * Samples an indexed corner point on a rounded stroke contour.
     *
     * @param shape decoded shape or contour to inspect
     * @param index element or contour point index
     */
    private Point point(Outline shape, int index) {
        int group = index / (CORNER_SEGMENTS + 1);
        int part = index % (CORNER_SEGMENTS + 1);
        float t = part / (float) CORNER_SEGMENTS;
        float x, y, radius, start;

        switch (group) {
            case 0 -> {
                radius = shape.tl;
                x = shape.x + radius;
                y = shape.y + radius;
                start = 180f;
            }
            case 1 -> {
                radius = shape.tr;
                x = shape.x + shape.width - radius;
                y = shape.y + radius;
                start = 270f;
            }
            case 2 -> {
                radius = shape.br;
                x = shape.x + shape.width - radius;
                y = shape.y + shape.height - radius;
                start = 0f;
            }
            default -> {
                radius = shape.bl;
                x = shape.x + radius;
                y = shape.y + shape.height - radius;
                start = 90f;
            }
        }

        double angle = Math.toRadians(start + t * 90f);

        return new Point(
                x + (float) Math.cos(angle) * radius,
                y + (float) Math.sin(angle) * radius
        );
    }

    /**
     * Subdivides gradient stroke segments before emitting coverage-aware quads.
     *
     * @param consumer Minecraft GUI vertex consumer
     * @param p0 first contour point
     * @param p1 first cubic control coordinate
     * @param p2 second cubic control coordinate
     * @param p3 fourth contour point
     * @param a0 coverage multiplier for vertex 0
     * @param a1 coverage multiplier for vertex 1
     * @param a2 coverage multiplier for vertex 2
     * @param a3 coverage multiplier for vertex 3
     */
    private void strokeQuad(VertexConsumer consumer, Point p0, Point p1, Point p2, Point p3,
                            float a0, float a1, float a2, float a3) {
        int steps = paintType == 0 ? 1 : Math.min(512, Math.max(1, (int) Math.ceil(
                Math.max(Math.hypot(p1.x - p0.x, p1.y - p0.y), Math.hypot(p2.x - p3.x, p2.y - p3.y)) / 2)));
        for (int i = 0; i < steps; i++) {
            float from = i / (float) steps, to = (i + 1f) / steps;
            quad(consumer, mix(p0.x, p1.x, from), mix(p0.y, p1.y, from),
                    mix(p0.x, p1.x, to), mix(p0.y, p1.y, to),
                    mix(p3.x, p2.x, to), mix(p3.y, p2.y, to),
                    mix(p3.x, p2.x, from), mix(p3.y, p2.y, from),
                    mix(a0, a1, from), mix(a0, a1, to), mix(a3, a2, to), mix(a3, a2, from));
        }
    }

    /**
     * Normalizes an imported stroke alignment to center, inside or outside.
     *
     * @param value value or state to inspect or assign
     */
    private static int alignment(Object value) {
        if (value instanceof Number n) {
            return n.intValue();
        }

        return switch (String.valueOf(value)) {
            case "INSIDE" -> 1;
            case "OUTSIDE" -> 2;
            default -> 0;
        };
    }
}
