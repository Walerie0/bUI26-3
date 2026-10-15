package net.val.bui.animation;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import net.val.bui.error.BUIErrors;

import static net.val.bui.model.BUIValues.clamp;
import static net.val.bui.model.BUIValues.copy;
import static net.val.bui.model.BUIValues.mix;
import static net.val.bui.model.BUIValues.number;

/**
 * Per-instance controller for imported variant interactions and supported numeric paint/geometry interpolation.
 */
public final class BUIAnimationController {

    /**
     * Internal animation variant identity, shapes, interactions and target names.
     */
    private record Variant(String name, String id, List<Map<String, Object>> shapes, List<?> interactions,
                           Set<String> interactiveChildNames) {
    }

    private final List<Variant> variants = new ArrayList<>();

    private final Map<String, Integer> byId = new HashMap<>();

    private int state;

    private List<Map<String, Object>> fromShapes;

    private long animationStart;
    private long animationDuration;
    private long stateReadyAt;

    private long stateRevision;
    private long consumedTimeoutRevision = -1;

    private String easing = "LINEAR";

    private float[] cubic = {0f, 0f, 1f, 1f};

    private boolean hovering;

    private int hoverSource = -1;
    private int hoverDestination = -1;

    /**
     * Builds independent variant state and trigger lookups from the imported animation states.
     *
     * @param rawStates imported variant-state list
     */
    private BUIAnimationController(List<?> rawStates) {
        for (Object raw : rawStates) {
            if (!(raw instanceof Map<?, ?> entry)) {
                continue;
            }

            List<Map<String, Object>> shapes = new ArrayList<>();

            if (entry.get("shapes") instanceof List<?> items) {
                for (Object item : items) {
                    if (item instanceof Map<?, ?> shape) {
                        shapes.add(copy(shape));
                    }
                }
            } else if (entry.get("shape") instanceof Map<?, ?> shape) {
                shapes.add(copy(shape));
            }

            if (shapes.isEmpty()) {
                continue;
            }

            String id = String.valueOf(entry.get("id"));
            byId.put(id, variants.size());
            Set<String> clickableChildren = new LinkedHashSet<>();

            if (entry.get("buiInteractiveChildNames") instanceof List<?> names) {
                for (Object name : names) {
                    clickableChildren.add(String.valueOf(name));
                }
            }

            List<?> interactions = entry.get("interactions") instanceof List<?> list ? list : List.of();
            variants.add(new Variant(String.valueOf(entry.get("name")), id, List.copyOf(shapes), interactions, Set.copyOf(clickableChildren)));
        }

        if (variants.isEmpty()) {
            throw new IllegalStateException(BUIErrors.message(BUIAnimationController.class, "BUIAnimationController", "Animated component has no states", "Supply at least one valid animation state, or load the component through FigLoader."));
        }

        fromShapes = variants.getFirst().shapes();
        animationStart = System.nanoTime();
        stateReadyAt = animationStart;
    }

    /**
     * Creates a controller for imported variant data, or returns {@code null} for a static design. Component instances call this automatically.
     *
     * @param design decoded component design; treat shared nested values as read-only
     */
    public static BUIAnimationController fromDesign(Map<String, Object> design) {
        BUIErrors.requireNonNull(design, BUIAnimationController.class, "fromDesign", "design");
        if (!(design.get("buiAnimationStates") instanceof List<?> states)) {
            return null;
        }

        return new BUIAnimationController(states);
    }

    /**
     * Returns the name of the currently selected target variant, including while a transition is still in progress.
     *
     */
    public String getStateName() {
        return variants.get(state).name();
    }

    /**
     * Checks whether a rendered shape is one of the interaction targets declared by the active variant.
     *
     * @param shape decoded shape or contour to inspect
     */
    public boolean isHitTarget(Map<?, ?> shape) {
        Set<String> targets = variants.get(state).interactiveChildNames();

        return targets.isEmpty() || targets.contains(String.valueOf(shape.get("name")));
    }

