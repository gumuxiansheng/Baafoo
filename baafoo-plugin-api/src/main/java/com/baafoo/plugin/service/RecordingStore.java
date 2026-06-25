package com.baafoo.plugin.service;

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
}
