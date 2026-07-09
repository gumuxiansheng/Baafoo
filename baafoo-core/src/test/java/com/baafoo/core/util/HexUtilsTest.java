package com.baafoo.core.util;

import org.junit.Test;

import static org.junit.Assert.*;

public class HexUtilsTest {

    @Test
    public void testBytesToHexNull() {
        assertEquals("", HexUtils.bytesToHex(null));
    }

    @Test
    public void testBytesToHexEmpty() {
        assertEquals("", HexUtils.bytesToHex(new byte[0]));
    }

    @Test
    public void testBytesToHexSingleByte() {
        assertEquals("00", HexUtils.bytesToHex(new byte[]{0x00}));
        assertEquals("ff", HexUtils.bytesToHex(new byte[]{(byte) 0xFF}));
        assertEquals("7f", HexUtils.bytesToHex(new byte[]{0x7F}));
    }

    @Test
    public void testBytesToHexMultipleBytes() {
        assertEquals("cafebabe", HexUtils.bytesToHex(new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE}));
    }

    @Test
    public void testBytesToHexLookupTableCoversFullRange() {
        for (int i = 0; i < 256; i++) {
            byte[] single = new byte[]{(byte) i};
            String hex = HexUtils.bytesToHex(single);
            assertEquals(2, hex.length());
            assertEquals(String.format("%02x", i), hex);
        }
    }

    @Test
    public void testByteToHexInt() {
        assertEquals("00", HexUtils.byteToHex(0));
        assertEquals("ff", HexUtils.byteToHex(255));
        assertEquals("0a", HexUtils.byteToHex(10));
    }

    @Test
    public void testByteToHexByte() {
        assertEquals("00", HexUtils.byteToHex((byte) 0));
        assertEquals("ff", HexUtils.byteToHex((byte) 0xFF));
        assertEquals("0a", HexUtils.byteToHex((byte) 0x0A));
    }

    @Test
    public void testBytesToHexWithSeparatorNull() {
        assertEquals("", HexUtils.bytesToHex(null, ":"));
    }

    @Test
    public void testBytesToHexWithSeparatorEmpty() {
        assertEquals("", HexUtils.bytesToHex(new byte[0], ":"));
    }

    @Test
    public void testBytesToHexWithSeparatorNullSep() {
        assertEquals("cafe", HexUtils.bytesToHex(new byte[]{(byte) 0xCA, (byte) 0xFE}, null));
    }

    @Test
    public void testBytesToHexWithSeparatorEmptySep() {
        assertEquals("cafe", HexUtils.bytesToHex(new byte[]{(byte) 0xCA, (byte) 0xFE}, ""));
    }

    @Test
    public void testBytesToHexWithColon() {
        assertEquals("ca:fe:ba:be", HexUtils.bytesToHex(new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE}, ":"));
    }

    @Test
    public void testBytesToHexWithSpace() {
        assertEquals("ca fe", HexUtils.bytesToHex(new byte[]{(byte) 0xCA, (byte) 0xFE}, " "));
    }

    @Test
    public void testAppendHex() {
        StringBuilder sb = new StringBuilder();
        HexUtils.appendHex(sb, new byte[]{(byte) 0xCA, (byte) 0xFE});
        assertEquals("cafe", sb.toString());
    }

    @Test
    public void testAppendHexNull() {
        StringBuilder sb = new StringBuilder("existing");
        HexUtils.appendHex(sb, null);
        assertEquals("existing", sb.toString());
    }

    @Test
    public void testAppendHexEmpty() {
        StringBuilder sb = new StringBuilder();
        HexUtils.appendHex(sb, new byte[0]);
        assertEquals("", sb.toString());
    }

    @Test
    public void testAppendByte() {
        StringBuilder sb = new StringBuilder();
        HexUtils.appendByte(sb, 0);
        assertEquals("00", sb.toString());
    }

    @Test
    public void testAppendByteMax() {
        StringBuilder sb = new StringBuilder();
        HexUtils.appendByte(sb, 255);
        assertEquals("ff", sb.toString());
    }

    @Test
    public void testAppendByteZero() {
        StringBuilder sb = new StringBuilder();
        HexUtils.appendByte(sb, 0);
        assertEquals("00", sb.toString());
    }
}