    /**
     * Dispatches hover entry or exit interactions and restores an implicit hover source when required. Widgets invoke this for the root action.
     *
     * @param value value or state to inspect or assign
     */
    public void onHover(boolean value) {
        if (hovering == value) {
            return;
        }

        hovering = value;
        long now = System.nanoTime();

        if (value) {
            if (!fire("MOUSE_IN", now)) {
                fire("ON_HOVER", now);
            }
        } else {
            if (!fire("MOUSE_OUT", now) && hoverSource >= 0 && state == hoverDestination) {
                moveTo(hoverSource, now, 0.3f, "LINEAR", null);
                hoverSource = -1;
                hoverDestination = -1;
            }
        }
    }

    /**
     * Dispatches the active variant mouse-down or press interaction.
     */
    public void onPress() {
        long now = System.nanoTime();

        if (!fire("MOUSE_DOWN", now)) {
            fire("ON_PRESS", now);
        }
    }

    /**
     * Dispatches the active variant mouse-up interaction.
     */
    public void onRelease() {
        fire("MOUSE_UP", System.nanoTime());
    }

    /**
     * Dispatches the active variant click interaction.
     */
    public void onClick() {
        fire("ON_CLICK", System.nanoTime());
    }

    /**
     * Advances due timeout transitions and returns the current interpolated shapes. The renderer calls this each frame.
     *
     */
    public List<Map<String, Object>> renderNodes() {
        long now = System.nanoTime();
        advanceTimeout(now);

        if (!hovering && hoverSource >= 0 && state == hoverDestination) {
            moveTo(hoverSource, now, 0.3f, "LINEAR", null);
            hoverSource = -1;
            hoverDestination = -1;
        }

        return snapshot(now);
    }

    /**
     * Returns the first currently rendered shape. Use {@link #renderNodes()} for components containing multiple shapes.
     *
     */
    public Map<String, Object> renderNode() {
        return renderNodes().getFirst();
    }

    /**
     * Fires an eligible timeout once for the current state revision after its ready time.
     *
     * @param now monotonic timestamp in nanoseconds
     */
    private void advanceTimeout(long now) {
        if (consumedTimeoutRevision == stateRevision || now < stateReadyAt) {
            return;
        }

        Variant current = variants.get(state);

        for (Object raw : current.interactions()) {
            if (!(raw instanceof Map<?, ?> interaction)) {
                continue;
            }

            if (!(interaction.get("event") instanceof Map<?, ?> event)) {
                continue;
            }

            if (!"AFTER_TIMEOUT".equals(eventName(event.get("interactionType")))) {
                continue;
            }

            long delay = (long) (Math.max(0f, number(event.get("transitionTimeout"), 0f)) * 1_000_000_000L);

            if (now >= stateReadyAt + delay) {
                consumedTimeoutRevision = stateRevision;
                fire("AFTER_TIMEOUT", now);

                return;
            }
        }
    }

    /**
     * Finds the first matching trigger that can navigate to a known variant and starts its transition.
     *
     * @param trigger interaction trigger name
     * @param now monotonic timestamp in nanoseconds
     */
    private boolean fire(String trigger, long now) {
        Variant current = variants.get(state);

        for (Object raw : current.interactions()) {
            if (!(raw instanceof Map<?, ?> interaction)) {
                continue;
            }

            if (!(interaction.get("event") instanceof Map<?, ?> event)) {
                continue;
            }

            if (!trigger.equals(eventName(event.get("interactionType")))) {
                continue;
            }

            if (!(interaction.get("actions") instanceof List<?> actions)) {
                continue;
            }

            for (Object actionRaw : actions) {
                if (!(actionRaw instanceof Map<?, ?> action)) {
                    continue;
                }

                if (!isSwapState(action.get("navigationType"))) {
                    continue;
                }

                Integer destination = byId.get(guid(action.get("transitionNodeID")));

                if (destination == null) {
                    continue;
                }

                if ("ON_HOVER".equals(trigger)) {
                    hoverSource = state;
                    hoverDestination = destination;
                }

                float duration = number(action.get("transitionDuration"), 0f);
                String easingType = String.valueOf(action.containsKey("easingType") ? action.get("easingType") : "LINEAR");
                moveTo(destination, now, duration, easingType, action.get("easingFunction"));

                return true;
            }
        }

        return false;
    }

