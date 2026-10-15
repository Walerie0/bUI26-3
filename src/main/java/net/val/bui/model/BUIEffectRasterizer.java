package net.val.bui.model;

import net.val.bui.error.BUIErrors;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

import static net.val.bui.model.BUIValues.clamp;
import static net.val.bui.model.BUIValues.number;

/** Builds reusable layer surfaces. Pixel colors are straight-alpha ARGB. */
public final class BUIEffectRasterizer {

    /**
     * Reusable straight-alpha ARGB pixel surface. Left/top and step map its pixels back into design coordinates.
     */
    public record Surface(int width, int height, float left, float top, float step, int[] pixels) {
        /**
         * Returns this surface width in original design units, including effect padding.
         *
         */
        public float logicalWidth() { return width * step; }
        /**
         * Returns this surface height in original design units, including effect padding.
         *
         */
        public float logicalHeight() { return height * step; }
    }

    /**
     * Internal parsed paint sampler with an optional decoded image.
     */
    private record Paint(BUIPaint color, BufferedImage image, Map<?, ?> properties) {
        /**
         * Samples a local color or image paint with its opacity and supported image scale mode.
         *
         * @param u normalized horizontal texture coordinate
         * @param v normalized vertical texture coordinate
         * @param width width in design or GUI units as appropriate
         * @param height height in design or GUI units as appropriate
         */
        int sample(float u, float v, float width, float height) {
            if (image == null) return color.sample(u, v);
            int mode = (int) number(properties.get("imageScaleMode"), 0);
            float ratio = image.getWidth() / (float) image.getHeight();
            float boxRatio = width / height;
            if (mode == 0 || mode == 1) {
                boolean adjustX = (ratio > boxRatio) == (mode == 0);
                if (adjustX) u = (u - 0.5f) * boxRatio / ratio + 0.5f;
                else v = (v - 0.5f) * ratio / boxRatio + 0.5f;
            } else if (mode == 2 && properties.get("transform") instanceof Map<?, ?> transform) {
                var matrix = BUIGeometry.Transform.from(transform);
                float x = matrix.x(u, v);
                v = matrix.y(u, v);
                u = x;
            } else if (mode == 3) {
                float scale = Math.max(0.001f, number(properties.get("scale"), 1));
                u = u * width / (image.getWidth() * scale);
                v = v * height / (image.getHeight() * scale);
                u -= (float) Math.floor(u);
                v -= (float) Math.floor(v);
            }
            if (u < 0 || u > 1 || v < 0 || v > 1) return 0;
            int x = Math.min(image.getWidth() - 1, (int) (u * image.getWidth()));
            int y = Math.min(image.getHeight() - 1, (int) (v * image.getHeight()));
            return BUIPaint.opacity(image.getRGB(x, y), number(properties.get("opacity"), 1));
        }
    }

    private static final Map<byte[], BufferedImage> IMAGES = new LinkedHashMap<>(16, 0.75f, true);

    /**
     * Prevents construction; use the static methods of this utility class.
     */
    private BUIEffectRasterizer() {}

    /**
     * Reports whether this visible effect has a local raster implementation; Glass and Background Blur are not supported here.
     *
     * @param effect decoded effect and its parameters
     */
    public static boolean supports(BUIStyleStack.EffectLayer effect) {
        return effect.visible() && switch (effect.type()) {
            case "DROP_SHADOW", "INNER_SHADOW", "FOREGROUND_BLUR", "LAYER_BLUR", "NOISE", "GRAIN" -> true;
            default -> false;
        };
    }

