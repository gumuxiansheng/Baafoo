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
 * varint lengths). Baafoo currently caps all APIs below their flexible
 * threshold so that clients use Request Header v0/v1 (int16-prefixed
 * strings) throughout. This avoids the need for dual-mode parsing in
 * every request handler.
 *
 * <p>All APIs are capped below their flexible threshold:
 * <ul>
 *   <li>Produce v8 — non-flexible max (flexible at v9)</li>
 *   <li>Fetch v11 — non-flexible max (flexible at v12)</li>
 *   <li>Metadata v8 — non-flexible max (flexible at v9)</li>
 *   <li>ApiVersions v2 — non-flexible max (KIP-511 gate, flexible at v3)</li>
 *   <li>ListOffsets v5 — non-flexible max (flexible at v6)</li>
 *   <li>OffsetCommit v7 — non-flexible max (flexible at v8)</li>
 *   <li>OffsetFetch v5 — non-flexible max (flexible at v6)</li>
 *   <li>FindCoordinator v2 — non-flexible max (flexible at v3)</li>
 *   <li>JoinGroup v5 — non-flexible max (flexible at v6)</li>
 *   <li>Heartbeat v3 — non-flexible max (flexible at v4)</li>
 *   <li>LeaveGroup v3 — non-flexible max (flexible at v4)</li>
 *   <li>SyncGroup v3 — non-flexible max (flexible at v4)</li>
 *   <li>DescribeGroups v4 — non-flexible max (flexible at v5)</li>
 *   <li>ListGroups v2 — non-flexible max (flexible at v3)</li>
 *   <li>InitProducerId v1 — non-flexible max (flexible at v2)</li>
 *   <li>DescribeConfigs v3 — non-flexible max (flexible at v4)</li>
 * </ul>
 *
 * <p>This covers Java kafka-clients 2.x, 3.x, librdkafka 1.x, and most
 * non-Java clients including Kafka 3.x+ that require flexible versions.
 *
 * @see <a href="https://cwiki.apache.org/confluence/display/KAFKA/KIP-482">KIP-482</a>
 * @see <a href="https://cwiki.apache.org/confluence/display/KAFKA/KIP-511">KIP-511</a>
 */
public final class KafkaProtocolVersions {

    private KafkaProtocolVersions() {
    }

    // ===== API keys (Kafka protocol) =====

    /** Kafka error code: no error. */
    public static final short NONE = 0;

    /** Kafka error code: unsupported version. */
    public static final short UNSUPPORTED_VERSION = 35;

    public static final short API_PRODUCE = 0;
    public static final short API_FETCH = 1;
    public static final short API_LIST_OFFSETS = 2;
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

    /** ListOffsets becomes flexible at v6. */
    public static final short LIST_OFFSETS_FLEXIBLE_VERSION = 6;
    /** Produce becomes flexible at v9. */
    public static final short PRODUCE_FLEXIBLE_VERSION = 9;
    /** Fetch becomes flexible at v12. */
    public static final short FETCH_FLEXIBLE_VERSION = 12;
    /** Metadata becomes flexible at v9. */
    public static final short METADATA_FLEXIBLE_VERSION = 9;
    /** ApiVersions becomes flexible at v3 — the KIP-511 gate. */
    public static final short API_VERSIONS_FLEXIBLE_VERSION = 3;
    /** OffsetCommit becomes flexible at v8. */
    public static final short OFFSET_COMMIT_FLEXIBLE_VERSION = 8;
    /** OffsetFetch becomes flexible at v6. */
    public static final short OFFSET_FETCH_FLEXIBLE_VERSION = 6;
    /** FindCoordinator becomes flexible at v3. */
    public static final short FIND_COORDINATOR_FLEXIBLE_VERSION = 3;
    /** JoinGroup becomes flexible at v6. */
    public static final short JOIN_GROUP_FLEXIBLE_VERSION = 6;
    /** Heartbeat becomes flexible at v4. */
    public static final short HEARTBEAT_FLEXIBLE_VERSION = 4;
    /** LeaveGroup becomes flexible at v4. */
    public static final short LEAVE_GROUP_FLEXIBLE_VERSION = 4;
    /** SyncGroup becomes flexible at v4. */
    public static final short SYNC_GROUP_FLEXIBLE_VERSION = 4;
    /** DescribeGroups becomes flexible at v5. */
    public static final short DESCRIBE_GROUPS_FLEXIBLE_VERSION = 5;
    /** ListGroups becomes flexible at v3. */
    public static final short LIST_GROUPS_FLEXIBLE_VERSION = 3;
    /** InitProducerId becomes flexible at v2. */
    public static final short INIT_PRODUCER_ID_FLEXIBLE_VERSION = 2;
    /** DescribeConfigs becomes flexible at v4. */
    public static final short DESCRIBE_CONFIGS_FLEXIBLE_VERSION = 4;