    /**
     * Starts a transition from the current visual snapshot, preserving continuity if another transition was interrupted.
     *
     * @param destination target variant index
     * @param now monotonic timestamp in nanoseconds
     * @param duration transition duration in seconds
     * @param easingType imported easing name or enum value
     * @param bezier custom cubic control points when supplied
     */
    private void moveTo(int destination, long now, float duration, String easingType, Object bezier) {
        if (destination == state) {
            return;
        }

        // Start from the current visual state, including an interrupted transition.
        fromShapes = snapshot(now);
        state = destination;
        animationStart = now;
        animationDuration = (long) (Math.max(0f, duration) * 1_000_000_000L);
        stateReadyAt = animationStart + animationDuration;
        stateRevision++;
        easing = easingName(easingType);

        if (bezier instanceof List<?> cp && cp.size() >= 4) {
            cubic = new float[]{number(cp.get(0), 0f), number(cp.get(1), 0f), number(cp.get(2), 1f), number(cp.get(3), 1f)};
        }
    }

    /**
     * Builds the current visual shapes using elapsed monotonic time and the selected easing curve.
     *
     * @param now monotonic timestamp in nanoseconds
     */
    private List<Map<String, Object>> snapshot(long now) {
        List<Map<String, Object>> destination = variants.get(state).shapes();

        if (animationDuration <= 0) {
            return destination;
        }

        float progress = clamp((float) (now - animationStart) / animationDuration, 0f, 1f);

        if (progress >= 1f) {
            return destination;
        }

        float t = ease(progress);
        List<Map<String, Object>> result = new ArrayList<>(destination.size());

        for (int i = 0; i < destination.size(); i++) {
            Map<String, Object> target = destination.get(i);
            Map<String, Object> source = findMatch(fromShapes, target, i);
            result.add(source == null ? target : interpolate(source, target, t));
        }

        return result;
    }

    /**
     * Matches a target shape to its source by layer name, falling back to its list position.
     *
     * @param from source state, value or color
     * @param target target shape or property map
     * @param index element or contour point index
     */
    private static Map<String, Object> findMatch(List<Map<String, Object>> from, Map<String, Object> target, int index) {
        Object name = target.get("name");

        if (index < from.size() && Objects.equals(from.get(index).get("name"), name)) {
            return from.get(index);
        }

        for (Map<String, Object> shape : from) {
            if (Objects.equals(shape.get("name"), name)) {
                return shape;
            }
        }

        return index < from.size() ? from.get(index) : null;
    }

    /**
     * Evaluates the active easing curve at normalized transition progress.
     *
     * @param t normalized interpolation progress
     */
    private float ease(float t) {
        return switch (easing) {
            case "IN_CUBIC" -> t * t * t;
            case "OUT_CUBIC" -> 1f - (float) Math.pow(1f - t, 3);
            case "INOUT_CUBIC" -> t < 0.5f ? 4f * t * t * t : 1f - (float) Math.pow(-2f * t + 2f, 3) / 2f;
            case "CUSTOM_CUBIC" -> customCubic(t);
            default -> t;
        };
    }

    /**
     * Solves the custom cubic easing curve for a normalized time coordinate.
     *
     * @param x horizontal coordinate in the method coordinate space
     */
    private float customCubic(float x) {
        float low = 0f;
        float high = 1f;

        for (int i = 0; i < 22; i++) {
            float mid = (low + high) * 0.5f;

            if (bezier(mid, cubic[0], cubic[2]) < x) {
                low = mid;
            } else {
                high = mid;
            }
        }

        return bezier((low + high) * 0.5f, cubic[1], cubic[3]);
    }

    /**
     * Evaluates a cubic Bezier coordinate with endpoints zero and one.
     *
     * @param t normalized interpolation progress
     * @param p1 first cubic control coordinate
     * @param p2 second cubic control coordinate
     */
    private static float bezier(float t, float p1, float p2) {
        float q = 1f - t;

        return 3f * q * q * t * p1 + 3f * q * t * t * p2 + t * t * t;
    }

