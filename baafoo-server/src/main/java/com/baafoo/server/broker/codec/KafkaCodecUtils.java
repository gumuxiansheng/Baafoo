package com.baafoo.server.broker.codec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;

import java.nio.charset.StandardCharsets;

/**
 * Shared low-level helpers for Kafka wire-protocol codecs.
 *
 * <p>Contains the non-flexible (int16/int32 length prefixed) string and bytes
 * helpers, plus response framing and varint primitives used by both the
 * legacy and the flexible codec paths. All methods are stateless.
 *
 * @see KafkaFlexibleCodec for compact (KIP-482) helpers.
 */
public final class KafkaCodecUtils {

    private KafkaCodecUtils() {
    }

    // ===== Response framing =====

    /**
     * Wrap the response payload in a 4-byte big-endian size frame.
     *
     * @param payload the payload to frame; ownership is transferred and the
     *                buffer is released by this method.
     * @return a new buffer containing the 4-byte length followed by the payload.
     */
    public static ByteBuf frameResponse(ByteBuf payload) {
        ByteBuf frame = Unpooled.buffer(4 + payload.readableBytes());
        frame.writeInt(payload.readableBytes());
        frame.writeBytes(payload);
        payload.release();
        return frame;
    }

    // ===== Non-flexible string helpers =====

    /**
     * Read a Kafka nullable string (int16 length prefix, {@code -1} means null).
     */
    public static String readNullableString(ByteBuf buf) {
        short length = buf.readShort();
        if (length < 0) {
            return null;
        }
        byte[] bytes = new byte[length];
        buf.readBytes(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    /**
     * Write a Kafka nullable string (int16 length prefix, {@code -1} means null).
     */
    public static void writeNullableString(ByteBuf buf, String value) {
        if (value == null) {
            buf.writeShort(-1);
        } else {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            buf.writeShort(bytes.length);
            buf.writeBytes(bytes);
        }
    }

    /**
     * Read Kafka nullable bytes (int32 length prefix, {@code -1} means null).
     */
    public static byte[] readNullableBytes(ByteBuf buf) {
        int length = buf.readInt();
        if (length < 0) {
            return null;
        }
        byte[] bytes = new byte[length];
        buf.readBytes(bytes);
        return bytes;
    }

    /**
     * Write Kafka nullable bytes (int32 length prefix, {@code -1} means null).
     */
    public static void writeNullableBytes(ByteBuf buf, byte[] value) {
        if (value == null) {
            buf.writeInt(-1);
        } else {
            buf.writeInt(value.length);
            buf.writeBytes(value);
        }
    }

    // ===== Zig-zag varint (RecordBatch internals) =====

    /**
     * Write a zig-zag varint as used inside Kafka RecordBatch records.
     */
    public static void writeVarint(ByteBuf buf, int value) {
        int zigzag = com.baafoo.core.util.VarintCodec.zigzagEncode(value);
        while ((zigzag & ~0x7F) != 0) {
            buf.writeByte((byte) ((zigzag & 0x7F) | 0x80));
            zigzag >>>= 7;
        }
        buf.writeByte((byte) zigzag);
    }

    /**
     * Read a zig-zag varint as used inside Kafka RecordBatch records.
     */
    public static int readVarint(ByteBuf buf) {
        int raw = KafkaFlexibleCodec.readUnsignedVarint(buf);
        return com.baafoo.core.util.VarintCodec.zigzagDecode(raw);
    }

    /**
     * Number of bytes the zig-zag varint encoding of {@code value} occupies.
     */
    public static int varintEncodedSize(int value) {
        return com.baafoo.core.util.VarintCodec.zigzagVarintSize(value);
    }

    // ===== CRC32C (Castagnoli) for RecordBatch checksum =====

    /** CRC32C lookup table (Castagnoli polynomial 0x1EDC6F41). */
    private static final int[] CRC32C_TABLE = new int[256];
    static {
        for (int i = 0; i < 256; i++) {
            int crc = i;
            for (int j = 0; j < 8; j++) {
                if ((crc & 1) != 0) {
                    crc = (crc >>> 1) ^ 0x82F63B78;
                } else {
                    crc >>>= 1;
                }
            }
            CRC32C_TABLE[i] = crc;
        }
    }

    /**
     * Compute CRC32C (Castagnoli) over a byte range.
     */
    public static int computeCrc32c(byte[] data, int offset, int length) {
        int crc = 0xFFFFFFFF;
        for (int i = offset; i < offset + length; i++) {
            crc = CRC32C_TABLE[(crc ^ data[i]) & 0xFF] ^ (crc >>> 8);
        }
        return crc ^ 0xFFFFFFFF;
    }
}
