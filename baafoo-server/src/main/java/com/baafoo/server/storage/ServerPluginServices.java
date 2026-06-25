package com.baafoo.server.storage;

import com.baafoo.plugin.service.PluginServices;
import com.baafoo.plugin.service.RecordingStore;
import com.baafoo.plugin.service.RuleStore;
import com.baafoo.plugin.service.ServerAdmin;
import com.baafoo.server.storage.StorageService;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Server-side implementation of PluginServices.
 *
 * <p>Wraps StorageService to provide plugin-facing access to rules,
 * recordings, and admin actions. Uses {@code Map<String,Object>} to
 * keep plugin-api zero-dependency.</p>
 */
public class ServerPluginServices implements PluginServices {

    private final RuleStore ruleStore;
    private final RecordingStore recordingStore;
    private final ServerAdmin serverAdmin;

    public ServerPluginServices(StorageService storageService,
                                 ServerAdmin serverAdmin) {
        this.ruleStore = new ServerRuleStore(storageService);
        this.recordingStore = new ServerRecordingStore(storageService);
        this.serverAdmin = serverAdmin;
    }

    @Override
    public RuleStore getRuleStore() { return ruleStore; }

    @Override
    public RecordingStore getRecordingStore() { return recordingStore; }

    @Override
    public ServerAdmin getServerAdmin() { return serverAdmin; }

    // ---- Shared conversion helpers ----

    @SuppressWarnings("unchecked")
    static Map<String, Object> ruleToMap(Object rule) {
        if (rule instanceof Map) {
            return (Map<String, Object>) rule;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("raw", rule);
        return m;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> recordingToMap(Object rec) {
        if (rec instanceof Map) {
            return (Map<String, Object>) rec;
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("raw", rec);
        return m;
    }

    static com.baafoo.core.model.RecordingEntry mapToRecordingEntry(Map<String, Object> map) {
        com.baafoo.core.model.RecordingEntry entry = new com.baafoo.core.model.RecordingEntry();
        entry.setRuleId((String) map.get("ruleId"));
        entry.setProtocol((String) map.get("protocol"));
        entry.setHost((String) map.get("host"));
        entry.setPort(map.get("port") instanceof Integer ? (Integer) map.get("port") : 0);
        entry.setMethod((String) map.get("method"));
        entry.setPath((String) map.get("path"));
        entry.setRequestBody((String) map.get("requestBody"));
        entry.setResponseBody((String) map.get("responseBody"));
        entry.setResponseStatusCode(map.get("responseStatusCode") instanceof Integer ? (Integer) map.get("responseStatusCode") : 200);
        return entry;
    }

    // ---- Inner classes ----

    /**
     * RuleStore implementation wrapping StorageService.
     */
    static class ServerRuleStore implements RuleStore {

        private final StorageService storageService;

        ServerRuleStore(StorageService storageService) {
            this.storageService = storageService;
        }

        @Override
        public List<Map<String, Object>> listRules(String environmentId) {
            List<?> rules = storageService.listRules();
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object rule : rules) {
                result.add(ruleToMap(rule));
            }
            return result;
        }

        @Override
        public Map<String, Object> getRule(String ruleId) {
            Object rule = storageService.getRule(ruleId);
            return rule != null ? ruleToMap(rule) : null;
        }
    }

    /**
     * RecordingStore implementation wrapping StorageService.
     */
    static class ServerRecordingStore implements RecordingStore {

        private final StorageService storageService;

        ServerRecordingStore(StorageService storageService) {
            this.storageService = storageService;
        }

        @Override
        public void save(Map<String, Object> recording) {
            try {
                com.baafoo.core.model.RecordingEntry entry = mapToRecordingEntry(recording);
                storageService.addRecording(entry);
            } catch (Exception e) {
                System.err.println("[Baafoo] Failed to save recording via PluginServices: " + e.getMessage());
            }
        }

        @Override
        public List<Map<String, Object>> listByEnvironment(String environmentId, int limit) {
            List<?> recordings = storageService.listRecordings(null, limit);
            List<Map<String, Object>> result = new ArrayList<>();
            for (Object rec : recordings) {
                result.add(recordingToMap(rec));
            }
            return result;
        }
    }
}
