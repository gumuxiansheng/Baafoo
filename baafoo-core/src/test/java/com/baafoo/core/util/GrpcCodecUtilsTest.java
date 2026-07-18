package com.baafoo.core.util;

import org.junit.Test;

import java.util.List;

import static org.junit.Assert.*;

public class GrpcCodecUtilsTest {

    // ==================== buildGrpcFrame ====================

    @Test
    public void testBuildGrpcFrameEmptyMessage() {
        byte[] frame = GrpcCodecUtils.buildGrpcFrame(new byte[0]);
        assertEquals(5, frame.length);
        assertEquals(0, frame[0]);
        // Length = 0 (big-endian bytes 1-4)
        for (int i = 1; i <= 4; i++) {
            assertEquals(0, frame[i]);
        }
    }

    @Test
    public void testBuildGrpcFrameSmallMessage() {
        byte[] message = {0x01, 0x02, 0x03};
        byte[] frame = GrpcCodecUtils.buildGrpcFrame(message);
        assertEquals(5 + 3, frame.length);
        assertEquals(0, frame[0]); // no compression
        // length = 3 in big-endian: 00 00 00 03
        assertEquals(0, frame[1] & 0xFF);
        assertEquals(0, frame[2] & 0xFF);
        assertEquals(0, frame[3] & 0xFF);
        assertEquals(3, frame[4] & 0xFF);
        assertEquals(0x01, frame[5] & 0xFF);
        assertEquals(0x02, frame[6] & 0xFF);
        assertEquals(0x03, frame[7] & 0xFF);
    }

    @Test
    public void testBuildGrpcFrameLargeMessage() {
        byte[] message = new byte[256];
        for (int i = 0; i < 256; i++) message[i] = (byte) i;
        byte[] frame = GrpcCodecUtils.buildGrpcFrame(message);
        assertEquals(5 + 256, frame.length);
        // length = 256 in big-endian: 00 00 01 00
        assertEquals(0, frame[1] & 0xFF);
        assertEquals(0, frame[2] & 0xFF);
        assertEquals(1, frame[3] & 0xFF);
        assertEquals(0, frame[4] & 0xFF);
    }

    // ==================== parseGrpcFrame ====================

    @Test
    public void testParseGrpcFrameNull() {
        assertNull(GrpcCodecUtils.parseGrpcFrame(null));
    }

    @Test
    public void testParseGrpcFrameTooShort() {
        assertNull(GrpcCodecUtils.parseGrpcFrame(new byte[4]));
    }

    @Test
    public void testParseGrpcFrameValid() {
        byte[] message = {0x0A, 0x0B, 0x0C, 0x0D};
        byte[] frame = GrpcCodecUtils.buildGrpcFrame(message);
        byte[] parsed = GrpcCodecUtils.parseGrpcFrame(frame);
        assertNotNull(parsed);
        assertArrayEquals(message, parsed);
    }

    @Test
    public void testParseGrpcFrameLengthMismatch() {
        byte[] frame = new byte[5];
        frame[1] = 0x00;
        frame[2] = 0x00;
        frame[3] = 0x00;
        frame[4] = 0x05; // claims 5 bytes, but frame is only 5 total
        assertNull(GrpcCodecUtils.parseGrpcFrame(frame));
    }

    @Test
    public void testParseGrpcFrameNegativeLength() {
        byte[] frame = new byte[5];
        frame[1] = (byte) 0xFF;
        frame[2] = (byte) 0xFF;
        frame[3] = (byte) 0xFF;
        frame[4] = (byte) 0xFF; // very large length that wraps negative
        assertNull(GrpcCodecUtils.parseGrpcFrame(frame));
    }

    @Test
    public void testParseGrpcFrameEmptyMessage() {
        byte[] frame = GrpcCodecUtils.buildGrpcFrame(new byte[0]);
        byte[] parsed = GrpcCodecUtils.parseGrpcFrame(frame);
        assertNotNull(parsed);
        assertEquals(0, parsed.length);
    }

    // ==================== parseGrpcFrames ====================

    @Test
    public void testParseGrpcFramesNull() {
        List<byte[]> result = GrpcCodecUtils.parseGrpcFrames(null);
        assertTrue(result.isEmpty());
    }

    @Test
    public void testParseGrpcFramesTooShort() {
        List<byte[]> result = GrpcCodecUtils.parseGrpcFrames(new byte[3]);
        assertTrue(result.isEmpty());
    }

    @Test
    public void testParseGrpcFramesSingleFrame() {
        byte[] msg1 = {0x01, 0x02};
        byte[] data = GrpcCodecUtils.buildGrpcFrame(msg1);
        List<byte[]> result = GrpcCodecUtils.parseGrpcFrames(data);
        assertEquals(1, result.size());
        assertArrayEquals(msg1, result.get(0));
    }

