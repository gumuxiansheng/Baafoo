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
     */
    public static String seq(String prefix) {
        return prefix + "-" + SEQ.incrementAndGet();
    }

    /**
     * Generate a timestamp-based ID.
     */
    public static String timestamp() {
        return String.valueOf(System.currentTimeMillis());
    }
}
