package com.baafoo.server.broker.codec;

import io.netty.buffer.ByteBuf;

import java.nio.charset.StandardCharsets;

/**
 * Kafka flexible-versions (KIP-482) codec utilities.
 *
 * <p>Provides compact string, compact nullable bytes, and unsigned varint
 * read/write operations that are used by Kafka API versions at or above
 * the flexible-version threshold (Produce v9, Fetch v12, Metadata v9,
 * ApiVersions v3, etc.).
 *
 * <p><b>Compact string</b>: unsigned-varint length prefix where
 * {@code 0 = null}, {@code N = N-1 bytes follow} (so length 1 = empty string).
 *
 * <p><b>Compact bytes</b>: same encoding as compact string but for raw bytes.
 *
 * <p><b>Unsigned varint</b>: big-endian base-128, NOT zig-zag encoded.
 * Used for array lengths and compact-string lengths in flexible versions.
 *
 * <p><b>Zig-zag varint</b>: used inside RecordBatch records for
 * timestampDelta, offsetDelta, key/value lengths. Delegated to
 * {@link com.baafoo.core.util.VarintCodec}.
 *
 * <p>This class is stateless; all methods are static and thread-safe.
 */
public final class KafkaFlexibleCodec {

    private KafkaFlexibleCodec() {
    }

    // ===== Unsigned varint (KIP-482 array lengths, compact-string lengths) =====

    /**
     * Read a Kafka unsigned varint (big-endian base-128, NOT zig-zag).
     */
    public static int readUnsignedVarint(ByteBuf buf) {
        int result = 0;
        int shift = 0;
        int b;
        do {
            b = buf.readByte() & 0xFF;
            result |= (b & 0x7F) << shift;
            shift += 7;
        } while ((b & 0x80) != 0);
        return result;
    }

    /**
     * Write a Kafka unsigned varint (big-endian base-128, NOT zig-zag).
     */
    public static void writeUnsignedVarint(ByteBuf buf, int value) {
        while ((value & ~0x7F) != 0) {
            buf.writeByte((byte) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
        buf.writeByte((byte) value);
    }

    /**
     * Number of bytes the unsigned varint encoding of {@code value} occupies.
     */
    public static int unsignedVarintSize(int value) {
        int bytes = 1;
        while ((value & ~0x7F) != 0) {
            value >>>= 7;
            bytes++;
        }
        return bytes;
    }

    // ===== Compact string (KIP-482 flexible versions) =====

    /**
     * Read a Kafka compact string (nullable).
     * <p>Encoding: uvarint length where 0 = null, N = N-1 bytes follow.
     *
     * @return the string, or {@code null} if the compact length is 0
     */
    public static String readCompactString(ByteBuf buf) {
        int length = readUnsignedVarint(buf);
        if (length == 0) return null;
        int actualLen = length - 1;
        if (actualLen < 0) return null;
        byte[] bytes = new byte[actualLen];
        buf.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Write a Kafka compact string (nullable).
     * <p>Encoding: uvarint(N+1) + N bytes, or uvarint(0) for null.
     */
    public static void writeCompactString(ByteBuf buf, String value) {
        if (value == null) {
            writeUnsignedVarint(buf, 0);
        } else {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            writeUnsignedVarint(buf, bytes.length + 1);
            buf.writeBytes(bytes);
        }
    }

    /**
     * Read a Kafka compact string (non-nullable). An empty string is
     * encoded as uvarint(1) + 0 bytes.
     */
    public static String readCompactStringNotNullable(ByteBuf buf) {
        int length = readUnsignedVarint(buf);
        int actualLen = length - 1;
        if (actualLen <= 0) return "";
        byte[] bytes = new byte[actualLen];
        buf.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Write a Kafka compact string (non-nullable).
     */
    public static void writeCompactStringNotNullable(ByteBuf buf, String value) {
        if (value == null) value = "";
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        writeUnsignedVarint(buf, bytes.length + 1);
        buf.writeBytes(bytes);
    }

    // ===== Compact bytes (KIP-482 flexible versions) =====

    /**
     * Read Kafka compact bytes (nullable).
     */
    public static byte[] readCompactBytes(ByteBuf buf) {
        int length = readUnsignedVarint(buf);
        if (length == 0) return null;
        int actualLen = length - 1;
        if (actualLen < 0) return null;
        byte[] bytes = new byte[actualLen];
        buf.readBytes(bytes);
        return bytes;
    }

    /**
     * Write Kafka compact bytes (nullable).
     */
    public static void writeCompactBytes(ByteBuf buf, byte[] value) {
        if (value == null) {
            writeUnsignedVarint(buf, 0);
        } else {
            writeUnsignedVarint(buf, value.length + 1);
            buf.writeBytes(value);
        }
    }

    // ===== Compact array (KIP-482 flexible versions) =====

    /**
     * Read a compact array length (uvarint where 0 = null/empty, N = N-1 elements).
     *
     * @return the number of elements, or {@code -1} if the array is null
     */
    public static int readCompactArrayLength(ByteBuf buf) {
        int length = readUnsignedVarint(buf);
        if (length == 0) return -1;
        return length - 1;
    }

    /**
     * Write a compact array length.
     *
     * @param length the number of elements, or {@code -1} for null array
     */
    public static void writeCompactArrayLength(ByteBuf buf, int length) {
        if (length < 0) {
            writeUnsignedVarint(buf, 0);
        } else {
            writeUnsignedVarint(buf, length + 1);
        }
    }

    // ===== Zig-zag varint (RecordBatch internals, unchanged by KIP-482) =====

    /**
     * Read a zig-zag varint as used inside Kafka RecordBatch records.
     */
    public static int readZigzagVarint(ByteBuf buf) {
        int raw = readUnsignedVarint(buf);
        return com.baafoo.core.util.VarintCodec.zigzagDecode(raw);
    }

    /**
     * Write a zig-zag varint as used inside Kafka RecordBatch records.
     */
    public static void writeZigzagVarint(ByteBuf buf, int value) {
        int zigzag = com.baafoo.core.util.VarintCodec.zigzagEncode(value);
        writeUnsignedVarint(buf, zigzag);
    }

    // ===== UUID (KIP-516, Fetch v10+ topic_id) =====

    /**
     * Read a 16-byte Kafka UUID (used by Fetch v10+ topic_id).
     *
     * @return 16-byte array, or null if all zeros
     */
    public static byte[] readUuid(ByteBuf buf) {
        byte[] uuid = new byte[16];
        buf.readBytes(uuid);
        // Check if all zeros (null UUID)
        boolean allZero = true;
        for (byte b : uuid) {
            if (b != 0) { allZero = false; break; }
        }
        return allZero ? null : uuid;
    }

    /**
     * Write a 16-byte Kafka UUID. Null is written as all zeros.
     */
    public static void writeUuid(ByteBuf buf, byte[] uuid) {
        if (uuid == null) {
            buf.writeZero(16);
        } else {
            if (uuid.length != 16) {
                throw new IllegalArgumentException("UUID must be 16 bytes, got " + uuid.length);
            }
            buf.writeBytes(uuid);
        }
    }
}
