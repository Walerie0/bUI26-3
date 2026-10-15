package net.val.bui.fig;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.Inflater;
import java.util.zip.InflaterInputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import net.val.bui.component.BUIComponent;
import net.val.bui.fig.kiwi.KiwiMessageDecoder;
import net.val.bui.fig.kiwi.KiwiSchema;
import net.val.bui.model.BUIFigLayerTree;
import net.val.bui.model.BUIGeometry.Bounds;
import net.val.bui.model.BUIGeometry.Transform;
import net.val.bui.model.BUIGeometry;
import net.val.bui.model.BUIValues;
import net.val.bui.error.BUIErrors;

/**
 * FIG resource entry point for consuming mods. Place components under
 * src/main/resources/assets/YOURMODID/bui/components/component_name/filename.fig and load the absolute /assets
 * path from your registry. No components are bundled by bUI.
 */
public final class FigLoader {

    /**
     * Prevents construction; use the static methods of this utility class.
     */
    private FigLoader() {}

    /**
     * Loads a reusable FIG component from an absolute resource path such as
     * {@code /assets/yourmodid/bui/components/panel/panel.fig}. Place the file at
     * {@code src/main/resources/assets/yourmodid/bui/components/panel/panel.fig} and keep the loaded definition in
     * your component registry. Missing, malformed and unsupported designs produce contextual errors with recovery
     * hints.
     *
     * @param path absolute resource path, including the leading slash and .fig extension
     * @return a reusable component definition; create an instance for each placement
     * @throws IllegalArgumentException if a required argument is null or the path is not an absolute .fig resource path
     * @throws IllegalStateException if the resource is missing or its decoded design is unsupported
     * @throws java.io.UncheckedIOException if the archive cannot be read or decompressed
     */
    public static BUIComponent load(String path) {
        return loadResource(null, path);
    }

    /**
     * Loads a reusable FIG component from an absolute resource path such as
     * {@code /assets/yourmodid/bui/components/panel/panel.fig}. Place the file at
     * {@code src/main/resources/assets/yourmodid/bui/components/panel/panel.fig} and keep the loaded definition in
     * your component registry. Missing, malformed and unsupported designs produce contextual errors with recovery
     * hints. This overload reads directly from the supplied mod class module; use it when an explicit resource owner
     * is needed.
     *
     * @param resourceOwner class belonging to the mod module that owns the resource
     * @param path absolute resource path, including the leading slash and .fig extension
     * @return a reusable component definition; create an instance for each placement
     * @throws IllegalArgumentException if a required argument is null or the path is not an absolute .fig resource path
     * @throws IllegalStateException if the resource is missing or its decoded design is unsupported
     * @throws java.io.UncheckedIOException if the archive cannot be read or decompressed
     */
    public static BUIComponent load(Class<?> resourceOwner, String path) {
        BUIErrors.requireNonNull(resourceOwner, FigLoader.class, "load", "resourceOwner");
        return loadResource(resourceOwner, path);
    }

    /**
     * Validates the public resource arguments, closes the opened archive, and builds a component with decoded layers
     * and embedded images.
     *
     * @param resourceOwner class belonging to the mod module that owns the resource
     * @param path absolute resource path, including the leading slash and .fig extension
     */
    private static BUIComponent loadResource(Class<?> resourceOwner, String path) {
        BUIErrors.requireNonNull(path, FigLoader.class, "load", "path");
        if (!path.startsWith("/") || !path.endsWith(".fig")) {
            throw BUIErrors.argument(FigLoader.class, "load", "Expected an absolute .fig resource path: " + path,
                    "Use /assets/yourmodid/bui/components/component_name/filename.fig.");
        }
        try (InputStream input = openResource(resourceOwner, path)) {
            if (input == null) {
                throw BUIErrors.state(FigLoader.class, "load", "FIG resource not found: " + path,
                        "Place the file under src/main/resources" + path
                                + "; check spelling and case, or use load(YourMod.class, path) for an explicit resource owner.");
            }
            Map<String, Object> document = null;
            Map<String, byte[]> images = new LinkedHashMap<>();
            try (ZipInputStream zip = new ZipInputStream(input)) {
                for (ZipEntry entry; (entry = zip.getNextEntry()) != null; zip.closeEntry()) {
                    String name = entry.getName().replace('\\', '/');
                    if (entry.isDirectory()) continue;

                    if (name.equals("canvas.fig") && document == null) {
                        document = readCanvas(zip);
                    } else if (name.startsWith("images/") && name.length() > 7) {
                        byte[] bytes = zip.readAllBytes();
                        if (bytes.length == 0) continue;
                        images.put(name, bytes);
                        images.putIfAbsent(name.substring(name.lastIndexOf('/') + 1), bytes);
                    }
                }
            }

            if (document == null) throw new IOException(BUIErrors.message(FigLoader.class, "loadResource", "canvas.fig not found", "Check the resource path and re-export a complete, supported .fig archive from your design tool."));
            BUIComponent component = BUIAnimationImporter.tryExtract(document);
            if (component == null) component = extractStaticComponent(document);
            Map<String, Object> design = new LinkedHashMap<>(component.getDesign());
            design.put("buiFigLayerTree", BUIFigLayerTree.build(document));
            if (!images.isEmpty()) design.put("buiEmbeddedImages", Map.copyOf(images));
            return new BUIComponent(component.getName(), design);
        } catch (IOException e) {
            throw new UncheckedIOException(BUIErrors.message(FigLoader.class, "loadResource", "Cannot load FIG component: " + path, "Check the resource path and re-export a complete, supported .fig archive from your design tool."), e);
        }
    }

