package net.val.bui.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Internal decoded-value helpers; ordinary mod integration should use the component API.
 */
public final class BUIValues {

    /**
     * Prevents construction; use the static methods of this utility class.
     */
    private BUIValues() {}

    /**
     * Reads a numeric design property as a float or returns its fallback.
     *
     * @param value value or state to inspect or assign
     * @param fallback value used when the decoded property is absent or non-numeric
     */
    public static float number(Object value, float fallback) {
        return value instanceof Number number ? number.floatValue() : fallback;
    }

    /**
     * Constrains a float to its inclusive interval.
     *
     * @param value value or state to inspect or assign
     * @param minimum inclusive lower bound
     * @param maximum inclusive upper bound or size limit
     */
    public static float clamp(float value, float minimum, float maximum) {
        return Math.max(minimum, Math.min(maximum, value));
    }

    /**
     * Linearly interpolates two floats using the supplied progress.
     *
     * @param from source state, value or color
     * @param to target state, value or color
     * @param progress normalized interpolation progress
     */
    public static float mix(float from, float to, float progress) {
        return from + (to - from) * progress;
    }

    /**
     * Makes a mutable top-level copy with string keys; nested decoded values remain shared.
     *
     * @param source source map or data
     */
    public static Map<String, Object> copy(Map<?, ?> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(String.valueOf(key), value));
        return result;
    }

    /**
     * Formats a complete session/local GUID or returns an empty string when either part is missing.
     *
     * @param value value or state to inspect or assign
     */
    public static String guid(Object value) {
        if (!(value instanceof Map<?, ?> map)) return "";
        Object session = map.get("sessionID");
        Object local = map.get("localID");
        return session == null || local == null ? "" : session + ":" + local;
    }
}
