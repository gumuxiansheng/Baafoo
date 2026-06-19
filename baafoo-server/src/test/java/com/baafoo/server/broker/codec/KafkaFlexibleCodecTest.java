package com.baafoo.server.broker.codec;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link KafkaFlexibleCodec}.
 *
 * <p>Verifies compact string, compact bytes, unsigned varint, and UUID
 * encoding/decoding against the KIP-482 wire format specification.
 */
public class KafkaFlexibleCodecTest {

    // ===== Unsigned varint =====

    @Test
    public void testUnsignedVarintRoundTrip() {
        int[] values = {0, 1, 127, 128, 255, 16383, 16384, 0x0FFFFFFF};
        for (int v : values) {
            ByteBuf buf = Unpooled.buffer();
            KafkaFlexibleCodec.writeUnsignedVarint(buf, v);
            int read = KafkaFlexibleCodec.readUnsignedVarint(buf);
            assertEquals("Round-trip uvarint " + v, v, read);
            assertEquals("Buffer fully consumed", 0, buf.readableBytes());
            buf.release();
        }
    }

    @Test
    public void testUnsignedVarintSize() {
        assertEquals(1, KafkaFlexibleCodec.unsignedVarintSize(0));
        assertEquals(1, KafkaFlexibleCodec.unsignedVarintSize(127));
        assertEquals(2, KafkaFlexibleCodec.unsignedVarintSize(128));
        assertEquals(2, KafkaFlexibleCodec.unsignedVarintSize(16383));
        assertEquals(3, KafkaFlexibleCodec.unsignedVarintSize(16384));
    }

    @Test
    public void testUnsignedVarintKnownEncoding() {
        // 0 → 0x00
        ByteBuf buf = Unpooled.buffer();
        KafkaFlexibleCodec.writeUnsignedVarint(buf, 0);
        assertEquals(0x00, buf.readByte() & 0xFF);

        // 1 → 0x01
        buf.clear();
        KafkaFlexibleCodec.writeUnsignedVarint(buf, 1);
        assertEquals(0x01, buf.readByte() & 0xFF);

        // 128 → 0x80 0x01
        buf.clear();
        KafkaFlexibleCodec.writeUnsignedVarint(buf, 128);
        assertEquals(0x80, buf.readByte() & 0xFF);
        assertEquals(0x01, buf.readByte() & 0xFF);
        buf.release();
    }

    // ===== Compact string (nullable) =====

    @Test
    public void testCompactStringNull() {
        ByteBuf buf = Unpooled.buffer();
        KafkaFlexibleCodec.writeCompactString(buf, null);
        // null → uvarint(0) = single byte 0x00
        assertEquals(1, buf.readableBytes());
        assertEquals(0x00, buf.readByte() & 0xFF);
        buf.release();
    }

    @Test
    public void testCompactStringNullRoundTrip() {
        ByteBuf buf = Unpooled.buffer();
        KafkaFlexibleCodec.writeCompactString(buf, null);
        String result = KafkaFlexibleCodec.readCompactString(buf);
        assertNull(result);
        buf.release();
    }

    @Test
    public void testCompactStringEmpty() {
        ByteBuf buf = Unpooled.buffer();
        KafkaFlexibleCodec.writeCompactString(buf, "");
        // empty string → uvarint(1) = 0x01, no bytes
        assertEquals(1, buf.readableBytes());
        assertEquals(0x01, buf.readByte() & 0xFF);
        buf.release();
    }

    @Test
    public void testCompactStringEmptyRoundTrip() {
        ByteBuf buf = Unpooled.buffer();
        KafkaFlexibleCodec.writeCompactString(buf, "");
        String result = KafkaFlexibleCodec.readCompactString(buf);
        assertEquals("", result);
        buf.release();
    }

    @Test
    public void testCompactStringNonEmpty() {
        ByteBuf buf = Unpooled.buffer();
        String value = "hello";
        KafkaFlexibleCodec.writeCompactString(buf, value);
        // "hello" (5 bytes) → uvarint(6) = 0x06, then 5 bytes = 6 total
        assertEquals(6, buf.readableBytes());

        // Verify round-trip without consuming bytes first
        String result = KafkaFlexibleCodec.readCompactString(buf);
        assertEquals("hello", result);
        assertEquals(0, buf.readableBytes());
        buf.release();
    }