    @Test
    public void testParseGrpcFramesMultipleFrames() {
        byte[] msg1 = {0x01, 0x02};
        byte[] msg2 = {0x03, 0x04, 0x05};
        byte[] frame1 = GrpcCodecUtils.buildGrpcFrame(msg1);
        byte[] frame2 = GrpcCodecUtils.buildGrpcFrame(msg2);

        byte[] combined = new byte[frame1.length + frame2.length];
        System.arraycopy(frame1, 0, combined, 0, frame1.length);
        System.arraycopy(frame2, 0, combined, frame1.length, frame2.length);

        List<byte[]> result = GrpcCodecUtils.parseGrpcFrames(combined);
        assertEquals(2, result.size());
        assertArrayEquals(msg1, result.get(0));
        assertArrayEquals(msg2, result.get(1));
    }

    @Test
    public void testParseGrpcFramesIncompleteTrailingFrameSkipped() {
        byte[] msg1 = {0x01};
        byte[] frame1 = GrpcCodecUtils.buildGrpcFrame(msg1);
        byte[] combined = new byte[frame1.length + 3];
        System.arraycopy(frame1, 0, combined, 0, frame1.length);
        // trailing 3 bytes (incomplete header)

        List<byte[]> result = GrpcCodecUtils.parseGrpcFrames(combined);
        assertEquals(1, result.size());
        assertArrayEquals(msg1, result.get(0));
    }

    // ==================== bytesToHex / hexToBytes ====================

    @Test
    public void testBytesToHexNull() {
        assertEquals("", GrpcCodecUtils.bytesToHex(null));
    }

    @Test
    public void testBytesToHexEmpty() {
        assertEquals("", GrpcCodecUtils.bytesToHex(new byte[0]));
    }

    @Test
    public void testBytesToHexKnownBytes() {
        byte[] bytes = {(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE};
        assertEquals("cafebabe", GrpcCodecUtils.bytesToHex(bytes));
    }

    @Test
    public void testHexToBytesNull() {
        assertArrayEquals(new byte[0], GrpcCodecUtils.hexToBytes(null));
    }

    @Test
    public void testHexToBytesEmpty() {
        assertArrayEquals(new byte[0], GrpcCodecUtils.hexToBytes(""));
    }

    @Test
    public void testHexToBytesValid() {
        byte[] result = GrpcCodecUtils.hexToBytes("cafebabe");
        assertArrayEquals(new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE}, result);
    }

    @Test
    public void testHexToBytesOddLength() {
        byte[] result = GrpcCodecUtils.hexToBytes("f");
        assertArrayEquals(new byte[]{(byte) 0x0F}, result);
    }