    /**
     * Rasterizes a layer and its supported effects into a padded reusable surface. Node opacity and its screen transform are applied later by the GUI state. This is an internal CPU path with cached GPU upload, not a complete GPU effect stack.
     *
     * @param design decoded component design; treat shared nested values as read-only
     * @param node decoded FIG node properties
     */
    public static Surface render(Map<?, ?> design, Map<?, ?> node) {
        float width = BUIGeometry.width(node), height = BUIGeometry.height(node);
        if (width <= 0 || height <= 0 || !Float.isFinite(width + height)) {
            throw new IllegalArgumentException(BUIErrors.message(BUIEffectRasterizer.class, "render", "Invalid effect surface dimensions", "Set finite, positive layer width and height before rendering effects."));
        }
        var styles = BUIStyleStack.fromNode(node);
        float padding = 2;
        for (var effect : styles.effects()) {
            if (!supports(effect)) continue;
            if (effect.type().equals("DROP_SHADOW")) {
                padding += Math.max(0, effect.spread()) + Math.max(Math.abs(effect.offsetX()), Math.abs(effect.offsetY()))
                        + Math.max(0, effect.radius()) * 1.5f;
            } else if (effect.type().equals("FOREGROUND_BLUR") || effect.type().equals("LAYER_BLUR")) {
                padding += Math.max(0, effect.radius()) * 1.5f;
            }
        }
        padding = Math.min(padding, 4096);
        float step = Math.max(0.5f, Math.max(width + 2 * padding, height + 2 * padding) / 1024);
        int w = Math.max(1, (int) Math.ceil((width + 2 * padding) / step));
        int h = Math.max(1, (int) Math.ceil((height + 2 * padding) / step));
        Surface surface = new Surface(w, h, -padding, -padding, step, new int[w * h]);
        var corners = BUIGeometry.Corners.from(node, width, height);
        List<Paint> fills = paints(design, node.get("fillPaints"));
        List<Paint> strokes = paints(design, node.get("strokePaints"));
        float weight = Math.max(0, number(node.get("strokeWeight"), 1));
        float outside = switch (styles.strokeAlign()) {
            case "INSIDE" -> 0;
            case "OUTSIDE" -> weight;
            default -> weight / 2;
        };
        float inside = weight - outside;
        float[] mask = new float[w * h];
        for (int y = 0; y < h; y++) {
            float py = surface.top() + (y + 0.5f) * step;
            for (int x = 0; x < w; x++) {
                float px = surface.left() + (x + 0.5f) * step;
                float distance = distance(px, py, width, height, corners);
                float coverage = clamp(0.5f - distance / step, 0, 1);
                int index = y * w + x;
                mask[index] = coverage;
                int color = sample(fills, px / width, py / height, width, height);
                color = BUIPaint.opacity(color, coverage);
                float ring = clamp(0.5f - (distance - outside) / step, 0, 1)
                        - clamp(0.5f - (distance + inside) / step, 0, 1);
                if (ring > 0 && weight > 0) {
                    color = BUIPaint.over(BUIPaint.opacity(sample(strokes, px / width, py / height, width, height), ring), color);
                }
                surface.pixels()[index] = color;
            }
        }
        for (var effect : styles.effects()) {
            if (!supports(effect)) continue;
            switch (effect.type()) {
                case "DROP_SHADOW", "INNER_SHADOW" -> shadow(surface, mask, width, height, corners, effect);
                case "NOISE", "GRAIN" -> noise(surface, mask, effect);
                case "FOREGROUND_BLUR", "LAYER_BLUR" -> blurPixels(surface, Math.max(0, effect.radius()) / (2 * step));
                default -> { }
            }
        }
        return surface;
    }

    /**
     * Evaluates a rounded-rectangle signed distance using the appropriate corner radius.
     *
     * @param x horizontal coordinate in the method coordinate space
     * @param y vertical coordinate in the method coordinate space
     * @param width width in design or GUI units as appropriate
     * @param height height in design or GUI units as appropriate
     * @param corners normalized independent corner radii
     */
    private static float distance(float x, float y, float width, float height, BUIGeometry.Corners corners) {
        float radius = y < height / 2 ? (x < width / 2 ? corners.tl() : corners.tr())
                : (x < width / 2 ? corners.bl() : corners.br());
        float dx = Math.abs(x - width / 2) - width / 2 + radius;
        float dy = Math.abs(y - height / 2) - height / 2 + radius;
        return (float) Math.hypot(Math.max(dx, 0), Math.max(dy, 0)) + Math.min(Math.max(dx, dy), 0) - radius;
    }

    /**
     * Resolves visible paint samplers and embedded images, retaining a bounded decoded-image cache.
     *
     * @param design decoded component design; treat shared nested values as read-only
     * @param value value or state to inspect or assign
     */
    private static List<Paint> paints(Map<?, ?> design, Object value) {
        List<Paint> result = new ArrayList<>();
        if (!(value instanceof List<?> entries)) return result;
        for (Object entry : entries) {
            if (!(entry instanceof Map<?, ?> paint) || Boolean.FALSE.equals(paint.get("visible"))) continue;
            BufferedImage image = null;
            if (BUIPaint.type(paint.get("type")) == 5) {
                byte[] bytes = BUIPaint.imageBytes(design, paint);
                if (bytes == null) continue;
                if (!IMAGES.containsKey(bytes)) {
                    try { IMAGES.put(bytes, ImageIO.read(new ByteArrayInputStream(bytes))); }
                    catch (IOException e) { IMAGES.put(bytes, null); }
                    if (IMAGES.size() > 32) IMAGES.remove(IMAGES.keySet().iterator().next());
                }
                image = IMAGES.get(bytes);
                if (image == null) continue;
            }
            result.add(new Paint(new BUIPaint(paint), image, paint));
        }
        return result;
    }

