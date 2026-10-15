package net.val.bui.fig.kiwi;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.val.bui.fig.kiwi.KiwiSchema.Definition;
import net.val.bui.fig.kiwi.KiwiSchema.Field;
import net.val.bui.error.BUIErrors;

/**
 * Internal schema-driven decoder for Kiwi document messages.
 */
public final class KiwiMessageDecoder {

    private static final int MAX_DEPTH = 64;
    private static final int MAX_ARRAY_SIZE = 1_000_000;

    private final KiwiSchema schema;

    /**
     * Creates a decoder bound to the schema embedded in the same FIG document.
     *
     * @param schema schema decoded from this same FIG file
     */
    public KiwiMessageDecoder(KiwiSchema schema) {
        this.schema = BUIErrors.requireNonNull(schema, KiwiMessageDecoder.class, "KiwiMessageDecoder", "schema");
    }

    /**
     * Decodes the root Kiwi Message and requires the document bytes to be consumed completely.
     *
     * @param data complete encoded input bytes
     */
    public Map<String, Object> decode(byte[] data) {
        KiwiReader reader = new KiwiReader(data);
        Map<String, Object> document = readDefinition(reader, schema.definition("Message"), 0);
        if (reader.remaining() != 0) {
            throw new IllegalStateException(BUIErrors.message(KiwiMessageDecoder.class, "decode", "Kiwi document has " + reader.remaining() + " unread bytes", "Use the schema embedded in the same .fig file and re-export files with corrupt or unsupported data."));
        }
        return document;
    }

    /**
     * Reads fixed-order struct fields or tagged message fields within the nesting limit.
     *
     * @param reader current Kiwi byte reader
     * @param definition schema descriptor being decoded
     * @param depth current bounded recursion depth
     */
    private Map<String, Object> readDefinition(KiwiReader reader, Definition definition, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalStateException(BUIErrors.message(KiwiMessageDecoder.class, "readDefinition", "Maximum Kiwi nesting depth exceeded", "Use the schema embedded in the same .fig file and re-export files with corrupt or unsupported data."));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        switch (definition.kind()) {
            case 1 -> {
                // Struct fields have no tags: their order is part of the wire format.
                for (Field field : definition.fields()) {
                    result.put(field.name(), readField(reader, field, depth + 1));
                }
            }
            case 2 -> {
                for (int id = reader.readVarUint(); id != 0; id = reader.readVarUint()) {
                    Field field = definition.field(id);
                    if (field == null) {
                        throw new IllegalStateException(BUIErrors.message(KiwiMessageDecoder.class, "readDefinition", "Unknown field " + id + " in "
                                + definition.name() + " at byte " + reader.position(), "Use the schema embedded in the same .fig file and re-export files with corrupt or unsupported data."));
                    }
                    result.put(field.name(), readField(reader, field, depth + 1));
                }
            }
            default -> throw new IllegalStateException(BUIErrors.message(KiwiMessageDecoder.class, "readDefinition", "Cannot decode ENUM as object: " + definition.name(), "Use the schema embedded in the same .fig file and re-export files with corrupt or unsupported data."));
        }
        return result;
    }

    /**
     * Decodes one scalar or bounded-array field using its declared Kiwi type.
     *
     * @param reader current Kiwi byte reader
     * @param field schema field being decoded
     * @param depth current bounded recursion depth
     */
    private Object readField(KiwiReader reader, Field field, int depth) {
        if (!field.array()) return readValue(reader, field.type(), depth);
        int count = reader.readVarUint();
        if (count < 0 || count > MAX_ARRAY_SIZE) {
            throw new IllegalStateException(BUIErrors.message(KiwiMessageDecoder.class, "readField", "Invalid Kiwi array size: " + count, "Use the schema embedded in the same .fig file and re-export files with corrupt or unsupported data."));
        }
        if (field.type() == -2) return reader.readBytes(count);
        if (count > reader.remaining()) {
            throw new IllegalStateException(BUIErrors.message(KiwiMessageDecoder.class, "readField", "Array exceeds remaining data: " + field.name(), "Use the schema embedded in the same .fig file and re-export files with corrupt or unsupported data."));
        }

        List<Object> values = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            values.add(readValue(reader, field.type(), depth));
        }
        return values;
    }

    /**
     * Decodes a primitive, enum or nested schema value.
     *
     * @param reader current Kiwi byte reader
     * @param type numeric or named imported type
     * @param depth current bounded recursion depth
     */
    private Object readValue(KiwiReader reader, int type, int depth) {
        return switch (type) {
            case -1 -> reader.readByte() != 0;
            case -2 -> reader.readByte();
            case -3 -> reader.readVarInt();
            case -4 -> Integer.toUnsignedLong(reader.readVarUint());
            case -5 -> reader.readVarFloat();
            case -6 -> reader.readString();
            case -7 -> reader.readVarInt64();
            case -8 -> reader.readVarUint64();
            default -> {
                if (type < 0 || type >= schema.definitions().size()) {
                    throw new IllegalStateException(BUIErrors.message(KiwiMessageDecoder.class, "readValue", "Invalid Kiwi type: " + type, "Use the schema embedded in the same .fig file and re-export files with corrupt or unsupported data."));
                }
                Definition definition = schema.definitions().get(type);
                yield definition.kind() == 0 ? reader.readVarUint() : readDefinition(reader, definition, depth + 1);
            }
        };
    }
}
