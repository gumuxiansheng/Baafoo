package com.baafoo.core.util;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Simple ID generation utilities.
 */
public final class IdGenerator {

    private static final AtomicLong SEQ = new AtomicLong(System.currentTimeMillis());

    private IdGenerator() {}

    /**
     * Generate a UUID-based ID.
     */
    public static String uuid() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    /**
     * Generate a short sequential ID.
     *
     * @param prefix the prefix to prepend (e.g. "rule"); {@code null} is
     *              treated as the empty string to avoid NPE in concatenation
     * @return a string of the form {@code "<prefix>-<seq>"}
     */
    public static String seq(String prefix) {
        // M9: guard against null prefix so callers passing null (e.g. from
        // unmarshalled JSON with a missing field) don't trigger an NPE inside
        // the implicit StringBuilder.append(String) used for "+".
        return (prefix != null ? prefix : "") + "-" + SEQ.incrementAndGet();
    }

    /**
     * Generate a timestamp-based ID.
     */
    public static String timestamp() {
        return String.valueOf(System.currentTimeMillis());
    }
}
