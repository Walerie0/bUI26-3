package net.val.bui.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static net.val.bui.model.BUIValues.copy;
import static net.val.bui.model.BUIValues.number;

/**
 * Ordered imported fill, stroke and effect metadata for one FIG layer; decoding an effect does not imply that its renderer is implemented.
 */
public record BUIStyleStack(List<PaintLayer> fills, List<PaintLayer> strokes, List<EffectLayer> effects,
                            float strokeWeight, String strokeAlign) {

    /**
     * Copies the ordered fill, stroke and effect lists for one layer.
     *
     * @param fills fills in imported paint order
     * @param strokes strokes in imported paint order
     * @param effects effects in imported stack order
     * @param strokeWeight stroke width in design units
     * @param strokeAlign normalized stroke alignment name
     */
    public BUIStyleStack {
        fills = List.copyOf(fills);
        strokes = List.copyOf(strokes);
        effects = List.copyOf(effects);
    }

    /**
     * A paint original list index and its read-only top-level properties.
     */
    public record PaintLayer(int index, Map<String, Object> properties) {
        /**
         * Reports whether this paint is enabled in the imported design.
         *
         */
        public boolean visible() {
            return !Boolean.FALSE.equals(properties.get("visible"));
        }

        /**
         * Returns the normalized solid, gradient or image paint type name.
         *
         */
        public String type() {
            if (!(properties.get("type") instanceof Number value)) return String.valueOf(properties.get("type"));
            return switch (value.intValue()) {
                case 0 -> "SOLID";
                case 1 -> "GRADIENT_LINEAR";
                case 2 -> "GRADIENT_RADIAL";
                case 3 -> "GRADIENT_ANGULAR";
                case 4 -> "GRADIENT_DIAMOND";
                case 5 -> "IMAGE";
                default -> "UNKNOWN_" + value.intValue();
            };
        }

        /**
         * Returns the imported blend-mode metadata without applying it.
         *
         */
        public String blendMode() {
            return String.valueOf(properties.getOrDefault("blendMode", "NORMAL"));
        }

        /**
         * Returns this paint opacity, defaulting to one when absent.
         *
         */
        public float opacity() {
            return number(properties.get("opacity"), 1);
        }
    }

    /**
     * An effect original list index and its read-only top-level properties.
     */
    public record EffectLayer(int index, Map<String, Object> properties) {
        /**
         * Reports whether this effect is enabled in the imported design.
         *
         */
        public boolean visible() {
            return !Boolean.FALSE.equals(properties.get("visible"));
        }

        /**
         * Returns the normalized effect type name from its Kiwi enum value.
         *
         */
        public String type() {
            if (!(properties.get("type") instanceof Number value)) return String.valueOf(properties.get("type"));
            return switch (value.intValue()) {
                case 0 -> "INNER_SHADOW";
                case 1 -> "DROP_SHADOW";
                case 2 -> "FOREGROUND_BLUR";
                case 3 -> "BACKGROUND_BLUR";
                case 6 -> "GRAIN";
                case 7 -> "NOISE";
                case 8 -> "GLASS";
                default -> "UNKNOWN_" + value.intValue();
            };
        }

        /**
         * Returns the imported effect blend-mode metadata without applying it.
         *
         */
        public String blendMode() {
            return String.valueOf(properties.getOrDefault("blendMode", "NORMAL"));
        }

        /**
         * Returns the effect blur or texture radius in design units.
         *
         */
        public float radius() {
            return number(properties.get("radius"), 0);
        }

        /**
         * Returns the shadow spread in design units.
         *
         */
        public float spread() {
            return number(properties.get("spread"), 0);
        }

        /**
         * Returns the horizontal shadow offset in design units.
         *
         */
        public float offsetX() {
            return coordinate("x");
        }

        /**
         * Returns the vertical shadow offset in design units.
         *
         */
        public float offsetY() {
            return coordinate("y");
        }

        /**
         * Reads one coordinate from the effect offset map.
         *
         * @param key property name or resource lookup alias
         */
        private float coordinate(String key) {
            return properties.get("offset") instanceof Map<?, ?> offset ? number(offset.get(key), 0) : 0;
        }
    }

    /**
     * Reads ordered fill, stroke and effect metadata from a decoded FIG node.
     *
     * @param node decoded FIG node properties
     */
    public static BUIStyleStack fromNode(Map<?, ?> node) {
        Object alignment = node.get("strokeAlign");
        String align = alignment instanceof Number value ? switch (value.intValue()) {
            case 1 -> "INSIDE";
            case 2 -> "OUTSIDE";
            default -> "CENTER";
        } : alignment == null ? "CENTER" : alignment.toString();

        List<PaintLayer> fills = paints(node.get("fillPaints"));
        List<PaintLayer> strokes = paints(node.get("strokePaints"));
        List<EffectLayer> effects = new ArrayList<>();
        if (node.get("effects") instanceof List<?> entries) {
            for (int i = 0; i < entries.size(); i++) {
                if (entries.get(i) instanceof Map<?, ?> effect) {
                    effects.add(new EffectLayer(i, Collections.unmodifiableMap(copy(effect))));
                }
            }
        }
        return new BUIStyleStack(fills, strokes, effects, number(node.get("strokeWeight"), 0), align);
    }

    /**
     * Reports whether at least one declared effect is visible.
     *
     */
    public boolean hasVisibleEffects() {
        return effects.stream().anyMatch(EffectLayer::visible);
    }

    /**
     * Reads indexed paint layers while preserving the original list positions.
     *
     * @param value value or state to inspect or assign
     */
    private static List<PaintLayer> paints(Object value) {
        if (!(value instanceof List<?> entries)) return List.of();
        List<PaintLayer> paints = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i) instanceof Map<?, ?> paint) {
                paints.add(new PaintLayer(i, Collections.unmodifiableMap(copy(paint))));
            }
        }
        return paints;
    }
}
