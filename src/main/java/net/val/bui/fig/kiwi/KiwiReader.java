package net.val.bui.fig.kiwi;

import java.nio.charset.StandardCharsets;
import net.val.bui.error.BUIErrors;

/**
 * Internal bounded reader for the binary Kiwi wire format used by FIG files.
 */
public class KiwiReader {

    private final byte[] data;
    private int pos;

    /**
     * Creates a reader over a complete Kiwi byte array. Prefer FigLoader for normal mod integration.
     *
     * @param data complete encoded input bytes
     */
    public KiwiReader(byte[] data) {
        this.data = BUIErrors.requireNonNull(data, KiwiReader.class, "KiwiReader", "data");
    }

    /**
     * Reads one unsigned byte and advances the cursor, reporting truncated data with its byte offset.
     *
     */
    public int readByte() {
        if (pos >= data.length) {
            throw new IllegalStateException(BUIErrors.message(KiwiReader.class, "readByte", "Unexpected end of Kiwi data at byte " + pos, "Provide complete, correctly encoded Kiwi bytes or re-export the original .fig archive."));
        }

        return data[pos++] & 0xFF;
    }

    /**
     * Reads a Kiwi unsigned 32-bit variable-length integer with overflow validation.
     *
     */
    public int readVarUint() {
        int result = 0;

        for (int i = 0; i < 5; i++) {
            int value = readByte();

            if (i == 4 && (value & 0xF0) != 0) {
                throw new IllegalStateException(BUIErrors.message(KiwiReader.class, "readVarUint", "Kiwi VarUint exceeds 32 bits", "Provide complete, correctly encoded Kiwi bytes or re-export the original .fig archive."));
            }

            result |= (value & 0x7F) << (7 * i);

            if ((value & 0x80) == 0) {
                return result;
            }
        }

        throw new IllegalStateException(BUIErrors.message(KiwiReader.class, "readVarUint", "Invalid VarUint", "Provide complete, correctly encoded Kiwi bytes or re-export the original .fig archive."));
    }

    /**
     * Reads a ZigZag-encoded signed 32-bit integer.
     *
     */
    public int readVarInt() {
        int value = readVarUint();
        return (value >>> 1) ^ -(value & 1);
    }

    /**
     * Reads a Kiwi variable float, restoring its rotated IEEE 754 bits.
     *
     */
    public float readVarFloat() {
        int first = readByte();

        if (first == 0) {
            return 0.0f;
        }

        // Kiwi stores the IEEE 754 exponent in the low bits before byte encoding.
        int bits = first
                | (readByte() << 8)
                | (readByte() << 16)
                | (readByte() << 24);
        bits = Integer.rotateLeft(bits, 23);

        return Float.intBitsToFloat(bits);
    }

    /**
     * Reads a zero-terminated UTF-8 string from the current cursor.
     *
     */
    public String readString() {
        int start = pos;

        while (readByte() != 0) {
            // Kiwi strings are UTF-8 and terminated by a zero byte.
        }

        return new String(data, start, pos - start - 1, StandardCharsets.UTF_8);
    }

    /**
     * Reads a Kiwi unsigned 64-bit integer; its final encoding byte carries eight payload bits.
     *
     */
    public long readVarUint64() {
        long result = 0;

        for (int i = 0; i < 9; i++) {
            int value = readByte();

            if (i == 8) {
                // The ninth byte carries eight payload bits instead of a continuation flag.
                result |= (long) value << 56;
                return result;
            }

            result |= (long) (value & 0x7F) << (i * 7);

            if ((value & 0x80) == 0) {
                return result;
            }
        }

        throw new IllegalStateException(BUIErrors.message(KiwiReader.class, "readVarUint64", "Invalid VarUint64", "Provide complete, correctly encoded Kiwi bytes or re-export the original .fig archive."));
    }

    /**
     * Reads a ZigZag-encoded signed 64-bit integer.
     *
     */
    public long readVarInt64() {
        long value = readVarUint64();
        return (value >>> 1) ^ -(value & 1);
    }

    /**
     * Copies a bounded number of bytes and advances the cursor.
     *
     * @param count requested number of elements or bytes
     */
    public byte[] readBytes(int count) {
        if (count < 0 || count > data.length - pos) {
            throw new IllegalStateException(BUIErrors.message(KiwiReader.class, "readBytes", "Invalid Kiwi byte count: " + count, "Provide complete, correctly encoded Kiwi bytes or re-export the original .fig archive."));
        }

        byte[] result = new byte[count];
        System.arraycopy(data, pos, result, 0, count);
        pos += count;

        return result;
    }

    /**
     * Returns the current byte offset within the source data.
     *
     */
    public int position() {
        return pos;
    }

    /**
     * Returns the number of unread source bytes.
     *
     */
    public int remaining() {
        return data.length - pos;
    }
}
