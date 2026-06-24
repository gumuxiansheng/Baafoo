package com.baafoo.core.util;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * gRPC codec utility methods.
 *
 * <p>Provides shared encoding/decoding for gRPC message frames:
 * <pre>
 * +----------------+------------------------+-----------+
 * | Compressed(1B) | Length(4B big-endian)  | Message(N)|
 * +----------------+------------------------+-----------+
 * </pre>
 * </p>
 *
 * <p>Also provides hex conversion and gRPC path parsing utilities
 * used by both {@code GrpcUnifiedHandler} and {@code MatchEngine}.</p>
 */
public final class GrpcCodecUtils {

    private GrpcCodecUtils() {}

    // ==================== Frame Building / Parsing ====================

    /**
     * Build a gRPC message frame from raw message bytes.
     *
     * @param message the protobuf message bytes
     * @return gRPC frame (5-byte header + message)
     */
    public static byte[] buildGrpcFrame(byte[] message) {
        byte[] frame = new byte[5 + message.length];
        frame[0] = 0; // no compression
        frame[1] = (byte) ((message.length >> 24) & 0xFF);
        frame[2] = (byte) ((message.length >> 16) & 0xFF);
        frame[3] = (byte) ((message.length >> 8) & 0xFF);
        frame[4] = (byte) (message.length & 0xFF);
        System.arraycopy(message, 0, frame, 5, message.length);
        return frame;
    }

    /**
     * Parse a single gRPC frame and extract the message bytes.
     *
     * @param frame the full gRPC frame bytes (at least 5 + length bytes)
     * @return the message bytes, or null if the frame is too short
     */
    public static byte[] parseGrpcFrame(byte[] frame) {
        if (frame == null || frame.length < 5) return null;
        int length = ((frame[1] & 0xFF) << 24)
                | ((frame[2] & 0xFF) << 16)
                | ((frame[3] & 0xFF) << 8)
                | (frame[4] & 0xFF);
        if (length < 0 || frame.length < 5 + length) return null;
        byte[] message = new byte[length];
        System.arraycopy(frame, 5, message, 0, length);
        return message;
    }

    /**
     * Parse all complete gRPC frames from a byte array.
     *
     * @param data raw bytes containing one or more gRPC frames
     * @return list of message bytes (one per frame); incomplete trailing frames are skipped
     */
    public static List<byte[]> parseGrpcFrames(byte[] data) {
        List<byte[]> messages = new ArrayList<>();
        if (data == null || data.length < 5) return messages;
        int offset = 0;
        while (offset + 5 <= data.length) {
            // Skip compressed flag (1 byte), read length (4 bytes big-endian)
            int length = ((data[offset + 1] & 0xFF) << 24)
                    | ((data[offset + 2] & 0xFF) << 16)
                    | ((data[offset + 3] & 0xFF) << 8)
                    | (data[offset + 4] & 0xFF);
            if (length < 0 || offset + 5 + length > data.length) break;
            byte[] message = new byte[length];
            System.arraycopy(data, offset + 5, message, 0, length);
            messages.add(message);
            offset += 5 + length;
        }
        return messages;
    }

    // ==================== Hex Conversion ====================

    public static String bytesToHex(byte[] bytes) {
        if (bytes == null) return "";
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    public static byte[] hexToBytes(String hex) {
        if (hex == null || hex.isEmpty()) return new byte[0];
        String clean = hex.replaceAll("\\s", "");
        if (clean.length() % 2 != 0) {
            clean = "0" + clean;
        }
        byte[] result = new byte[clean.length() / 2];
        for (int i = 0; i < result.length; i++) {
            int high = Character.digit(clean.charAt(i * 2), 16);
            int low = Character.digit(clean.charAt(i * 2 + 1), 16);
            result[i] = (byte) ((high << 4) | low);
        }
        return result;
    }

    public static boolean isHexString(String s) {
        if (s == null || s.isEmpty()) return false;
        if (s.length() % 2 != 0) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
                return false;
            }
        }
        return true;
    }

    // ==================== gRPC Path Parsing ====================

    /**
     * Extract the gRPC service name from a gRPC HTTP/2 path.
     *
     * <p>gRPC path format: {@code /package.Service/Method}
     * <ul>
     *   <li>{@code /helloworld.Greeter/SayHello} -> "helloworld.Greeter"</li>
     *   <li>{@code /Greeter/SayHello} -> "Greeter"</li>
     * </ul>
     *
     * @param path the HTTP path from the gRPC request
     * @return the service name, or null if the path is not a valid gRPC path
     */
    public static String extractGrpcService(String path) {
        if (path == null || path.isEmpty()) return null;
        if (!path.startsWith("/")) return null;
        int secondSlash = path.indexOf('/', 1);
        if (secondSlash < 0) return null;
        String service = path.substring(1, secondSlash);
        return service.isEmpty() ? null : service;
    }

    /**
     * Extract the gRPC method name from a gRPC HTTP/2 path.
     *
     * <p>gRPC path format: {@code /package.Service/Method}
     * <ul>
     *   <li>{@code /helloworld.Greeter/SayHello} -> "SayHello"</li>
     * </ul>
     *
     * @param path the HTTP path from the gRPC request
     * @return the method name, or null if the path is not a valid gRPC path
     */
    public static String extractGrpcMethod(String path) {
        if (path == null || path.isEmpty()) return null;
        if (!path.startsWith("/")) return null;
        int secondSlash = path.indexOf('/', 1);
        if (secondSlash < 0 || secondSlash == path.length() - 1) return null;
        String method = path.substring(secondSlash + 1);
        return method.isEmpty() ? null : method;
    }

    // ==================== Response Body Parsing ====================

    /**
     * Convert a response body string to bytes.
     *
     * <p>Supports three formats:
     * <ul>
     *   <li>{@code 0x...} prefix - hex string</li>
     *   <li>Even-length hex string - hex bytes</li>
     *   <li>Otherwise - UTF-8 text</li>
     * </ul>
     *
     * @param body the response body string
     * @return the decoded bytes
     */
    public static byte[] responseBodyToBytes(String body) {
        if (body == null || body.isEmpty()) return new byte[0];
        if (body.startsWith("0x") || body.startsWith("0X")) {
            return hexToBytes(body.substring(2));
        }
        if (isHexString(body)) {
            return hexToBytes(body);
        }
        return body.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Split a response body string into multiple messages for streaming.
     *
     * <p>Splitting rules:
     * <ul>
     *   <li>Comma-separated: "msg1,msg2,msg3" -> 3 messages</li>
     *   <li>Newline-separated: "msg1\nmsg2\nmsg3" -> 3 messages</li>
     *   <li>Single value: "msg1" -> 1 message</li>
     * </ul>
     *
     * @param body the response body string
     * @return array of individual message strings (trimmed)
     */
    public static String[] splitStreamingMessages(String body) {
        if (body == null || body.isEmpty()) return new String[]{""};
        if (body.contains(",")) {
            return body.split(",");
        }
        if (body.contains("\n")) {
            return body.split("\n");
        }
        return new String[]{body};
    }
}
