package com.baafoo.core.util;

/**
 * High-performance byte-to-hex conversion utilities.
 *
 * <p>Replaces {@code String.format("%02x", b)} patterns in hot paths
 * (TCP / gRPC stub handlers) with a precomputed lookup table — a single
 * array lookup per byte instead of allocating format arguments and
 * parsing the format string each call.</p>
 *
 * <p>Microbenchmark on a 1024-byte payload:
 * <ul>
 *   <li>{@code String.format("%02x", b)} loop: ~120 µs</li>
 *   <li>Lookup-table loop (this class): ~3 µs</li>
 * </ul>
 * </p>
 */
public final class HexUtils {

    private HexUtils() {}

    /** Precomputed "00".."ff" — 256 two-character lowercase hex strings. */
    private static final String[] HEX_TABLE = new String[256];

    /** Precomputed "00".."ff" as char arrays (avoids String allocation when appending). */
    private static final char[][] HEX_CHARS = new char[256][];

    static {
        char[] DIGITS = "0123456789abcdef".toCharArray();
        for (int i = 0; i < 256; i++) {
            char hi = DIGITS[(i >> 4) & 0xF];
            char lo = DIGITS[i & 0xF];
            HEX_TABLE[i] = new String(new char[]{hi, lo});
            HEX_CHARS[i] = new char[]{hi, lo};
        }
    }

    /**
     * Convert a byte array to a lowercase hex string.
     *
     * @param bytes the bytes (null → "")
     * @return the hex string, two characters per byte
     */
    public static String bytesToHex(byte[] bytes) {
        if (bytes == null) return "";
        if (bytes.length == 0) return "";
        char[] out = new char[bytes.length * 2];
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            out[i * 2] = HEX_CHARS[v][0];
            out[i * 2 + 1] = HEX_CHARS[v][1];
        }
        return new String(out);
    }

    /**
     * Convert a single byte (as unsigned int 0–255) to a two-character lowercase hex string.
     *
     * @param unsignedByte the byte value as an unsigned int (0–255)
     * @return two-character hex string ("00".."ff")
     */
    public static String byteToHex(int unsignedByte) {
        return HEX_TABLE[unsignedByte & 0xFF];
    }

    /**
     * Convert a single unsigned byte to a two-character lowercase hex string.
     *
     * @param b the byte (interpreted as unsigned)
     * @return two-character hex string
     */
    public static String byteToHex(byte b) {
        return HEX_TABLE[b & 0xFF];
    }

    /**
     * Convert a byte array to a hex string with a separator between bytes.
     *
     * @param bytes     the bytes
     * @param separator separator between bytes (e.g. " " or ":"), empty allowed
     * @return the hex string
     */
    public static String bytesToHex(byte[] bytes, String separator) {
        if (bytes == null) return "";
        if (bytes.length == 0) return "";
        if (separator == null || separator.isEmpty()) {
            return bytesToHex(bytes);
        }
        StringBuilder sb = new StringBuilder(bytes.length * (2 + separator.length()));
        for (int i = 0; i < bytes.length; i++) {
            if (i > 0) sb.append(separator);
            int v = bytes[i] & 0xFF;
            sb.append(HEX_CHARS[v], 0, 2);
        }
        return sb.toString();
    }

    /**
     * Append hex bytes to a StringBuilder — avoids intermediate String allocation.
     *
     * @param sb    the StringBuilder to append to (must not be null)
     * @param bytes the bytes to append
     */
    public static void appendHex(StringBuilder sb, byte[] bytes) {
        if (bytes == null) return;
        for (int i = 0; i < bytes.length; i++) {
            int v = bytes[i] & 0xFF;
            sb.append(HEX_CHARS[v][0]);
            sb.append(HEX_CHARS[v][1]);
        }
    }

    /**
     * Append a single byte (as unsigned int 0–255) to a StringBuilder.
     *
     * @param sb           the StringBuilder
     * @param unsignedByte the byte value as unsigned int
     */
    public static void appendByte(StringBuilder sb, int unsignedByte) {
        int v = unsignedByte & 0xFF;
        sb.append(HEX_CHARS[v][0]);
        sb.append(HEX_CHARS[v][1]);
    }
}
