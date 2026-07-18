package com.baafoo.plugin.service;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Recording storage service.
 *
 * <p>Uses {@code Map<String,Object>} instead of domain models to keep
 * baafoo-plugin-api zero-dependency.</p>
 *
 * <p>Map keys for recordings: id, ruleId, protocol, host, port, method,
 * path, requestBody, responseBody, responseStatusCode, recordedAt,
 * environmentId.</p>
 *
 * <p>L-15: Optional operations are declared as {@code default} methods so existing
 * implementations don't break when new query/delete capabilities are added.</p>
 */
public interface RecordingStore {

    /**
     * Save a recording entry.
     *
     * @param recording recording map
     */
    void save(Map<String, Object> recording);

    /**
     * Query recordings by environment.
     *
     * @param environmentId environment ID
     * @param limit max results
     * @return list of recording maps, empty if none
     */
    List<Map<String, Object>> listByEnvironment(String environmentId, int limit);

    /**
     * Query recordings by rule ID.
     * <p>Default implementation returns an empty list — override to support rule-scoped queries.</p>
     *
     * @param ruleId rule ID
     * @param limit max results
     * @return list of recording maps, empty if unsupported or none
     */
    default List<Map<String, Object>> listByRule(String ruleId, int limit) {
        return Collections.emptyList();
    }

    /**
     * Delete a recording by ID.
     * <p>Default implementation is a no-op — override to support deletion.</p>
     *
     * @param id recording ID
     * @return true if a recording was deleted, false otherwise
     */
    default boolean delete(String id) {
        return false;
    }

    /**
     * Count recordings for an environment.
     * <p>Default implementation returns -1 to signal "unsupported" — override to enable.</p>
     *
     * @param environmentId environment ID
     * @return count, or -1 if unsupported
     */
    default long countByEnvironment(String environmentId) {
        return -1;
    }
}
