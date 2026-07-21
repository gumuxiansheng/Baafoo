package com.baafoo.core.util;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

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

    /**
     * L1: Shared, thread-safe ObjectMapper instance.
     *
     * <p><b>Mutability warning</b>: {@code ObjectMapper} is thread-safe for
     * read operations (readValue / writeValue) ONLY after configuration is
     * complete. Reconfiguring this shared instance after startup
     * (registering modules, changing features, adding mixins) is NOT safe
     * under concurrent access and will affect every other consumer in the
     * process. Callers that need a custom-configured mapper must create
     * their own {@code new ObjectMapper()} (or {@code MAPPER.copy()}) —
     * never mutate this singleton.</p>
     *
     * <p>Configuration: registers {@link JavaTimeModule} and disables
     * {@link SerializationFeature#WRITE_DATES_AS_TIMESTAMPS} so that Java 8
     * date/time types (OffsetDateTime, LocalDateTime, etc.) are serialized
     * as ISO-8601 strings. Without this, any DTO exposing
     * {@code OffsetDateTime} (e.g. UserSafeResponse.createdAt) would trigger
     * "Java 8 date/time type not supported by default" at serialization time
     * and close the HTTP channel without sending a response.</p>
     */
    public static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private JsonUtils() {
        // Utility class — no instantiation
    }
}