    /**
     * Interpolates supported size, transform, corner, fill and stroke properties between two decoded shapes.
     *
     * @param from source state, value or color
     * @param to target state, value or color
     * @param t normalized interpolation progress
     */
    private static Map<String, Object> interpolate(Map<String, Object> from, Map<String, Object> to, float t) {
        Map<String, Object> result = copy(to);
        result.put("opacity", mix(number(from.get("opacity"), 1f), number(to.get("opacity"), 1f), t));
        interpolateVectorMap(from, to, result, "size", List.of("x", "y"), t);
        interpolateVectorMap(from, to, result, "transform", List.of("m00", "m01", "m02", "m10", "m11", "m12"), t);
        String[] cornerKeys = {"rectangleTopLeftCornerRadius", "rectangleTopRightCornerRadius", "rectangleBottomRightCornerRadius", "rectangleBottomLeftCornerRadius"};
        float[] startCorners = corners(from);
        float[] endCorners = corners(to);
        result.put("rectangleCornerRadiiIndependent", true);

        for (int i = 0; i < 4; i++) {
            result.put(cornerKeys[i], mix(startCorners[i], endCorners[i], t));
        }

        Object fills = interpolatePaints(from.get("fillPaints"), to.get("fillPaints"), t);

        if (fills != null) {
            result.put("fillPaints", fills);
        }

        result.put("strokeWeight", mix(number(from.get("strokeWeight"), 0f), number(to.get("strokeWeight"), 0f), t));
        Object strokes = interpolatePaints(from.get("strokePaints"), to.get("strokePaints"), t);

        if (strokes != null) {
            result.put("strokePaints", strokes);
        }

        return result;
    }

    /**
     * Interpolates selected numeric fields in a nested vector or transform property map.
     *
     * @param from source state, value or color
     * @param to target state, value or color
     * @param output destination property map
     * @param key property name or resource lookup alias
     * @param fields numeric property names to interpolate
     * @param t normalized interpolation progress
     */
    private static void interpolateVectorMap(Map<String, Object> from, Map<String, Object> to, Map<String, Object> output, String key, List<String> fields, float t) {
        if (!(from.get(key) instanceof Map<?, ?> a) || !(to.get(key) instanceof Map<?, ?> b)) {
            return;
        }

        Map<String, Object> interpolated = copy(b);

        for (String field : fields) {
            Object av = a.get(field);
            Object bv = b.get(field);

            if (av instanceof Number first && bv instanceof Number second) {
                interpolated.put(field, mix(first.floatValue(), second.floatValue(), t));
            }
        }

        output.put(key, interpolated);
    }

    /**
     * Interpolates compatible paint stacks, crossfading images when their hashes differ.
     *
     * @param fromRaw decoded source paint list
     * @param toRaw decoded target paint list
     * @param t normalized interpolation progress
     */
    private static Object interpolatePaints(Object fromRaw, Object toRaw, float t) {
        if (!(fromRaw instanceof List<?> first) || !(toRaw instanceof List<?> second) || first.size() != second.size()) {
            return null;
        }

        if (Objects.equals(fromRaw, toRaw)) {
            return toRaw;
        }

        List<Map<String, Object>> result = new ArrayList<>();

        for (int i = 0; i < first.size(); i++) {
            if (!(first.get(i) instanceof Map<?, ?> p0) || !(second.get(i) instanceof Map<?, ?> p1)) {
                return null;
            }

            if (!Objects.equals(p0.get("type"), p1.get("type"))) {
                return null;
            }

            String type = String.valueOf(p1.get("type"));

            if ("IMAGE".equals(type) || "5".equals(type)) {
                // Crossfade the old and new image paints when their content differs.
                if (!sameImage(p0.get("image"), p1.get("image"))) {
                    Map<String, Object> start = copy(p0);
                    Map<String, Object> end = copy(p1);
                    start.put("opacity", clamp(number(p0.get("opacity"), 1f) * (1f - t), 0f, 1f));
                    end.put("opacity", clamp(number(p1.get("opacity"), 1f) * t, 0f, 1f));
                    result.add(start);
                    result.add(end);
                } else {
                    result.add(copy(p1));
                }

                continue;
            }

            Map<String, Object> paint = copy(p1);
            paint.put("opacity", mix(number(p0.get("opacity"), 1f), number(p1.get("opacity"), 1f), t));

            if (p0.get("color") instanceof Map<?, ?> c0 && p1.get("color") instanceof Map<?, ?> c1) {
                paint.put("color", interpolateColor(c0, c1, t));
            }

            if (p0.get("stops") instanceof List<?> s0 && p1.get("stops") instanceof List<?> s1 && s0.size() == s1.size()) {
                List<Map<String, Object>> stops = new ArrayList<>();

                for (int j = 0; j < s0.size(); j++) {
                    if (!(s0.get(j) instanceof Map<?, ?> a) || !(s1.get(j) instanceof Map<?, ?> b)) {
                        return null;
                    }

                    Map<String, Object> stop = copy(b);
                    stop.put("position", mix(number(a.get("position"), 0f), number(b.get("position"), 0f), t));

                    if (a.get("color") instanceof Map<?, ?> ca && b.get("color") instanceof Map<?, ?> cb) {
                        stop.put("color", interpolateColor(ca, cb, t));
                    }

                    stops.add(stop);
                }

                paint.put("stops", stops);
            }

            result.add(paint);
        }

        return result;
    }