    /**
     * Opens an explicit owner-module resource, or searches the game context class loader followed by the library
     * class loader. This allows consuming mods to supply their own assets.
     *
     * @param owner owning class, instance or resource module
     * @param path absolute resource path, including the leading slash and .fig extension
     */
    private static InputStream openResource(Class<?> owner, String path) {
        if (owner != null) return owner.getResourceAsStream(path);
        String name = path.substring(1);
        ClassLoader context = Thread.currentThread().getContextClassLoader();
        InputStream input = context == null ? null : context.getResourceAsStream(name);
        if (input != null) return input;
        ClassLoader library = FigLoader.class.getClassLoader();
        return library == context ? null : library.getResourceAsStream(name);
    }

    /**
     * Reads a FIG canvas header, its embedded Kiwi schema and its compressed document.
     *
     * @param input source byte stream or pixel channel buffer
     */
    private static Map<String, Object> readCanvas(InputStream input) throws IOException {
        ByteBuffer header = ByteBuffer.wrap(readExactly(input, 16)).order(ByteOrder.LITTLE_ENDIAN);
        byte[] magic = new byte[8];
        header.get(magic);
        String signature = new String(magic, StandardCharsets.US_ASCII);
        if (!signature.equals("fig-kiwi")) throw new IOException(BUIErrors.message(FigLoader.class, "readCanvas", "Unsupported FIG signature: " + signature, "Check the resource path and re-export a complete, supported .fig archive from your design tool."));
        header.getInt(); // Container version; the embedded schema describes the actual fields.
        int schemaSize = checkedSize(header.getInt(), 1_000_000, "schema");
        KiwiSchema schema = KiwiSchema.read(inflate(readExactly(input, schemaSize), 1_048_576));
        int documentSize = ByteBuffer.wrap(readExactly(input, 4)).order(ByteOrder.LITTLE_ENDIAN).getInt();
        checkedSize(documentSize, 50_000_000, "document");
        byte[] bytes = inflate(readExactly(input, documentSize), 10_000_000);
        return new KiwiMessageDecoder(schema).decode(bytes);
    }

    /**
     * Rejects non-positive or oversized compressed section sizes before allocating buffers.
     *
     * @param size compressed section size
     * @param maximum inclusive upper bound or size limit
     * @param label section name used in diagnostics
     */
    private static int checkedSize(int size, int maximum, String label) throws IOException {
        if (size <= 0 || size > maximum) throw new IOException(BUIErrors.message(FigLoader.class, "checkedSize", "Invalid FIG " + label + " size: " + size, "Check the resource path and re-export a complete, supported .fig archive from your design tool."));
        return size;
    }

    /**
     * Reads the requested byte count or reports a truncated FIG section.
     *
     * @param input source byte stream or pixel channel buffer
     * @param count requested number of elements or bytes
     */
    private static byte[] readExactly(InputStream input, int count) throws IOException {
        byte[] bytes = input.readNBytes(count);
        if (bytes.length != count) throw new IOException(BUIErrors.message(FigLoader.class, "readExactly", "Incomplete FIG data: expected " + count + " bytes", "Check the resource path and re-export a complete, supported .fig archive from your design tool."));
        return bytes;
    }

