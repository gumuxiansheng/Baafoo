package com.baafoo.server.storage;

import com.baafoo.core.api.PaginatedResult;
import com.baafoo.core.model.*;
import java.util.List;
import java.util.Map;

/**
 * Storage service interface.
 * Decouples storage implementation from business logic.
 */
public interface StorageService {

    // --- Lifecycle ---

    void init() throws Exception;

    void shutdown();

    // --- Rule CRUD ---

    List<Rule> listRules();

    PaginatedResult<Rule> listRulesPaged(String protocol, String keyword, String environment, String host, int page, int size);

    Rule getRule(String id);

    Rule createRule(Rule rule);

    Rule updateRule(String id, Rule update);

    boolean deleteRule(String id);

    boolean undoRule(String id);

    // --- Environment CRUD ---

    List<Environment> listEnvironments();

    Environment getEnvironment(String id);

    Environment getEnvironmentByName(String name);

    Environment createEnvironment(Environment env);

    Environment updateEnvironment(String id, Environment update);

    boolean deleteEnvironment(String id);

    // --- Scene Set CRUD ---

    List<SceneSet> listScenes();

    SceneSet getScene(String id);

    SceneSet createScene(SceneSet scene);

    SceneSet updateScene(String id, SceneSet update);

    boolean deleteScene(String id);

    // --- Rule Set CRUD ---

    List<RuleSet> listRuleSets();

    RuleSet createRuleSet(RuleSet ruleSet);

    boolean deleteRuleSet(String id);

    // --- MQ Relationship CRUD ---

    List<MqRelationship> listMqRelationships();

    List<MqRelationship> listMqRelationshipsByFrom(String fromProtocol, String fromTopic);

    MqRelationship getMqRelationship(String id);

    MqRelationship createMqRelationship(MqRelationship relationship);

    MqRelationship updateMqRelationship(String id, MqRelationship update);

    boolean deleteMqRelationship(String id);

    // --- Recording ---

    List<RecordingEntry> listRecordings(String ruleId, int limit);

    PaginatedResult<RecordingEntry> listRecordingsPaged(String ruleId, String agentId, String agentIp,
                                                         String protocol, String method, String path,
                                                         Integer statusCode, String keyword,
                                                         int page, int size);

    void addRecording(RecordingEntry recording);

    void addRecordings(List<RecordingEntry> batch);

    boolean deleteRecording(String id);

    /**
     * Delete recordings older than the specified number of days.
     * @param retentionDays number of days to retain
     * @return number of recordings deleted
     */
    int deleteRecordingsOlderThan(int retentionDays);

    /**
     * Get the total count of recordings.
     * @return total recording count
     */
    long getRecordingCount();

    /**
     * Get the total size of recordings in bytes (estimated).
     * @return estimated total size in bytes
     */
    long getRecordingTotalSizeBytes();

    /**
     * Get recording counts grouped by day since the given start time.
     * @param startTime start time in milliseconds
     * @return list of maps with "day" (epoch millis) and "count" keys
     */
    List<Map<String, Object>> getRecordingCountsByDay(long startTime);

    // --- Agent Management ---

    AgentRegistration registerAgent(String agentId, String environment, String hostname, String version, List<String> protocols, String agentIp);

    void agentHeartbeat(String agentId, String agentIp);

    /**
     * P3: Update plugin health statuses for an agent (in-memory, not persisted).
     * Called from heartbeat handler when agent reports plugin statuses.
     */
    void updateAgentPluginStatuses(String agentId, Map<String, Object> pluginStatuses);

    List<AgentRegistration> listAgents();

    List<AgentRegistration> getAgentsForEnvironment(String envName);

    // --- Environment-Rule Association ---

    void associateRulesToEnvironment(String envName, List<String> ruleIds);

    void dissociateRulesFromEnvironment(String envName, List<String> ruleIds);

    // --- User CRUD ---

    List<User> listUsers();

    User getUserByUsername(String username);

    User getUserByApiKey(String apiKey);

    User createUser(User user);

    boolean updateUserRole(String username, String role);

    boolean updateUserApiKey(String username, String apiKey);

    boolean updateUserLastLogin(String username);

    boolean deleteUser(String username);

    // --- DTO ---

    /**
     * Agent registration info.
     */
    class AgentRegistration {
        public String agentId;
        public String environment;
        public String hostname;
        public String version;
        public List<String> protocols;
        public String agentIp;
        public long registeredAt;
        public long lastHeartbeat;

        /** P3: Plugin health statuses (in-memory only, refreshed via heartbeat). */
        public Map<String, Object> pluginStatuses;

        public String getAgentId() { return agentId; }
        public long getLastHeartbeat() { return lastHeartbeat; }
        public Map<String, Object> getPluginStatuses() { return pluginStatuses; }
    }
}
