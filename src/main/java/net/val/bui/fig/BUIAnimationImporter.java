package net.val.bui.fig;

import net.val.bui.error.BUIErrors;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.val.bui.component.BUIComponent;

import static net.val.bui.model.BUIValues.copy;
import static net.val.bui.model.BUIValues.guid;
import static net.val.bui.model.BUIValues.number;

/**
 * Internal extractor for component-set variants and their supported child shapes.
 */
final class BUIAnimationImporter {

    private static final List<String> CORNER_KEYS = List.of("rectangleTopLeftCornerRadius",
            "rectangleTopRightCornerRadius", "rectangleBottomRightCornerRadius", "rectangleBottomLeftCornerRadius");

    /**
     * Prevents construction; use the static methods of this utility class.
     */
    private BUIAnimationImporter() {}

    /**
     * Extracts a component set and its variants when present, or returns no animated definition for a static document.
     *
     * @param document decoded FIG document
     */
    static BUIComponent tryExtract(Map<String, Object> document) {
        if (!(document.get("nodeChanges") instanceof List<?> changes)) return null;

        List<Map<String, Object>> nodes = new ArrayList<>();
        for (Object change : changes) {
            if (change instanceof Map<?, ?> node) nodes.add(copy(node));
        }
        Map<String, Object> group = nodes.stream()
                .filter(node -> type(node, 4, "FRAME") && Boolean.TRUE.equals(node.get("isStateGroup")))
                .findFirst().orElse(null);
        if (group == null) return null;

        List<Map<String, Object>> symbols = childrenOf(nodes, guid(group.get("guid"))).stream()
                .filter(node -> type(node, 15, "SYMBOL")).toList();
        if (symbols.isEmpty()) throw new IllegalStateException(BUIErrors.message(BUIAnimationImporter.class, "tryExtract", "No variants in " + group.get("name"), "Export a component set with at least one variant containing supported rectangle layers."));

        List<Map<String, Object>> states = new ArrayList<>();
        for (Map<String, Object> symbol : symbols) {
            states.add(readState(symbol, childrenOf(nodes, guid(symbol.get("guid")))));
        }
        Map<String, Object> design = new LinkedHashMap<>();
        design.put("name", group.get("name"));
        design.put("size", symbols.getFirst().get("size"));
        design.put("buiAnimationStates", List.copyOf(states));
        return new BUIComponent(String.valueOf(group.get("name")), design);
    }

    /**
     * Collects supported shapes, inherited corner defaults and interaction metadata for one variant.
     *
     * @param symbol variant symbol node
     * @param children ordered child nodes
     */
    private static Map<String, Object> readState(Map<String, Object> symbol, List<Map<String, Object>> children) {
        List<Map<String, Object>> shapes = new ArrayList<>();
        List<Object> interactions = new ArrayList<>();
        List<String> interactiveNames = new ArrayList<>();
        appendInteractions(interactions, symbol.get("prototypeInteractions"));

        for (Map<String, Object> child : children) {
            if (child.get("prototypeInteractions") instanceof List<?> triggers && !triggers.isEmpty()) {
                interactiveNames.add(String.valueOf(child.get("name")));
            }
            appendInteractions(interactions, child.get("prototypeInteractions"));
            if (!type(child, 10, "RECTANGLE") && !type(child, 12, "ROUNDED_RECTANGLE")) continue;

            Map<String, Object> shape = copy(child);
            if (!hasCorners(shape) && hasCorners(symbol)) {
                float radius = number(symbol.get("cornerRadius"), 0);
                shape.put("rectangleCornerRadiiIndependent", true);
                for (String key : CORNER_KEYS) shape.put(key, number(symbol.get(key), radius));
            }
            shape.put("opacity", number(shape.get("opacity"), 1) * number(symbol.get("opacity"), 1));
            shapes.add(shape);
        }
        if (shapes.isEmpty()) {
            throw new IllegalStateException(BUIErrors.message(BUIAnimationImporter.class, "readState", "Variant " + symbol.get("name") + " has no supported shapes", "Export a component set with at least one variant containing supported rectangle layers."));
        }

        Map<String, Object> state = new LinkedHashMap<>();
        state.put("name", symbol.get("name"));
        state.put("id", guid(symbol.get("guid")));
        state.put("shapes", List.copyOf(shapes));
        state.put("shape", shapes.getFirst());
        state.put("interactions", List.copyOf(interactions));
        state.put("buiInteractiveChildNames", List.copyOf(interactiveNames));
        return state;
    }

    /**
     * Selects and sorts the immediate children of an imported FIG node.
     *
     * @param nodes available decoded nodes
     * @param parentId parent GUID to match
     */
    private static List<Map<String, Object>> childrenOf(List<Map<String, Object>> nodes, String parentId) {
        return nodes.stream().filter(node -> parentId.equals(parentId(node)))
                .sorted(Comparator.comparing(BUIAnimationImporter::position)).toList();
    }

    /**
     * Appends decoded interactions when the source property is a list.
     *
     * @param output destination property map
     * @param value value or state to inspect or assign
     */
    private static void appendInteractions(List<Object> output, Object value) {
        if (value instanceof List<?> interactions) output.addAll(interactions);
    }

    /**
     * Matches a node type against its numeric Kiwi value or symbolic name.
     *
     * @param node decoded FIG node properties
     * @param numeric expected numeric enum value
     * @param name exact component, layer or schema name
     */
    private static boolean type(Map<?, ?> node, int numeric, String name) {
        return node.get("type") instanceof Number value ? value.intValue() == numeric : name.equals(node.get("type"));
    }

    /**
     * Reads the parent GUID from a decoded node.
     *
     * @param node decoded FIG node properties
     */
    private static String parentId(Map<?, ?> node) {
        return node.get("parentIndex") instanceof Map<?, ?> parent ? guid(parent.get("guid")) : "";
    }

    /**
     * Reads the sibling sort position from a decoded node.
     *
     * @param node decoded FIG node properties
     */
    private static String position(Map<?, ?> node) {
        return node.get("parentIndex") instanceof Map<?, ?> parent ? String.valueOf(parent.get("position")) : "";
    }

    /**
     * Checks whether a node defines any positive corner radius.
     *
     * @param node decoded FIG node properties
     */
    private static boolean hasCorners(Map<?, ?> node) {
        return number(node.get("cornerRadius"), 0) > 0
                || CORNER_KEYS.stream().anyMatch(key -> number(node.get(key), 0) > 0);
    }
}