    /**
     * Compares image identities, including byte-array content hashes.
     *
     * @param a first input or channel value
     * @param b second input or channel value
     */
    private static boolean sameImage(Object a, Object b) {
        if (!(a instanceof Map<?, ?> first) || !(b instanceof Map<?, ?> second)) {
            return Objects.equals(a, b);
        }

        Object hashA = first.get("hash");
        Object hashB = second.get("hash");

        if (hashA instanceof byte[] bytesA && hashB instanceof byte[] bytesB) {
            return Arrays.equals(bytesA, bytesB);
        }

        return Objects.equals(hashA, hashB);
    }

    /**
     * Interpolates normalized RGBA color channels between two decoded colors.
     *
     * @param a first input or channel value
     * @param b second input or channel value
     * @param t normalized interpolation progress
     */
    private static Map<String, Object> interpolateColor(Map<?, ?> a, Map<?, ?> b, float t) {
        Map<String, Object> result = copy(b);

        for (String key : List.of("r", "g", "b", "a")) {
            float fallback = key.equals("a") ? 1f : 0f;
            result.put(key, mix(number(a.get(key), fallback), number(b.get(key), fallback), t));
        }

        return result;
    }

    /**
     * Reads the four source corner radii before interpolation.
     *
     * @param node decoded FIG node properties
     */
    private static float[] corners(Map<?, ?> node) {
        float radius = number(node.get("cornerRadius"), 0f);

        if (!Boolean.TRUE.equals(node.get("rectangleCornerRadiiIndependent"))) {
            return new float[]{radius, radius, radius, radius};
        }

        return new float[]{number(node.get("rectangleTopLeftCornerRadius"), radius), number(node.get("rectangleTopRightCornerRadius"), radius), number(node.get("rectangleBottomRightCornerRadius"), radius), number(node.get("rectangleBottomLeftCornerRadius"), radius)};
    }

    /**
     * Normalizes a Kiwi interaction enum number or its existing name.
     *
     * @param value value or state to inspect or assign
     */
    private static String eventName(Object value) {
        if (!(value instanceof Number n)) {
            return String.valueOf(value);
        }

        return switch (n.intValue()) {
            case 0 -> "ON_CLICK";
            case 1 -> "AFTER_TIMEOUT";
            case 2 -> "MOUSE_IN";
            case 3 -> "MOUSE_OUT";
            case 4 -> "ON_HOVER";
            case 5 -> "MOUSE_DOWN";
            case 6 -> "MOUSE_UP";
            case 7 -> "ON_PRESS";
            default -> "UNKNOWN";
        };
    }

    /**
     * Normalizes an imported easing enum to the supported curve name.
     *
     * @param raw decoded source value
     */
    private static String easingName(String raw) {
        return switch (raw) {
            case "0" -> "IN_CUBIC";
            case "1" -> "OUT_CUBIC";
            case "2" -> "INOUT_CUBIC";
            case "3" -> "LINEAR";
            default -> raw;
        };
    }

    /**
     * Checks whether an imported action navigates to another component variant.
     *
     * @param value value or state to inspect or assign
     */
    private static boolean isSwapState(Object value) {
        return "SWAP_STATE".equals(value) || value instanceof Number n && n.intValue() == 3;
    }

    /**
     * Formats an imported session/local GUID for variant lookup.
     *
     * @param raw decoded source value
     */
    private static String guid(Object raw) {
        if (!(raw instanceof Map<?, ?> map)) {
            return "";
        }

        return map.get("sessionID") + ":" + map.get("localID");
    }
}
