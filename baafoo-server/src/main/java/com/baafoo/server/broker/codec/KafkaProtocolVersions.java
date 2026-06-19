package com.baafoo.server.broker.codec;

/**
 * Centralized Kafka API version declaration table.
 *
 * <p>Defines the min/max supported version for each Kafka API key that
 * {@link com.baafoo.server.broker.KafkaProtocolDecoder} advertises in its
 * ApiVersions response.
 *
 * <h2>Version cap strategy (KIP-482 flexible versions)</h2>
 * <p>Kafka introduced <em>flexible versions</em> (KIP-482) starting with
 * ApiVersions v3. When a client negotiates ApiVersions v3+, it switches
 * all subsequent requests to Request Header v2 (compact strings, unsigned
 * varint lengths). Baafoo caps ApiVersions at v2 to prevent this switch,
 * allowing us to use the simpler int16-prefixed string format throughout.
 *
 * <p>Within the non-flexible range, we raise the caps to cover as many
 * client versions as possible:
 * <ul>
 *   <li>Produce v8 — covers Kafka 2.4+ clients (non-flexible max)</li>
 *   <li>Fetch v11 — covers Kafka 2.8+ clients (non-flexible max)</li>
 *   <li>Metadata v8 — covers Kafka 2.4+ clients (non-flexible max)</li>
 *   <li>ApiVersions v2 — the KIP-511 gate (kept at v2)</li>
 * </ul>
 *
 * <p>This covers Java kafka-clients 2.x, librdkafka 1.x, and most
 * non-Java clients. Kafka 3.x+ clients that require flexible versions
 * will negotiate down to these caps.
 *
 * @see <a href="https://cwiki.apache.org/confluence/display/KAFKA/KIP-482">KIP-482</a>
 * @see <a href="https://cwiki.apache.org/confluence/display/KAFKA/KIP-511">KIP-511</a>
 */
public final class KafkaProtocolVersions {

    private KafkaProtocolVersions() {
    }

    // ===== API keys (Kafka protocol) =====

    public static final short API_PRODUCE = 0;
    public static final short API_FETCH = 1;
    public static final short API_METADATA = 3;
    public static final short API_OFFSET_COMMIT = 8;
    public static final short API_OFFSET_FETCH = 9;
    public static final short API_FIND_COORDINATOR = 10;
    public static final short API_JOIN_GROUP = 11;
    public static final short API_HEARTBEAT = 12;
    public static final short API_LEAVE_GROUP = 13;
    public static final short API_SYNC_GROUP = 14;
    public static final short API_DESCRIBE_GROUPS = 15;
    public static final short API_LIST_GROUPS = 16;
    public static final short API_API_VERSIONS = 18;
    public static final short API_INIT_PRODUCER_ID = 22;
    public static final short API_DESCRIBE_CONFIGS = 32;

    // ===== Flexible-version thresholds (KIP-482) =====

    /** Produce becomes flexible at v9. */
    public static final short PRODUCE_FLEXIBLE_VERSION = 9;
    /** Fetch becomes flexible at v12. */
    public static final short FETCH_FLEXIBLE_VERSION = 12;
    /** Metadata becomes flexible at v9. */
    public static final short METADATA_FLEXIBLE_VERSION = 9;
    /** ApiVersions becomes flexible at v3 — the KIP-511 gate. */
    public static final short API_VERSIONS_FLEXIBLE_VERSION = 3;

    // ===== Supported version ranges =====

    /**
     * Supported API version table: {apiKey, minVersion, maxVersion}.
     *
     * <p>Caps are set just below the flexible-version threshold for each API.
     * This lets Baafoo handle the non-flexible wire format while still
     * supporting modern Kafka clients (2.x+).
     */
    public static final int[][] SUPPORTED_APIS = {
            {API_PRODUCE,           0, 8},   // v3→v8: covers Kafka 0.11-2.4+
            {API_FETCH,             0, 11},  // v7→v11: covers Kafka 2.4-2.8+
            {API_METADATA,          0, 8},   // unchanged (v8 = non-flexible max)
            {API_OFFSET_COMMIT,     0, 8},
            {API_OFFSET_FETCH,      0, 8},
            {API_FIND_COORDINATOR,  0, 4},
            {API_JOIN_GROUP,        0, 7},
            {API_HEARTBEAT,         0, 4},
            {API_LEAVE_GROUP,       0, 4},
            {API_SYNC_GROUP,        0, 5},
            {API_DESCRIBE_GROUPS,   0, 5},
            {API_LIST_GROUPS,       0, 4},
            {API_API_VERSIONS,      0, 2},   // KIP-511 gate: cap at v2
            {API_INIT_PRODUCER_ID,  0, 1},
            {API_DESCRIBE_CONFIGS,  0, 4}
    };

    /**
     * Check if a given API version uses flexible wire format.
     */
    public static boolean isFlexible(short apiKey, short apiVersion) {
        switch (apiKey) {
            case API_PRODUCE:      return apiVersion >= PRODUCE_FLEXIBLE_VERSION;
            case API_FETCH:        return apiVersion >= FETCH_FLEXIBLE_VERSION;
            case API_METADATA:     return apiVersion >= METADATA_FLEXIBLE_VERSION;
            case API_API_VERSIONS: return apiVersion >= API_VERSIONS_FLEXIBLE_VERSION;
            default: return false;
        }
    }

    /**
     * Get the max supported version for an API key.
     */
    public static short maxVersion(short apiKey) {
        for (int[] api : SUPPORTED_APIS) {
            if (api[0] == apiKey) {
                return (short) api[2];
            }
        }
        return 0;
    }
}
