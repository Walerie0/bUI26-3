package net.val.bui.model;

import net.val.bui.error.BUIErrors;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static net.val.bui.model.BUIValues.copy;
import static net.val.bui.model.BUIValues.guid;

/**
 * Imported FIG hierarchy metadata with stable sibling ordering. The current renderer does not yet implement full hierarchical group compositing.
 */
public final class BUIFigLayerTree {

    /**
     * Imported layer identity, decoded properties and ordered children. The node map is shared and must be treated as read-only.
     */
    public record Layer(String id, String parentId, String position, String name, String type,
                        Map<String, Object> node, List<Layer> children) {
        /**
         * Reads this layer fill, stroke and effect stack from its decoded properties.
         *
         */
        public BUIStyleStack style() {
            return BUIStyleStack.fromNode(node);
        }
    }

    /**
     * Internal mutable-child hierarchy entry used while building a layer tree.
     */
    private record Entry(String id, String parentId, String position, int order,
                         Map<String, Object> node, List<Entry> children) {}

    /**
     * Prevents construction; use the static methods of this utility class.
     */
    private BUIFigLayerTree() {}

    /**
     * Builds the imported FIG hierarchy and sorts siblings by their stored position.
     *
     * @param document decoded FIG document
     */
    public static List<Layer> build(Map<String, Object> document) {
        if (!(document.get("nodeChanges") instanceof List<?> changes)) return List.of();
        Map<String, Entry> index = new LinkedHashMap<>();
        int order = 0;
        for (Object change : changes) {
            if (!(change instanceof Map<?, ?> raw)) continue;
            Map<String, Object> node = copy(raw);
            String id = guid(node.get("guid"));
            if (id.isEmpty()) continue;
            Map<?, ?> parent = node.get("parentIndex") instanceof Map<?, ?> map ? map : Map.of();
            Object position = parent.get("position");
            index.put(id, new Entry(id, guid(parent.get("guid")), position == null ? "" : position.toString(),
                    order++, node, new ArrayList<>()));
        }

        List<Entry> roots = new ArrayList<>();
        for (Entry entry : index.values()) {
            Entry parent = index.get(entry.parentId());
            if (parent == null || parent == entry) roots.add(entry);
            else parent.children().add(entry);
        }

        Comparator<Entry> byPosition = Comparator.comparing(Entry::position).thenComparingInt(Entry::order);
        roots.sort(byPosition);
        List<Layer> layers = new ArrayList<>();
        for (Entry root : roots) {
            layers.add(freeze(root, byPosition, new HashSet<>()));
        }
        return List.copyOf(layers);
    }

    /**
     * Converts a mutable hierarchy entry to a layer snapshot while detecting parent cycles.
     *
     * @param entry hierarchy entry to freeze
     * @param comparator stable sibling-order comparator
     * @param path absolute resource path, including the leading slash and .fig extension
     */
    private static Layer freeze(Entry entry, Comparator<Entry> comparator, Set<String> path) {
        if (!path.add(entry.id())) throw new IllegalStateException(BUIErrors.message(BUIFigLayerTree.class, "freeze", "FIG hierarchy cycle: " + entry.id(), "Remove cyclic parent references in the design or re-export the component."));
        entry.children().sort(comparator);
        List<Layer> children = new ArrayList<>();
        for (Entry child : entry.children()) {
            children.add(freeze(child, comparator, path));
        }
        path.remove(entry.id());
        return new Layer(entry.id(), entry.parentId(), entry.position(),
                String.valueOf(entry.node().getOrDefault("name", "")),
                String.valueOf(entry.node().getOrDefault("type", "")), entry.node(), List.copyOf(children));
    }
}
