package com.baafoo.core.util;

/**
 * Shared varint (base-128) encoding/decoding utilities.
 *
 * <p>Used by both the Kafka protocol decoder and the Pulsar mock broker handler
 * to avoid duplicating varint read/write/size logic. Two flavours are provided:
 * <ul>
 *   <li>{@code byte[]} variants with an {@code int[]} cursor} — used by Pulsar's
 *       protobuf-style parsing where position is tracked externally.</li>
 *   <li>Static size/encode helpers — shared by both implementations.</li>
 * </ul>
 */
public final class VarintCodec {

    private VarintCodec() {}

    // ---- byte[] variants (Pulsar / protobuf style) ----

    /**
     * Read an unsigned varint from {@code data} at the current cursor position,
     * advancing {@code pos[0]} past the bytes consumed.
     *
     * @return the decoded value, or {@code -1} if the cursor is past the end of data
     */
    public static int readVarint(byte[] data, int[] pos) {
        int value = 0;
        int shift = 0;
        int b;
        do {
            if (pos[0] >= data.length) return -1;
            b = data[pos[0]++] & 0xFF;
            value |= (b & 0x7F) << shift;
            shift += 7;
        } while ((b & 0x80) != 0);
        return value;
    }

    /**
     * Read an unsigned varint from {@code data} at {@code offset} without
     * advancing a cursor.
     *
     * @return the decoded value, or {@code -1} if data is exhausted
     */
    public static int readVarint(byte[] data, int offset) {
        int value = 0;
        int shift = 0;
        int idx = offset;
        int b;
        do {
            if (idx >= data.length) return -1;
            b = data[idx++] & 0xFF;
            value |= (b & 0x7F) << shift;
            shift += 7;
        } while ((b & 0x80) != 0);
        return value;
    }

    /**
     * Read a 64-bit unsigned varint from {@code data} at the current cursor position,
     * advancing {@code pos[0]}.
     */
    public static long readVarint64(byte[] data, int[] pos) {
        long value = 0;
        int shift = 0;
        int b;
        do {
            if (pos[0] >= data.length) return 0;
            b = data[pos[0]++] & 0xFF;
            value |= ((long) (b & 0x7F)) << shift;
            shift += 7;
        } while ((b & 0x80) != 0);
        return value;
    }

    /**
     * Number of bytes needed to encode {@code value} as an unsigned varint.
     */
    public static int varintSize(int value) {
        int size = 0;
        int v = value;
        do {
            size++;
            v >>>= 7;
        } while (v != 0);
        return size;
    }

    // ---- zig-zag variants (Kafka RecordBatch style) ----

    /**
     * Zig-zag encode a 32-bit integer (as used by Kafka RecordBatch fields).
     */
    public static int zigzagEncode(int value) {
        return (value << 1) ^ (value >> 31);
    }

    /**
     * Zig-zag decode a 32-bit integer.
     */
    public static int zigzagDecode(int raw) {
        return (raw >>> 1) ^ -(raw & 1);
    }

    /**
     * Number of bytes needed to encode {@code value} as a zig-zag varint.
     */
    public static int zigzagVarintSize(int value) {
        return varintSize(zigzagEncode(value));
    }
}
