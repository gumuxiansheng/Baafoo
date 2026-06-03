package com.baafoo.server.storage;

import com.baafoo.core.api.PaginatedResult;
import com.baafoo.core.model.*;
import java.util.List;

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

    PaginatedResult<Rule> listRulesPaged(String protocol, String keyword, int page, int size);

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

    // --- Recording ---

    List<RecordingEntry> listRecordings(String ruleId, int limit);

    PaginatedResult<RecordingEntry> listRecordingsPaged(String ruleId, int page, int size);

    void addRecording(RecordingEntry recording);

    void addRecordings(List<RecordingEntry> batch);

    boolean deleteRecording(String id);

    // --- Agent Management ---

    AgentRegistration registerAgent(String agentId, String environment, String hostname, String version, List<String> protocols, String agentIp);

    void agentHeartbeat(String agentId, String agentIp);

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

        public String getAgentId() { return agentId; }
        public long getLastHeartbeat() { return lastHeartbeat; }
    }
}