    /**
     * Composites paint samples from bottom to top in normalized node coordinates.
     *
     * @param paints ordered paint samplers
     * @param u normalized horizontal texture coordinate
     * @param v normalized vertical texture coordinate
     * @param width width in design or GUI units as appropriate
     * @param height height in design or GUI units as appropriate
     */
    private static int sample(List<Paint> paints, float u, float v, float width, float height) {
        int color = 0;
        for (int i = paints.size() - 1; i >= 0; i--) color = BUIPaint.over(paints.get(i).sample(u, v, width, height), color);
        return color;
    }

    /**
     * Composites a blurred external or internal shadow with its spread, offset and shape mask.
     *
     * @param surface padded pixel surface to modify
     * @param mask local shape coverage for each surface pixel
     * @param width width in design or GUI units as appropriate
     * @param height height in design or GUI units as appropriate
     * @param corners normalized independent corner radii
     * @param effect decoded effect and its parameters
     */
    private static void shadow(Surface surface, float[] mask, float width, float height,
                               BUIGeometry.Corners corners, BUIStyleStack.EffectLayer effect) {
        boolean inner = effect.type().equals("INNER_SHADOW");
        float[] alpha = new float[mask.length];
        float spread = effect.spread();
        for (int y = 0; y < surface.height(); y++) {
            float py = surface.top() + (y + 0.5f) * surface.step() - effect.offsetY();
            for (int x = 0; x < surface.width(); x++) {
                float px = surface.left() + (x + 0.5f) * surface.step() - effect.offsetX();
                float d = distance(px, py, width, height, corners) + (inner ? spread : -spread);
                float coverage = clamp(0.5f - d / surface.step(), 0, 1);
                alpha[y * surface.width() + x] = inner ? 1 - coverage : coverage;
            }
        }
        blur(alpha, surface.width(), surface.height(), Math.max(0, effect.radius()) / (2 * surface.step()), inner ? 1 : 0);
        float opacity = clamp(number(effect.properties().get("opacity"), 1), 0, 1);
        int color = effect.properties().get("color") instanceof Map<?, ?> c ? BUIPaint.color(c, opacity)
                : BUIPaint.opacity(0x40000000, opacity);
        boolean behind = Boolean.TRUE.equals(effect.properties().get("showShadowBehindNode"));
        for (int i = 0; i < alpha.length; i++) {
            float coverage = alpha[i] * (inner ? mask[i] : behind ? 1 : 1 - mask[i]);
            int shadow = BUIPaint.opacity(color, coverage);
            surface.pixels()[i] = inner ? BUIPaint.over(shadow, surface.pixels()[i]) : BUIPaint.over(surface.pixels()[i], shadow);
        }
    }

    /**
     * Applies deterministic Grain or mono, duo or multitone Noise samples inside the layer mask.
     *
     * @param surface padded pixel surface to modify
     * @param mask local shape coverage for each surface pixel
     * @param effect decoded effect and its parameters
     */
    private static void noise(Surface surface, float[] mask, BUIStyleStack.EffectLayer effect) {
        Map<String, Object> p = effect.properties();
        boolean grain = effect.type().equals("GRAIN");
        float opacity = clamp(number(p.get("opacity"), grain ? 0.12f : 1), 0, 1);
        float density = clamp(number(p.get("density"), 1), 0, 1);
        Map<?, ?> size = p.get("noiseSize") instanceof Map<?, ?> v ? v : Map.of();
        float sx = Math.max(0.1f, number(size.get("x"), 1)), sy = Math.max(0.1f, number(size.get("y"), 1));
        int primary = p.get("color") instanceof Map<?, ?> c ? BUIPaint.color(c, opacity) : BUIPaint.opacity(0xff000000, opacity);
        int secondary = p.get("secondaryColor") instanceof Map<?, ?> c ? BUIPaint.color(c, opacity) : BUIPaint.opacity(0xffffffff, opacity);
        int noiseType = (int) number(p.get("noiseType"), 0);
        for (int y = 0; y < surface.height(); y++) {
            for (int x = 0; x < surface.width(); x++) {
                int i = y * surface.width() + x;
                if (mask[i] <= 0) continue;
                int nx = (int) Math.floor((surface.left() + (x + 0.5f) * surface.step()) / sx);
                int ny = (int) Math.floor((surface.top() + (y + 0.5f) * surface.step()) / sy);
                int hash = nx * 0x1f123bb5 ^ ny * 0x5f356495;
                hash ^= hash >>> 16; hash *= 0x7feb352d; hash ^= hash >>> 15;
                float random = (hash & 0x7fffffff) / (float) Integer.MAX_VALUE;
                if (random >= density) continue;
                int color;
                if (grain || noiseType == 2) color = (hash & 1) == 0 ? primary : secondary;
                else if (noiseType == 1) color = primary;
                else color = BUIPaint.opacity(0xff000000 | (hash & 0xffffff), opacity);
                surface.pixels()[i] = BUIPaint.over(BUIPaint.opacity(color, mask[i]), surface.pixels()[i]);
            }
        }
    }