    @Test
    public void testHexToBytesWithSpaces() {
        byte[] result = GrpcCodecUtils.hexToBytes("ca fe be");
        assertArrayEquals(new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBE}, result);
    }

    @Test
    public void testBytesHexRoundTrip() {
        byte[] original = {0x00, 0x01, 0x7F, (byte) 0x80, (byte) 0xFF};
        String hex = GrpcCodecUtils.bytesToHex(original);
        byte[] decoded = GrpcCodecUtils.hexToBytes(hex);
        assertArrayEquals(original, decoded);
    }

    // ==================== isHexString ====================

    @Test
    public void testIsHexStringNull() {
        assertFalse(GrpcCodecUtils.isHexString(null));
    }

    @Test
    public void testIsHexStringEmpty() {
        assertFalse(GrpcCodecUtils.isHexString(""));
    }

    @Test
    public void testIsHexStringOddLength() {
        // M3 fix: isHexString now accepts odd lengths (consistent with hexToBytes
        // which left-pads with '0'). "abc" is valid hex → 0x0A 0xBC.
        assertTrue(GrpcCodecUtils.isHexString("abc"));
    }

    @Test
    public void testIsHexStringValid() {
        assertTrue(GrpcCodecUtils.isHexString("cafebabe"));
        assertTrue(GrpcCodecUtils.isHexString("CAFE"));
        assertTrue(GrpcCodecUtils.isHexString("0123456789abcdef"));
    }

    @Test
    public void testIsHexStringInvalidChars() {
        assertFalse(GrpcCodecUtils.isHexString("xyz123"));
        assertFalse(GrpcCodecUtils.isHexString("cafe g"));
    }

    // ==================== extractGrpcService ====================

    @Test
    public void testExtractGrpcServiceNull() {
        assertNull(GrpcCodecUtils.extractGrpcService(null));
    }

    @Test
    public void testExtractGrpcServiceEmpty() {
        assertNull(GrpcCodecUtils.extractGrpcService(""));
    }

    @Test
    public void testExtractGrpcServiceNoSlash() {
        assertNull(GrpcCodecUtils.extractGrpcService("no-slash"));
    }

    @Test
    public void testExtractGrpcServiceFullyQualified() {
        assertEquals("helloworld.Greeter", GrpcCodecUtils.extractGrpcService("/helloworld.Greeter/SayHello"));
    }

    @Test
    public void testExtractGrpcServiceSimple() {
        assertEquals("Greeter", GrpcCodecUtils.extractGrpcService("/Greeter/SayHello"));
    }

    @Test
    public void testExtractGrpcServiceEmptyServiceName() {
        assertNull(GrpcCodecUtils.extractGrpcService("//SayHello"));
    }

    // ==================== extractGrpcMethod ====================

    @Test
    public void testExtractGrpcMethodNull() {
        assertNull(GrpcCodecUtils.extractGrpcMethod(null));
    }

    @Test
    public void testExtractGrpcMethodEmpty() {
        assertNull(GrpcCodecUtils.extractGrpcMethod(""));
    }

    @Test
    public void testExtractGrpcMethodNoSlash() {
        assertNull(GrpcCodecUtils.extractGrpcMethod("no-slash"));
    }

    @Test
    public void testExtractGrpcMethodFullyQualified() {
        assertEquals("SayHello", GrpcCodecUtils.extractGrpcMethod("/helloworld.Greeter/SayHello"));
    }

    @Test
    public void testExtractGrpcMethodTrailingSlash() {
        assertNull(GrpcCodecUtils.extractGrpcMethod("/Greeter/"));
    }

    @Test
    public void testExtractGrpcMethodSingleSlash() {
        assertNull(GrpcCodecUtils.extractGrpcMethod("/Greeter"));
    }

    // ==================== responseBodyToBytes ====================

    @Test
    public void testResponseBodyToBytesNull() {
        assertArrayEquals(new byte[0], GrpcCodecUtils.responseBodyToBytes(null));
    }

    @Test
    public void testResponseBodyToBytesEmpty() {
        assertArrayEquals(new byte[0], GrpcCodecUtils.responseBodyToBytes(""));
    }

    @Test
    public void testResponseBodyToBytesHexWithPrefix() {
        byte[] result = GrpcCodecUtils.responseBodyToBytes("0xdeadbeef");
        assertArrayEquals(new byte[]{(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF}, result);
    }

    @Test
    public void testResponseBodyToBytesHexWithUpperPrefix() {
        byte[] result = GrpcCodecUtils.responseBodyToBytes("0XDEAD");
        assertArrayEquals(new byte[]{(byte) 0xDE, (byte) 0xAD}, result);
    }

    @Test
    public void testResponseBodyToBytesPlainHex() {
        byte[] result = GrpcCodecUtils.responseBodyToBytes("cafebabe");
        assertArrayEquals(new byte[]{(byte) 0xCA, (byte) 0xFE, (byte) 0xBA, (byte) 0xBE}, result);
    }

    @Test
    public void testResponseBodyToBytesUtf8Text() {
        byte[] result = GrpcCodecUtils.responseBodyToBytes("hello");
        assertArrayEquals("hello".getBytes(java.nio.charset.StandardCharsets.UTF_8), result);
    }

    // ==================== splitStreamingMessages ====================

    @Test
    public void testSplitStreamingMessagesNull() {
        String[] result = GrpcCodecUtils.splitStreamingMessages(null);
        assertEquals(1, result.length);
        assertEquals("", result[0]);
    }

    @Test
    public void testSplitStreamingMessagesEmpty() {
        String[] result = GrpcCodecUtils.splitStreamingMessages("");
        assertEquals(1, result.length);
        assertEquals("", result[0]);
    }

    @Test
    public void testSplitStreamingMessagesSingleValue() {
        String[] result = GrpcCodecUtils.splitStreamingMessages("hello");
        assertEquals(1, result.length);
        assertEquals("hello", result[0]);
    }

    @Test
    public void testSplitStreamingMessagesCommaSeparated() {
        String[] result = GrpcCodecUtils.splitStreamingMessages("a,b,c");
        assertEquals(3, result.length);
        assertEquals("a", result[0]);
        assertEquals("b", result[1]);
        assertEquals("c", result[2]);
    }

    @Test
    public void testSplitStreamingMessagesNewlineSeparated() {
        String[] result = GrpcCodecUtils.splitStreamingMessages("a\nb\nc");
        assertEquals(3, result.length);
        assertEquals("a", result[0]);
        assertEquals("b", result[1]);
        assertEquals("c", result[2]);
    }

    @Test
    public void testSplitStreamingMessagesCommaTakesPriorityOverNewline() {
        String[] result = GrpcCodecUtils.splitStreamingMessages("a,b\n");
        assertEquals(2, result.length);
        assertEquals("a", result[0]);
        assertEquals("b\n", result[1]);
    }
}