    // ===== Supported version ranges =====

    /**
     * Supported API version table: {apiKey, minVersion, maxVersion}.
     *
     * <p>APIs with flexible codec support are raised to their flexible version.
     * APIs without flexible codec support are capped below the flexible threshold.
     */
    public static final int[][] SUPPORTED_APIS = {
            {API_PRODUCE,           0, 8},   // flexible at v9, cap v8 (keep non-flex)
            {API_FETCH,             0, 11},  // flexible at v12, cap v11 (keep non-flex)
            {API_LIST_OFFSETS,      0, 5},   // flexible at v6, cap v5 (keep non-flex)
            {API_METADATA,          0, 8},   // flexible at v9, cap v8 (keep non-flex)
            {API_OFFSET_COMMIT,     0, 7},   // flexible at v8, cap v7 (keep non-flex)
            {API_OFFSET_FETCH,      0, 5},   // flexible at v6, cap v5 (keep non-flex)
            {API_FIND_COORDINATOR,  0, 2},   // flexible at v3, cap v2 (keep non-flex)
            {API_JOIN_GROUP,        0, 5},   // flexible at v6, cap v5 (keep non-flex)
            {API_HEARTBEAT,         0, 3},   // flexible at v4, cap v3 (keep non-flex)
            {API_LEAVE_GROUP,       0, 3},   // flexible at v4, cap v3 (keep non-flex)
            {API_SYNC_GROUP,        0, 3},   // flexible at v4, cap v3 (keep non-flex)
            {API_DESCRIBE_GROUPS,   0, 4},   // flexible at v5, cap v4 (keep non-flex)
            {API_LIST_GROUPS,       0, 2},   // flexible at v3, cap v2 (keep non-flex)
            {API_API_VERSIONS,      0, 3},   // KIP-511 gate: v3 is flexible; server handles v3 request (non-flex header) + v3 response (flex body)
            {API_INIT_PRODUCER_ID,  0, 1},   // flexible at v2, cap v1 (keep non-flex)
            {API_DESCRIBE_CONFIGS,  0, 3}    // flexible at v4, cap v3 (keep non-flex)
    };

    /**
     * Check if a given API version uses flexible wire format.
     */
    public static boolean isFlexible(short apiKey, short apiVersion) {
        switch (apiKey) {
            case API_LIST_OFFSETS:      return apiVersion >= LIST_OFFSETS_FLEXIBLE_VERSION;
            case API_PRODUCE:           return apiVersion >= PRODUCE_FLEXIBLE_VERSION;
            case API_FETCH:             return apiVersion >= FETCH_FLEXIBLE_VERSION;
            case API_METADATA:          return apiVersion >= METADATA_FLEXIBLE_VERSION;
            case API_API_VERSIONS:      return apiVersion >= API_VERSIONS_FLEXIBLE_VERSION;
            case API_OFFSET_COMMIT:     return apiVersion >= OFFSET_COMMIT_FLEXIBLE_VERSION;
            case API_OFFSET_FETCH:      return apiVersion >= OFFSET_FETCH_FLEXIBLE_VERSION;
            case API_FIND_COORDINATOR:  return apiVersion >= FIND_COORDINATOR_FLEXIBLE_VERSION;
            case API_JOIN_GROUP:        return apiVersion >= JOIN_GROUP_FLEXIBLE_VERSION;
            case API_HEARTBEAT:         return apiVersion >= HEARTBEAT_FLEXIBLE_VERSION;
            case API_LEAVE_GROUP:       return apiVersion >= LEAVE_GROUP_FLEXIBLE_VERSION;
            case API_SYNC_GROUP:        return apiVersion >= SYNC_GROUP_FLEXIBLE_VERSION;
            case API_DESCRIBE_GROUPS:   return apiVersion >= DESCRIBE_GROUPS_FLEXIBLE_VERSION;
            case API_LIST_GROUPS:       return apiVersion >= LIST_GROUPS_FLEXIBLE_VERSION;
            case API_INIT_PRODUCER_ID:  return apiVersion >= INIT_PRODUCER_ID_FLEXIBLE_VERSION;
            case API_DESCRIBE_CONFIGS:  return apiVersion >= DESCRIBE_CONFIGS_FLEXIBLE_VERSION;
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
