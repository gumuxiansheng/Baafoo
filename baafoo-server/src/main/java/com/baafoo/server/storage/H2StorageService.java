package com.baafoo.server.storage;

import com.baafoo.core.config.ServerConfig;
import com.baafoo.core.model.*;
import com.baafoo.server.storage.repo.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.*;

public class H2StorageService implements StorageService {

    private static final Logger log = LoggerFactory.getLogger(H2StorageService.class);

    private final ServerConfig config;
    private final ObjectMapper mapper;
    private HikariDataSource dataSource;
    private RuleRepository ruleRepo;
    private EnvironmentRepository envRepo;
    private SceneRepository sceneRepo;
    private RecordingRepository recordingRepo;
    private AgentRepository agentRepo;
    private UserRepository userRepo;

    public H2StorageService(ServerConfig config) {
        this.config = config;
        this.mapper = new ObjectMapper();
        this.mapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    @Override
    public void init() throws Exception {
        String dbPath = config.getDataDir() + "/baafoo";
        java.io.File dbDir = new java.io.File(config.getDataDir());
        if (!dbDir.exists()) {
            dbDir.mkdirs();
        }
        String jdbcUrl = "jdbc:h2:file:" + dbPath + ";DB_CLOSE_DELAY=-1";

        HikariConfig hkConfig = new HikariConfig();
        hkConfig.setJdbcUrl(jdbcUrl);
        hkConfig.setUsername("sa");
        hkConfig.setPassword("");
        hkConfig.setMaximumPoolSize(10);
        hkConfig.setMinimumIdle(2);
        hkConfig.setIdleTimeout(30000);
        hkConfig.setConnectionTimeout(10000);
        hkConfig.setAutoCommit(true);
        hkConfig.setPoolName("baafoo-h2-pool");

        dataSource = new HikariDataSource(hkConfig);

        try (Connection conn = dataSource.getConnection()) {
            createTablesIfNotExist(conn);
        }

        JsonColumnHelper jsonHelper = new JsonColumnHelper(mapper);
        ruleRepo = new RuleRepository(dataSource, jsonHelper);
        envRepo = new EnvironmentRepository(dataSource, jsonHelper);
        sceneRepo = new SceneRepository(dataSource, jsonHelper, ruleRepo);
        recordingRepo = new RecordingRepository(dataSource, jsonHelper);
        agentRepo = new AgentRepository(dataSource, jsonHelper, envRepo);
        userRepo = new UserRepository(dataSource);

        log.info("H2 storage initialized with HikariCP pool: {}", dbPath);
    }

    @Override
    public void shutdown() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            log.info("H2 storage connection pool closed");
        }
    }

    private void createTablesIfNotExist(Connection conn) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS rules (" +
                "  id VARCHAR(36) PRIMARY KEY," +
                "  name VARCHAR(255)," +
                "  protocol VARCHAR(50)," +
                "  service_name VARCHAR(255)," +
                "  host VARCHAR(255)," +
                "  port INT," +
                "  conditions_json TEXT," +
                "  responses_json TEXT," +
                "  enabled BOOLEAN DEFAULT TRUE," +
                "  priority INT DEFAULT 100," +
                "  tags_json TEXT," +
                "  environments_json TEXT," +
                "  version INT DEFAULT 1," +
                "  created_at BIGINT," +
                "  updated_at BIGINT" +
                ")"
            );

            try {
                stmt.executeUpdate("ALTER TABLE rules ADD COLUMN environments_json TEXT");
            } catch (SQLException e) {
            }

            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS rule_history (" +
                "  id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                "  rule_id VARCHAR(36) NOT NULL," +
                "  rule_snapshot TEXT NOT NULL," +
                "  created_at BIGINT" +
                ")"
            );

            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS environments (" +
                "  id VARCHAR(36) PRIMARY KEY," +
                "  name VARCHAR(255) NOT NULL," +
                "  mode VARCHAR(50) DEFAULT 'STUB'," +
                "  agent_ids_json TEXT," +
                "  variables_json TEXT," +
                "  metadata_json TEXT," +
                "  created_at BIGINT," +
                "  updated_at BIGINT" +
                ")"
            );

            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS scene_sets (" +
                "  id VARCHAR(36) PRIMARY KEY," +
                "  name VARCHAR(255)," +
                "  description TEXT," +
                "  item_ids_json TEXT," +
                "  active BOOLEAN DEFAULT FALSE," +
                "  tags_json TEXT," +
                "  environments_json TEXT," +
                "  created_at BIGINT," +
                "  updated_at BIGINT" +
                ")"
            );

            try {
                stmt.executeUpdate("ALTER TABLE scene_sets ADD COLUMN environments_json TEXT");
            } catch (SQLException e) {
            }

            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS rule_sets (" +
                "  id VARCHAR(36) PRIMARY KEY," +
                "  name VARCHAR(255)," +
                "  description TEXT," +
                "  rule_ids_json TEXT," +
                "  enabled BOOLEAN DEFAULT TRUE," +
                "  tags_json TEXT," +
                "  created_at BIGINT," +
                "  updated_at BIGINT" +
                ")"
            );

            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS recordings (" +
                "  id VARCHAR(36) PRIMARY KEY," +
                "  rule_id VARCHAR(36)," +
                "  environment_id VARCHAR(36)," +
                "  agent_id VARCHAR(36)," +
                "  protocol VARCHAR(50)," +
                "  host VARCHAR(255)," +
                "  port INT," +
                "  service_name VARCHAR(255)," +
                "  method VARCHAR(20)," +
                "  path TEXT," +
                "  request_headers_json TEXT," +
                "  request_body TEXT," +
                "  response_status_code INT," +
                "  response_headers_json TEXT," +
                "  response_body TEXT," +
                "  response_time_ms BIGINT," +
                "  recorded_at BIGINT," +
                "  tags_json TEXT" +
                ")"
            );

            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS agents (" +
                "  agent_id VARCHAR(36) PRIMARY KEY," +
                "  environment VARCHAR(255)," +
                "  hostname VARCHAR(255)," +
                "  version VARCHAR(50)," +
                "  protocols_json TEXT," +
                "  registered_at BIGINT," +
                "  last_heartbeat BIGINT" +
                ")"
            );

            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_rules_protocol ON rules(protocol)");
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_rules_enabled ON rules(enabled)");
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_rules_priority ON rules(priority)");
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_environments_name ON environments(name)");
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_recordings_rule_id ON recordings(rule_id)");
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_recordings_recorded_at ON recordings(recorded_at)");
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_agents_environment ON agents(environment)");
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_rule_history_rule_id ON rule_history(rule_id)");

            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS users (" +
                "  id VARCHAR(36) PRIMARY KEY," +
                "  username VARCHAR(255) NOT NULL UNIQUE," +
                "  password_hash VARCHAR(512) NOT NULL," +
                "  display_name VARCHAR(255)," +
                "  email VARCHAR(255)," +
                "  role VARCHAR(50) DEFAULT 'guest'," +
                "  api_key VARCHAR(255)," +
                "  created_at BIGINT," +
                "  updated_at BIGINT," +
                "  last_login_at BIGINT" +
                ")"
            );

            try {
                stmt.executeUpdate("ALTER TABLE users ADD COLUMN IF NOT EXISTS display_name VARCHAR(255)");
            } catch (SQLException ignored) {}
            try {
                stmt.executeUpdate("ALTER TABLE users ADD COLUMN IF NOT EXISTS email VARCHAR(255)");
            } catch (SQLException ignored) {}

            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_users_username ON users(username)");
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_users_api_key ON users(api_key)");
        }

        log.info("Database tables verified/created");
    }

    @Override
    public List<Rule> listRules() { return ruleRepo.listRules(); }

    @Override
    public Rule getRule(String id) { return ruleRepo.getRule(id); }

    @Override
    public Rule createRule(Rule rule) { return ruleRepo.createRule(rule); }

    @Override
    public Rule updateRule(String id, Rule update) { return ruleRepo.updateRule(id, update); }

    @Override
    public boolean deleteRule(String id) { return ruleRepo.deleteRule(id); }

    @Override
    public boolean undoRule(String id) { return ruleRepo.undoRule(id); }

    @Override
    public List<Environment> listEnvironments() { return envRepo.listEnvironments(); }

    @Override
    public Environment getEnvironment(String id) { return envRepo.getEnvironment(id); }

    @Override
    public Environment getEnvironmentByName(String name) { return envRepo.getEnvironmentByName(name); }

    @Override
    public Environment createEnvironment(Environment env) { return envRepo.createEnvironment(env); }

    @Override
    public Environment updateEnvironment(String id, Environment update) { return envRepo.updateEnvironment(id, update); }

    @Override
    public boolean deleteEnvironment(String id) { return envRepo.deleteEnvironment(id); }

    @Override
    public List<SceneSet> listScenes() { return sceneRepo.listScenes(); }

    @Override
    public SceneSet getScene(String id) { return sceneRepo.getScene(id); }

    @Override
    public SceneSet createScene(SceneSet scene) { return sceneRepo.createScene(scene); }

    @Override
    public SceneSet updateScene(String id, SceneSet update) { return sceneRepo.updateScene(id, update); }

    @Override
    public boolean deleteScene(String id) { return sceneRepo.deleteScene(id); }

    @Override
    public List<RuleSet> listRuleSets() { return ruleRepo.listRuleSets(); }

    @Override
    public RuleSet createRuleSet(RuleSet ruleSet) { return ruleRepo.createRuleSet(ruleSet); }

    @Override
    public boolean deleteRuleSet(String id) { return ruleRepo.deleteRuleSet(id); }

    @Override
    public List<RecordingEntry> listRecordings(String ruleId, int limit) { return recordingRepo.listRecordings(ruleId, limit); }

    @Override
    public void addRecording(RecordingEntry recording) { recordingRepo.addRecording(recording); }

    @Override
    public void addRecordings(List<RecordingEntry> batch) { recordingRepo.addRecordings(batch); }

    @Override
    public boolean deleteRecording(String id) { return recordingRepo.deleteRecording(id); }

    @Override
    public AgentRegistration registerAgent(String agentId, String environment, String hostname, String version, List<String> protocols) {
        return agentRepo.registerAgent(agentId, environment, hostname, version, protocols);
    }

    @Override
    public void agentHeartbeat(String agentId) { agentRepo.agentHeartbeat(agentId); }

    @Override
    public List<AgentRegistration> listAgents() { return agentRepo.listAgents(); }

    @Override
    public List<AgentRegistration> getAgentsForEnvironment(String envName) { return agentRepo.getAgentsForEnvironment(envName); }

    @Override
    public void associateRulesToEnvironment(String envName, List<String> ruleIds) { ruleRepo.associateRulesToEnvironment(envName, ruleIds); }

    @Override
    public void dissociateRulesFromEnvironment(String envName, List<String> ruleIds) { ruleRepo.dissociateRulesFromEnvironment(envName, ruleIds); }

    @Override
    public List<User> listUsers() { return userRepo.listUsers(); }

    @Override
    public User getUserByUsername(String username) { return userRepo.getUserByUsername(username); }

    @Override
    public User getUserByApiKey(String apiKey) { return userRepo.getUserByApiKey(apiKey); }

    @Override
    public User createUser(User user) { return userRepo.createUser(user); }

    @Override
    public boolean updateUserRole(String username, String role) { return userRepo.updateUserRole(username, role); }

    @Override
    public boolean updateUserApiKey(String username, String apiKey) { return userRepo.updateUserApiKey(username, apiKey); }

    @Override
    public boolean updateUserLastLogin(String username) { return userRepo.updateUserLastLogin(username); }

    @Override
    public boolean deleteUser(String username) { return userRepo.deleteUser(username); }
}