    @Test
    public void testCompactStringUtf8() {
        ByteBuf buf = Unpooled.buffer();
        String value = "héllo世界";
        KafkaFlexibleCodec.writeCompactString(buf, value);
        String result = KafkaFlexibleCodec.readCompactString(buf);
        assertEquals(value, result);
        buf.release();
    }

    // ===== Compact string (non-nullable) =====

    @Test
    public void testCompactStringNotNullableEmpty() {
        ByteBuf buf = Unpooled.buffer();
        KafkaFlexibleCodec.writeCompactStringNotNullable(buf, "");
        String result = KafkaFlexibleCodec.readCompactStringNotNullable(buf);
        assertEquals("", result);
        buf.release();
    }

    @Test
    public void testCompactStringNotNullableNullBecomesEmpty() {
        ByteBuf buf = Unpooled.buffer();
        KafkaFlexibleCodec.writeCompactStringNotNullable(buf, null);
        String result = KafkaFlexibleCodec.readCompactStringNotNullable(buf);
        assertEquals("", result);
        buf.release();
    }

    // ===== Compact bytes =====

    @Test
    public void testCompactBytesNull() {
        ByteBuf buf = Unpooled.buffer();
        KafkaFlexibleCodec.writeCompactBytes(buf, null);
        assertNull(KafkaFlexibleCodec.readCompactBytes(buf));
        buf.release();
    }

    @Test
    public void testCompactBytesRoundTrip() {
        ByteBuf buf = Unpooled.buffer();
        byte[] data = "test-bytes".getBytes(StandardCharsets.UTF_8);
        KafkaFlexibleCodec.writeCompactBytes(buf, data);
        byte[] result = KafkaFlexibleCodec.readCompactBytes(buf);
        assertArrayEquals(data, result);
        assertEquals(0, buf.readableBytes());
        buf.release();
    }

    // ===== Compact array length =====

    @Test
    public void testCompactArrayLengthNull() {
        ByteBuf buf = Unpooled.buffer();
        KafkaFlexibleCodec.writeCompactArrayLength(buf, -1);
        assertEquals(-1, KafkaFlexibleCodec.readCompactArrayLength(buf));
        buf.release();
    }

    @Test
    public void testCompactArrayLengthZero() {
        ByteBuf buf = Unpooled.buffer();
        KafkaFlexibleCodec.writeCompactArrayLength(buf, 0);
        assertEquals(0, KafkaFlexibleCodec.readCompactArrayLength(buf));
        buf.release();
    }

    @Test
    public void testCompactArrayLengthNonZero() {
        ByteBuf buf = Unpooled.buffer();
        KafkaFlexibleCodec.writeCompactArrayLength(buf, 5);
        assertEquals(5, KafkaFlexibleCodec.readCompactArrayLength(buf));
        buf.release();
    }

    // ===== UUID =====

    @Test
    public void testUuidNull() {
        ByteBuf buf = Unpooled.buffer();
        KafkaFlexibleCodec.writeUuid(buf, null);
        // All zeros = 16 bytes
        assertEquals(16, buf.readableBytes());
        byte[] result = KafkaFlexibleCodec.readUuid(buf);
        assertNull("All-zero UUID should return null", result);
        buf.release();
    }

    @Test
    public void testUuidNonZero() {
        ByteBuf buf = Unpooled.buffer();
        byte[] uuid = new byte[16];
        uuid[0] = 1; // non-zero
        KafkaFlexibleCodec.writeUuid(buf, uuid);
        byte[] result = KafkaFlexibleCodec.readUuid(buf);
        assertArrayEquals(uuid, result);
        buf.release();
    }

    @Test(expected = IllegalArgumentException.class)
    public void testUuidWrongLength() {
        ByteBuf buf = Unpooled.buffer();
        KafkaFlexibleCodec.writeUuid(buf, new byte[10]);
        buf.release();
    }

    // ===== Zig-zag varint =====

    @Test
    public void testZigzagVarintRoundTrip() {
        int[] values = {0, 1, -1, 2, -2, 100, -100, Integer.MAX_VALUE, Integer.MIN_VALUE};
        for (int v : values) {
            ByteBuf buf = Unpooled.buffer();
            KafkaFlexibleCodec.writeZigzagVarint(buf, v);
            int read = KafkaFlexibleCodec.readZigzagVarint(buf);
            assertEquals("Round-trip zigzag " + v, v, read);
            buf.release();
        }
    }
}