    /**
     * Blurs straight-alpha surface pixels through premultiplied channels to preserve transparent edges.
     *
     * @param surface padded pixel surface to modify
     * @param sigma blur standard deviation in surface pixels
     */
    private static void blurPixels(Surface surface, float sigma) {
        if (sigma <= 0) return;
        int[] pixels = surface.pixels();
        float[][] channels = new float[4][pixels.length];
        for (int i = 0; i < pixels.length; i++) {
            float alpha = (pixels[i] >>> 24) / 255f;
            channels[0][i] = alpha;
            for (int c = 1; c <= 3; c++) channels[c][i] = ((pixels[i] >>> (24 - 8 * c)) & 255) * alpha;
        }
        for (float[] channel : channels) blur(channel, surface.width(), surface.height(), sigma, 0);
        for (int i = 0; i < pixels.length; i++) {
            float a = clamp(channels[0][i], 0, 1);
            int color = Math.round(a * 255) << 24;
            if (a > 0.00001f) {
                for (int c = 1; c <= 3; c++) color |= Math.round(clamp(channels[c][i] / a, 0, 255)) << (24 - 8 * c);
            }
            pixels[i] = color;
        }
    }

    /**
     * Approximates a Gaussian with three separable box passes whose per-pixel cost does not grow with radius.
     *
     * @param pixels pixel or channel buffer to modify
     * @param width width in design or GUI units as appropriate
     * @param height height in design or GUI units as appropriate
     * @param sigma blur standard deviation in surface pixels
     * @param outside value sampled beyond the surface bounds
     */
    private static void blur(float[] pixels, int width, int height, float sigma, float outside) {
        if (sigma <= 0) return;
        // Three box passes approximate a Gaussian with cost independent of its radius.
        float ideal = (float) Math.sqrt(4 * sigma * sigma + 1);
        int lower = Math.max(1, ((int) ideal) | 1);
        if (lower > ideal) lower = Math.max(1, lower - 2);
        int upper = lower + 2;
        int count = Math.round((12 * sigma * sigma - 3 * lower * lower - 12 * lower - 9) / (-4f * lower - 4));
        float[] scratch = new float[pixels.length];
        for (int pass = 0; pass < 3; pass++) {
            int radius = ((pass < count ? lower : upper) - 1) / 2;
            box(pixels, scratch, width, height, radius, outside, true);
            box(scratch, pixels, width, height, radius, outside, false);
        }
    }

    /**
     * Runs one horizontal or vertical sliding box filter with an explicit outside value.
     *
     * @param input source byte stream or pixel channel buffer
     * @param output destination property map
     * @param width width in design or GUI units as appropriate
     * @param height height in design or GUI units as appropriate
     * @param radius corner or filter radius
     * @param outside value sampled beyond the surface bounds
     * @param horizontal whether the filter runs along rows rather than columns
     */
    private static void box(float[] input, float[] output, int width, int height, int radius, float outside, boolean horizontal) {
        int length = horizontal ? width : height, lines = horizontal ? height : width;
        for (int line = 0; line < lines; line++) {
            float sum = 0;
            for (int p = -radius; p <= radius; p++) sum += at(input, width, height, line, p, horizontal, outside);
            for (int p = 0; p < length; p++) {
                output[horizontal ? line * width + p : p * width + line] = sum / (2 * radius + 1);
                sum += at(input, width, height, line, p + radius + 1, horizontal, outside)
                        - at(input, width, height, line, p - radius, horizontal, outside);
            }
        }
    }

    /**
     * Reads a filter sample or returns the requested outside value beyond the surface.
     *
     * @param values lookup aliases or channel values
     * @param width width in design or GUI units as appropriate
     * @param height height in design or GUI units as appropriate
     * @param line row or column index
     * @param p sample coordinate within the current filter line
     * @param horizontal whether the filter runs along rows rather than columns
     * @param outside value sampled beyond the surface bounds
     */
    private static float at(float[] values, int width, int height, int line, int p, boolean horizontal, float outside) {
        if (p < 0 || p >= (horizontal ? width : height)) return outside;
        return values[horizontal ? line * width + p : p * width + line];
    }
}