    /**
     * Decompresses a raw Deflate section within its size limit and always releases the inflater.
     *
     * @param bytes complete encoded input bytes
     * @param maximum inclusive upper bound or size limit
     */
    private static byte[] inflate(byte[] bytes, int maximum) throws IOException {
        Inflater inflater = new Inflater(true);
        try (InflaterInputStream input = new InflaterInputStream(new ByteArrayInputStream(bytes), inflater)) {
            byte[] result = input.readNBytes(maximum + 1);
            if (result.length > maximum || !inflater.finished()) {
                throw new IOException(BUIErrors.message(FigLoader.class, "inflate", "Incomplete or oversized FIG Deflate stream", "Check the resource path and re-export a complete, supported .fig archive from your design tool."));
            }
            return result;
        } finally {
            inflater.end();
        }
    }

    /**
     * Builds a static definition from supported rectangle nodes, preserving their paint order and combined bounds.
     *
     * @param document decoded FIG document
     */
    private static BUIComponent extractStaticComponent(Map<String, Object> document) {
        if (!(document.get("nodeChanges") instanceof List<?> nodes)) {
            throw new IllegalStateException(BUIErrors.message(FigLoader.class, "extractStaticComponent", "FIG: nodeChanges missing", "Export supported rectangle layers or a component set with variants. Arbitrary frame, text and vector imports are not fully supported."));
        }

        List<Map<String, Object>> shapes = new ArrayList<>();
        for (Object entry : nodes) {
            if (!(entry instanceof Map<?, ?> node) || !(node.get("type") instanceof Number type)) continue;
            if (Boolean.FALSE.equals(node.get("visible")) || type.intValue() <= 2) continue;
            if (type.intValue() != 10 && type.intValue() != 12) {
                throw new IllegalStateException(BUIErrors.message(FigLoader.class, "extractStaticComponent", "FIG node type not supported yet: " + type + " (" + node.get("name") + ")", "Export supported rectangle layers or a component set with variants. Arbitrary frame, text and vector imports are not fully supported."));
            }
            if (!(node.get("size") instanceof Map<?, ?>) || !(node.get("transform") instanceof Map<?, ?>)) {
                throw new IllegalStateException(BUIErrors.message(FigLoader.class, "extractStaticComponent", "FIG node is missing size/transform: " + node.get("name"), "Export supported rectangle layers or a component set with variants. Arbitrary frame, text and vector imports are not fully supported."));
            }
            shapes.add(BUIValues.copy(node));
        }

        if (shapes.isEmpty()) throw new IllegalStateException(BUIErrors.message(FigLoader.class, "extractStaticComponent", "No supported FIG shapes found", "Export supported rectangle layers or a component set with variants. Arbitrary frame, text and vector imports are not fully supported."));
        if (shapes.size() == 1) {
            Map<String, Object> shape = shapes.getFirst();
            return new BUIComponent(String.valueOf(shape.getOrDefault("name", "unnamed")), shape);
        }

        shapes.sort(Comparator.comparing(FigLoader::orderKey));
        Bounds bounds = null;
        for (Map<String, Object> shape : shapes) {
            float width = BUIGeometry.width(shape);
            float height = BUIGeometry.height(shape);
            if (width <= 0 || height <= 0) {
                throw new IllegalStateException(BUIErrors.message(FigLoader.class, "extractStaticComponent", "Invalid FIG dimensions: " + shape.get("name"), "Export supported rectangle layers or a component set with variants. Arbitrary frame, text and vector imports are not fully supported."));
            }
            Bounds next = Transform.from(shape.get("transform")).bounds(width, height);
            bounds = bounds == null ? next : bounds.union(next);
        }

        Map<String, Object> design = new LinkedHashMap<>();
        design.put("name", "fig_composite");
        design.put("size", Map.of("x", bounds.width(), "y", bounds.height()));
        design.put("buiMinX", bounds.left());
        design.put("buiMinY", bounds.top());
        design.put("buiChildren", List.copyOf(shapes));
        return new BUIComponent("fig_composite", design);
    }

    /**
     * Reads the imported sibling position used to order static FIG shapes.
     *
     * @param node decoded FIG node properties
     */
    private static String orderKey(Map<String, Object> node) {
        if (node.get("parentIndex") instanceof Map<?, ?> parent && parent.get("position") instanceof String position) {
            return position;
        }
        return "";
    }
}
