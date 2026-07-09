package com.baafoo.core.util;

import org.junit.Test;

import static org.junit.Assert.*;

public class VarintCodecTest {

    // ==================== readVarint (cursor) ====================

    @Test
    public void testReadVarintSingleByte() {
        // 0x00 = 0
        byte[] data = {0x00};
        int[] pos = {0};
        assertEquals(0, VarintCodec.readVarint(data, pos));
        assertEquals(1, pos[0]);
    }

    @Test
    public void testReadVarintSingleByteNonZero() {
        // 0x7F = 127
        byte[] data = {0x7F};
        int[] pos = {0};
        assertEquals(127, VarintCodec.readVarint(data, pos));
        assertEquals(1, pos[0]);
    }

    @Test
    public void testReadVarintMultiByte() {
        // 300 = 0x12C -> 0xAC 0x02
        byte[] data = {(byte) 0xAC, 0x02};
        int[] pos = {0};
        assertEquals(300, VarintCodec.readVarint(data, pos));
        assertEquals(2, pos[0]);
    }

    @Test
    public void testReadVarintExhausted() {
        byte[] data = new byte[0];
        int[] pos = {0};
        assertEquals(-1, VarintCodec.readVarint(data, pos));
    }

    @Test
    public void testReadVarintAdvancesCursor() {
        // Two varints: 300 and 127
        byte[] data = {(byte) 0xAC, 0x02, 0x7F};
        int[] pos = {0};
        assertEquals(300, VarintCodec.readVarint(data, pos));
        assertEquals(127, VarintCodec.readVarint(data, pos));
    }

    // ==================== readVarint (offset) ====================

    @Test
    public void testReadVarintOffsetSingleByte() {
        byte[] data = {0x00};
        assertEquals(0, VarintCodec.readVarint(data, 0));
    }

    @Test
    public void testReadVarintOffsetMultiByte() {
        byte[] data = {(byte) 0xAC, 0x02};
        assertEquals(300, VarintCodec.readVarint(data, 0));
    }

    @Test
    public void testReadVarintOffsetExhausted() {
        byte[] data = new byte[0];
        assertEquals(-1, VarintCodec.readVarint(data, 0));
    }

    // ==================== readVarint64 ====================

    @Test
    public void testReadVarint64SingleByte() {
        byte[] data = {0x00};
        int[] pos = {0};
        assertEquals(0L, VarintCodec.readVarint64(data, pos));
    }

    @Test
    public void testReadVarint64LargeValue() {
        // 300 = 0x12C -> 0xAC 0x02
        byte[] data = {(byte) 0xAC, 0x02};
        int[] pos = {0};
        assertEquals(300L, VarintCodec.readVarint64(data, pos));
    }

    @Test
    public void testReadVarint64Exhausted() {
        byte[] data = new byte[0];
        int[] pos = {0};
        assertEquals(0L, VarintCodec.readVarint64(data, pos));
    }

    // ==================== varintSize ====================

    @Test
    public void testVarintSizeZero() {
        assertEquals(1, VarintCodec.varintSize(0));
    }

    @Test
    public void testVarintSize127() {
        assertEquals(1, VarintCodec.varintSize(127));
    }

    @Test
    public void testVarintSize128() {
        assertEquals(2, VarintCodec.varintSize(128));
    }

    @Test
    public void testVarintSize300() {
        assertEquals(2, VarintCodec.varintSize(300));
    }

    @Test
    public void testVarintSizeMaxInt() {
        assertEquals(5, VarintCodec.varintSize(Integer.MAX_VALUE));
    }

    // ==================== zigzagEncode / zigzagDecode ====================

    @Test
    public void testZigzagEncodeDecodeZero() {
        assertEquals(0, VarintCodec.zigzagDecode(VarintCodec.zigzagEncode(0)));
    }

    @Test
    public void testZigzagEncodeDecodeOne() {
        assertEquals(1, VarintCodec.zigzagDecode(VarintCodec.zigzagEncode(1)));
    }

    @Test
    public void testZigzagEncodeDecodeNegativeOne() {
        assertEquals(-1, VarintCodec.zigzagDecode(VarintCodec.zigzagEncode(-1)));
    }

    @Test
    public void testZigzagEncodeDecodeLargePositive() {
        assertEquals(Integer.MAX_VALUE, VarintCodec.zigzagDecode(VarintCodec.zigzagEncode(Integer.MAX_VALUE)));
    }

    @Test
    public void testZigzagEncodeDecodeLargeNegative() {
        assertEquals(Integer.MIN_VALUE, VarintCodec.zigzagDecode(VarintCodec.zigzagEncode(Integer.MIN_VALUE)));
    }

    @Test
    public void testZigzagEncodeValues() {
        assertEquals(0, VarintCodec.zigzagEncode(0));
        assertEquals(1, VarintCodec.zigzagEncode(-1));
        assertEquals(2, VarintCodec.zigzagEncode(1));
        assertEquals(3, VarintCodec.zigzagEncode(-2));
        assertEquals(4, VarintCodec.zigzagEncode(2));
    }

    @Test
    public void testZigzagDecodeValues() {
        assertEquals(0, VarintCodec.zigzagDecode(0));
        assertEquals(-1, VarintCodec.zigzagDecode(1));
        assertEquals(1, VarintCodec.zigzagDecode(2));
        assertEquals(-2, VarintCodec.zigzagDecode(3));
        assertEquals(2, VarintCodec.zigzagDecode(4));
    }

    // ==================== zigzagVarintSize ====================

    @Test
    public void testZigzagVarintSizeZero() {
        assertEquals(1, VarintCodec.zigzagVarintSize(0));
    }

    @Test
    public void testZigzagVarintSizeNegativeOne() {
        assertEquals(1, VarintCodec.zigzagVarintSize(-1));
    }

    @Test
    public void testZigzagVarintSizeLargePositive() {
        // zigzagEncode(MAX_VALUE) = MAX_VALUE << 1 ^ (MAX_VALUE >> 31) = a 4-byte varint
        int encoded = VarintCodec.zigzagEncode(Integer.MAX_VALUE);
        assertEquals(VarintCodec.varintSize(encoded), VarintCodec.zigzagVarintSize(Integer.MAX_VALUE));
    }
}
