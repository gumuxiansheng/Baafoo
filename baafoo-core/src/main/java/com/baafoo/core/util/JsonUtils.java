package com.baafoo.core.util;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Shared {@link ObjectMapper} singleton for the entire Baafoo codebase.
 *
 * <p>Jackson's {@code ObjectMapper} is thread-safe after configuration and
 * expensive to construct (builds {@code TypeFactory}, serializer/deserializer
 * caches). Creating one per class/component wastes memory and CPU, and risks
 * inconsistent serialization configuration across the codebase.</p>
 *
 * <p>Usage:</p>
 * <pre>{@code
 * // Instead of: private final ObjectMapper mapper = new ObjectMapper();
 * // Use:        private final ObjectMapper mapper = JsonUtils.MAPPER;
 * // Or static:  private static final ObjectMapper MAPPER = JsonUtils.MAPPER;
 * }</pre>
 *
 * <p>This class lives in {@code baafoo-core} so both {@code baafoo-server}
 * and {@code baafoo-agent} (which both depend on {@code baafoo-core}) can
 * access the shared instance. The {@code baafoo-plugin-api} module does NOT
 * depend on {@code baafoo-core}, so plugins must continue to create their
 * own ObjectMapper instances — this is intentional for plugin isolation.</p>
 */
public final class JsonUtils {

    /** Shared, thread-safe ObjectMapper instance. */
    public static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonUtils() {
        // Utility class — no instantiation
    }
}
