package net.val.bui.fig.kiwi;

import net.val.bui.error.BUIErrors;

import java.util.ArrayList;
import java.util.List;

/**
 * Internal immutable schema descriptors decoded from the same FIG file as its document.
 */
public record KiwiSchema(List<Definition> definitions) {

    /**
     * One Kiwi field name, wire type, array flag and numeric field or enum value.
     */
    public record Field(String name, int type, boolean array, int value) {}

    /**
     * One named Kiwi enum, struct or message with its ordered schema fields.
     */
    public record Definition(String name, int kind, List<Field> fields) {
        /**
         * Copies the fields belonging to one Kiwi enum, struct or message definition.
         *
         * @param name exact component, layer or schema name
         * @param kind Kiwi definition kind: enum, struct or message
         * @param fields ordered field descriptors belonging to this definition
         */
        public Definition {
            fields = List.copyOf(fields);
        }

        /**
         * Finds a field by its wire ID, returning null when that ID is not defined.
         *
         * @param id namespaced action ID or imported node/field identifier
         */
        public Field field(int id) {
            for (Field field : fields) {
                if (field.value() == id) return field;
            }
            return null;
        }
    }

    /**
     * Copies the schema definition list for use by a Kiwi decoder.
     *
     * @param definitions ordered Kiwi schema definitions
     */
    public KiwiSchema {
        definitions = List.copyOf(definitions);
    }

    /**
     * Finds a schema definition by exact name or reports that the schema does not contain it.
     *
     * @param name exact component, layer or schema name
     */
    public Definition definition(String name) {
        return definitions.stream()
                .filter(definition -> definition.name().equals(name))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(BUIErrors.message(KiwiSchema.class, "definition", "Kiwi definition not found: " + name, "Read the schema embedded in the .fig archive; do not combine schemas and documents from different files.")));
    }

    /**
     * Decodes a complete embedded Kiwi schema and rejects unread trailing bytes.
     *
     * @param bytes complete encoded input bytes
     */
    public static KiwiSchema read(byte[] bytes) {
        KiwiReader reader = new KiwiReader(bytes);
        int count = reader.readVarUint();
        List<Definition> definitions = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            String name = reader.readString();
            int kind = reader.readByte();
            int fieldCount = reader.readVarUint();
            List<Field> fields = new ArrayList<>();

            for (int j = 0; j < fieldCount; j++) {
                fields.add(new Field(reader.readString(), reader.readVarInt(),
                        (reader.readByte() & 1) != 0, reader.readVarUint()));
            }
            definitions.add(new Definition(name, kind, fields));
        }

        if (reader.remaining() != 0) {
            throw new IllegalStateException(BUIErrors.message(KiwiSchema.class, "read", "Kiwi schema has " + reader.remaining() + " unread bytes", "Read the schema embedded in the .fig archive; do not combine schemas and documents from different files."));
        }
        return new KiwiSchema(definitions);
    }
}
