package net.val.bui.model;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static net.val.bui.model.BUIValues.clamp;
import static net.val.bui.model.BUIValues.mix;
import static net.val.bui.model.BUIValues.number;

/**
 * Internal solid/gradient sampling and embedded-image reference helpers.
 */
public final class BUIPaint {

    /**
     * A normalized gradient stop position and its ARGB color.
     */
    private record Stop(float position, int color) {}

    private final int type;
    private final BUIGeometry.Transform transform;
    private final List<Stop> stops = new ArrayList<>();

    /**
     * Parses solid or gradient paint colors and stops for repeated normalized sampling.
     *
     * @param paint decoded fill or stroke paint
     */
    public BUIPaint(Map<?, ?> paint) {
        type = type(paint.get("type"));
        transform = BUIGeometry.Transform.from(paint.get("transform"));
        float opacity = Boolean.FALSE.equals(paint.get("visible")) ? 0 : number(paint.get("opacity"), 1);
        if (type == 0 && paint.get("color") instanceof Map<?, ?> color) {
            stops.add(new Stop(0, color(color, opacity)));
        } else if (paint.get("stops") instanceof List<?> entries) {
            for (Object entry : entries) {
                if (entry instanceof Map<?, ?> stop && stop.get("color") instanceof Map<?, ?> color) {
                    stops.add(new Stop(number(stop.get("position"), 0), color(color, opacity)));
                }
            }
            stops.sort(Comparator.comparingDouble(Stop::position));
        }
    }

    /**
     * Samples this paint at normalized node coordinates, including paint opacity.
     *
     * @param u normalized horizontal texture coordinate
     * @param v normalized vertical texture coordinate
     */
    public int sample(float u, float v) {
        if (stops.isEmpty()) return 0;
        if (type == 0) return stops.getFirst().color();
        float x = transform.x(u, v), y = transform.y(u, v);
        float t = switch (type) {
            case 1 -> x;
            case 2 -> 2 * (float) Math.hypot(x - 0.5f, y - 0.5f);
            case 3 -> {
                float turn = (float) (Math.atan2(y - 0.5f, x - 0.5f) / (2 * Math.PI));
                yield turn - (float) Math.floor(turn);
            }
            case 4 -> 2 * (Math.abs(x - 0.5f) + Math.abs(y - 0.5f));
            default -> 0;
        };
        if (t <= stops.getFirst().position()) return stops.getFirst().color();
        for (int i = 1; i < stops.size(); i++) {
            Stop a = stops.get(i - 1), b = stops.get(i);
            if (t <= b.position()) {
                return interpolate(a.color(), b.color(), b.position() == a.position() ? 1 : (t - a.position()) / (b.position() - a.position()));
            }
        }
        return stops.getLast().color();
    }

    /**
     * Normalizes a numeric or named Kiwi paint type, returning minus one for an unknown type.
     *
     * @param value value or state to inspect or assign
     */
    public static int type(Object value) {
        if (value instanceof Number n) return n.intValue();
        return switch (String.valueOf(value)) {
            case "SOLID" -> 0;
            case "GRADIENT_LINEAR" -> 1;
            case "GRADIENT_RADIAL" -> 2;
            case "GRADIENT_ANGULAR" -> 3;
            case "GRADIENT_DIAMOND" -> 4;
            case "IMAGE" -> 5;
            default -> -1;
        };
    }

    /**
     * Converts normalized RGBA fields and an opacity multiplier to straight-alpha ARGB.
     *
     * @param color straight-alpha ARGB color or decoded RGBA fields
     * @param opacity opacity multiplier, normally between zero and one
     */
    public static int color(Map<?, ?> color, float opacity) {
        return (channel(number(color.get("a"), 1) * opacity) << 24)
                | (channel(number(color.get("r"), 0)) << 16)
                | (channel(number(color.get("g"), 0)) << 8) | channel(number(color.get("b"), 0));
    }

    /**
     * Multiplies the alpha channel of a straight-alpha ARGB color.
     *
     * @param color straight-alpha ARGB color or decoded RGBA fields
     * @param opacity opacity multiplier, normally between zero and one
     */
    public static int opacity(int color, float opacity) {
        return (Math.round((color >>> 24) * clamp(opacity, 0, 1)) << 24) | (color & 0xffffff);
    }

    /**
     * Composites a straight-alpha foreground color over a background color.
     *
     * @param front foreground ARGB color
     * @param back background ARGB color
     */
    public static int over(int front, int back) {
        if ((front >>> 24) == 0) return back;
        if ((front >>> 24) == 255) return front;
        float a = (front >>> 24) / 255f, b = (back >>> 24) / 255f;
        float alpha = a + b * (1 - a);
        if (alpha <= 0) return 0;
        int result = channel(alpha) << 24;
        for (int shift : new int[]{16, 8, 0}) {
            float value = (((front >>> shift) & 255) * a + ((back >>> shift) & 255) * b * (1 - a)) / alpha;
            result |= Math.round(value) << shift;
        }
        return result;
    }

    /**
     * Interpolates two colors through premultiplied alpha to avoid transparent-edge color artifacts.
     *
     * @param from source state, value or color
     * @param to target state, value or color
     * @param t normalized interpolation progress
     */
    private static int interpolate(int from, int to, float t) {
        float a = (from >>> 24) / 255f, b = (to >>> 24) / 255f;
        float alpha = mix(a, b, t);
        if (alpha <= 0) return 0;
        int result = channel(alpha) << 24;
        for (int shift : new int[]{16, 8, 0}) {
            result |= Math.round(mix(((from >>> shift) & 255) * a, ((to >>> shift) & 255) * b, t) / alpha) << shift;
        }
        return result;
    }

    /**
     * Converts a normalized color channel to a clamped eight-bit value.
     *
     * @param value value or state to inspect or assign
     */
    private static int channel(float value) {
        return Math.round(clamp(value, 0, 1) * 255);
    }

    /**
     * Resolves embedded image bytes from supported FIG hash and reference aliases, returning null when absent.
     *
     * @param design decoded component design; treat shared nested values as read-only
     * @param paint decoded fill or stroke paint
     */
    public static byte[] imageBytes(Map<?, ?> design, Map<?, ?> paint) {
        if (!(design.get("buiEmbeddedImages") instanceof Map<?, ?> images)) return null;
        Set<String> candidates = new LinkedHashSet<>();
        if (paint.get("image") instanceof Map<?, ?> image) {
            Object hash = image.get("hash");
            if (hash instanceof byte[] bytes) addCandidate(candidates, HexFormat.of().formatHex(bytes));
            else addCandidate(candidates, hash);
            addCandidate(candidates, image.get("imageRef"));
            addCandidate(candidates, image.get("name"));
        }
        for (String key : List.of("imageRef", "imageHash", "rasterContentHash")) addCandidate(candidates, paint.get(key));
        for (String candidate : candidates) {
            if (images.get(candidate) instanceof byte[] bytes) return bytes;
        }
        return null;
    }

    /**
     * Adds normalized embedded-image lookup aliases without duplicates.
     *
     * @param values lookup aliases or channel values
     * @param raw decoded source value
     */
    private static void addCandidate(Set<String> values, Object raw) {
        if (raw == null) return;
        String value = raw.toString().trim();
        if (!value.isEmpty()) {
            values.add("images/" + value);
            values.add(value);
        }
    }
}
