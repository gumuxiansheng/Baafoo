package com.baafoo.server.storage;

import com.baafoo.core.config.ServerConfig;
import com.baafoo.core.model.*;
import com.baafoo.core.util.IdGenerator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.*;

/**
 * H2 embedded database storage implementation.
 *
 * <p>Uses H2 in embedded mode (file-based, single connection pool).
 * All complex fields (conditions, responses, tags, variables, metadata, etc.)
 * are serialized as JSON TEXT columns via Jackson.</p>
 *
 * <p>Thread safety: H2's MVCC handles concurrent reads; writes are
 * serialized at application level via synchronized methods or
 * database-level row locks.</p>
 */
public class H2StorageService implements StorageService {

    private static final Logger log = LoggerFactory.getLogger(H2StorageService.class);

    private final ServerConfig config;
    private final ObjectMapper mapper;
    private Connection conn;

    public H2StorageService(ServerConfig config) {
        this.config = config;
        this.mapper = new ObjectMapper();
        this.mapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    // ==================== Lifecycle ====================

    @Override
    public void init() throws Exception {
        String dbPath = config.getDataDir() + "/baafoo";
        // Ensure parent directory exists
        java.io.File dbDir = new java.io.File(config.getDataDir());
        if (!dbDir.exists()) {
            dbDir.mkdirs();
        }
        String jdbcUrl = "jdbc:h2:file:" + dbPath + ";DB_CLOSE_DELAY=-1";

        conn = DriverManager.getConnection(jdbcUrl, "sa", "");
        conn.setAutoCommit(true);

        createTablesIfNotExist();
        log.info("H2 storage initialized: {}", dbPath);
    }

    @Override
    public void shutdown() {
        try {
            if (conn != null && !conn.isClosed()) {
                conn.close();
                log.info("H2 storage connection closed");
            }
        } catch (SQLException e) {
            log.error("Error closing H2 connection: {}", e.getMessage());
        }
    }

    private void createTablesIfNotExist() throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            // Rules table
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
                "  version INT DEFAULT 1," +
                "  created_at BIGINT," +
                "  updated_at BIGINT" +
                ")"
            );

            // Rule version history (for undo)
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS rule_history (" +
                "  id BIGINT AUTO_INCREMENT PRIMARY KEY," +
                "  rule_id VARCHAR(36) NOT NULL," +
                "  rule_snapshot TEXT NOT NULL," +
                "  created_at BIGINT" +
                ")"
            );

            // Environments table
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

            // Scene sets table
            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS scene_sets (" +
                "  id VARCHAR(36) PRIMARY KEY," +
                "  name VARCHAR(255)," +
                "  description TEXT," +
                "  item_ids_json TEXT," +
                "  active BOOLEAN DEFAULT FALSE," +
                "  tags_json TEXT," +
                "  created_at BIGINT," +
                "  updated_at BIGINT" +
                ")"
            );

            // Rule sets table
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

            // Recordings table
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

            // Agent registrations table
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

            // Indexes
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_rules_protocol ON rules(protocol)");
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_rules_enabled ON rules(enabled)");
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_rules_priority ON rules(priority)");
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_environments_name ON environments(name)");
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_recordings_rule_id ON recordings(rule_id)");
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_recordings_recorded_at ON recordings(recorded_at)");
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_agents_environment ON agents(environment)");
            stmt.executeUpdate("CREATE INDEX IF NOT EXISTS idx_rule_history_rule_id ON rule_history(rule_id)");
        }

        log.info("Database tables verified/created");
    }

    // ==================== Rule CRUD ====================

    @Override
    public List<Rule> listRules() {
        String sql = "SELECT * FROM rules ORDER BY priority ASC";
        List<Rule> result = new ArrayList<Rule>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(mapRule(rs));
            }
        } catch (SQLException e) {
            log.error("Failed to list rules: {}", e.getMessage());
        }
        return result;
    }

    @Override
    public Rule getRule(String id) {
        String sql = "SELECT * FROM rules WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapRule(rs);
            }
        } catch (SQLException e) {
            log.error("Failed to get rule {}: {}", id, e.getMessage());
        }
        return null;
    }

    @Override
    public Rule createRule(Rule rule) {
        if (rule.getId() == null || rule.getId().isEmpty()) {
            rule.setId(IdGenerator.uuid());
        }
        rule.setVersion(1);
        long now = System.currentTimeMillis();
        rule.setCreatedAt(now);
        rule.setUpdatedAt(now);

        String sql = "INSERT INTO rules (id, name, protocol, service_name, host, port, " +
                "conditions_json, responses_json, enabled, priority, tags_json, version, " +
                "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, rule.getId());
            setRuleParams(ps, rule, 2);
            ps.executeUpdate();
            return rule;
        } catch (SQLException e) {
            log.error("Failed to create rule: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public Rule updateRule(String id, Rule update) {
        Rule existing = getRule(id);
        if (existing == null) return null;

        // Save snapshot for undo
        saveVersion(id, existing);

        // Apply updates
        if (update.getName() != null) existing.setName(update.getName());
        if (update.getProtocol() != null) existing.setProtocol(update.getProtocol());
        if (update.getServiceName() != null) existing.setServiceName(update.getServiceName());
        if (update.getHost() != null) existing.setHost(update.getHost());
        if (update.getPort() != null) existing.setPort(update.getPort());
        if (update.getConditions() != null) existing.setConditions(update.getConditions());
        if (update.getResponses() != null) existing.setResponses(update.getResponses());
        existing.setEnabled(update.isEnabled());
        existing.setPriority(update.getPriority());
        if (update.getTags() != null) existing.setTags(update.getTags());
        existing.setVersion(existing.getVersion() + 1);
        existing.setUpdatedAt(System.currentTimeMillis());

        String sql = "UPDATE rules SET name=?, protocol=?, service_name=?, host=?, port=?, " +
                "conditions_json=?, responses_json=?, enabled=?, priority=?, tags_json=?, " +
                "version=?, created_at=?, updated_at=? WHERE id=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            setRuleParams(ps, existing, 1);
            ps.setString(14, id);
            ps.executeUpdate();
            return existing;
        } catch (SQLException e) {
            log.error("Failed to update rule {}: {}", id, e.getMessage());
            return null;
        }
    }

    @Override
    public boolean deleteRule(String id) {
        // Also delete history
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM rule_history WHERE rule_id = ?")) {
            ps.setString(1, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to delete rule history for {}: {}", id, e.getMessage());
        }

        String sql = "DELETE FROM rules WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("Failed to delete rule {}: {}", id, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean undoRule(String id) {
        // Get the most recent history entry
        String findSql = "SELECT rule_snapshot FROM rule_history " +
                "WHERE rule_id = ? ORDER BY created_at DESC LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(findSql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) return false;
                String snapshot = rs.getString("rule_snapshot");
                Rule previous = mapper.readValue(snapshot, Rule.class);

                // Replace current rule with the snapshot
                String updateSql = "UPDATE rules SET name=?, protocol=?, service_name=?, " +
                        "host=?, port=?, conditions_json=?, responses_json=?, enabled=?, " +
                        "priority=?, tags_json=?, version=?, created_at=?, updated_at=? WHERE id=?";
                try (PreparedStatement ups = conn.prepareStatement(updateSql)) {
                    setRuleParams(ups, previous, 1);
                    ups.setString(14, id);
                    ups.executeUpdate();
                }

                // Delete the used history entry
                try (PreparedStatement dps = conn.prepareStatement(
                        "DELETE FROM rule_history WHERE rule_id = ? AND id = " +
                        "(SELECT id FROM rule_history WHERE rule_id = ? ORDER BY created_at DESC LIMIT 1)")) {
                    dps.setString(1, id);
                    dps.setString(2, id);
                    dps.executeUpdate();
                }

                return true;
            }
        } catch (Exception e) {
            log.error("Failed to undo rule {}: {}", id, e.getMessage());
            return false;
        }
    }

    private void saveVersion(String ruleId, Rule previous) {
        try {
            String snapshot = mapper.writeValueAsString(previous);
            String sql = "INSERT INTO rule_history (rule_id, rule_snapshot, created_at) VALUES (?, ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, ruleId);
                ps.setString(2, snapshot);
                ps.setLong(3, System.currentTimeMillis());
                ps.executeUpdate();
            }
            // Keep max 10 versions per rule
            try (PreparedStatement ps = conn.prepareStatement(
                    "DELETE FROM rule_history WHERE rule_id = ? AND id NOT IN " +
                    "(SELECT id FROM rule_history WHERE rule_id = ? ORDER BY created_at DESC LIMIT 10)")) {
                ps.setString(1, ruleId);
                ps.setString(2, ruleId);
                ps.executeUpdate();
            }
        } catch (Exception e) {
            log.error("Failed to save rule version: {}", e.getMessage());
        }
    }

    private Rule mapRule(ResultSet rs) throws SQLException {
        Rule r = new Rule();
        r.setId(rs.getString("id"));
        r.setName(rs.getString("name"));
        r.setProtocol(rs.getString("protocol"));
        r.setServiceName(rs.getString("service_name"));
        r.setHost(rs.getString("host"));
        int port = rs.getInt("port");
        r.setPort(rs.wasNull() ? null : port);
        r.setEnabled(rs.getBoolean("enabled"));
        r.setPriority(rs.getInt("priority"));
        r.setVersion(rs.getInt("version"));
        r.setCreatedAt(rs.getLong("created_at"));
        r.setUpdatedAt(rs.getLong("updated_at"));
        try {
            String condJson = rs.getString("conditions_json");
            if (condJson != null && !condJson.isEmpty()) {
                r.setConditions(mapper.readValue(condJson, new TypeReference<List<MatchCondition>>() {}));
            }
            String respJson = rs.getString("responses_json");
            if (respJson != null && !respJson.isEmpty()) {
                r.setResponses(mapper.readValue(respJson, new TypeReference<List<ResponseEntry>>() {}));
            }
            String tagsJson = rs.getString("tags_json");
            if (tagsJson != null && !tagsJson.isEmpty()) {
                r.setTags(mapper.readValue(tagsJson, new TypeReference<List<String>>() {}));
            }
        } catch (Exception e) {
            log.warn("Failed to deserialize rule fields: {}", e.getMessage());
        }
        return r;
    }

    private void setRuleParams(PreparedStatement ps, Rule r, int offset) throws SQLException {
        ps.setString(offset, r.getName());
        ps.setString(offset + 1, r.getProtocol());
        ps.setString(offset + 2, r.getServiceName());
        ps.setString(offset + 3, r.getHost());
        if (r.getPort() != null) {
            ps.setInt(offset + 4, r.getPort());
        } else {
            ps.setNull(offset + 4, Types.INTEGER);
        }
        try {
            ps.setString(offset + 5, r.getConditions() != null ? mapper.writeValueAsString(r.getConditions()) : null);
            ps.setString(offset + 6, r.getResponses() != null ? mapper.writeValueAsString(r.getResponses()) : null);
        } catch (Exception e) {
            ps.setString(offset + 5, null);
            ps.setString(offset + 6, null);
        }
        ps.setBoolean(offset + 7, r.isEnabled());
        ps.setInt(offset + 8, r.getPriority());
        try {
            ps.setString(offset + 9, r.getTags() != null ? mapper.writeValueAsString(r.getTags()) : null);
        } catch (Exception e) {
            ps.setString(offset + 9, null);
        }
        ps.setInt(offset + 10, r.getVersion());
        ps.setLong(offset + 11, r.getCreatedAt());
        ps.setLong(offset + 12, r.getUpdatedAt());
    }

    // ==================== Environment CRUD ====================

    @Override
    public List<Environment> listEnvironments() {
        String sql = "SELECT * FROM environments";
        List<Environment> result = new ArrayList<Environment>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(mapEnvironment(rs));
            }
        } catch (SQLException e) {
            log.error("Failed to list environments: {}", e.getMessage());
        }
        return result;
    }

    @Override
    public Environment getEnvironment(String id) {
        String sql = "SELECT * FROM environments WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapEnvironment(rs);
            }
        } catch (SQLException e) {
            log.error("Failed to get environment {}: {}", id, e.getMessage());
        }
        return null;
    }

    @Override
    public Environment getEnvironmentByName(String name) {
        String sql = "SELECT * FROM environments WHERE name = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapEnvironment(rs);
            }
        } catch (SQLException e) {
            log.error("Failed to get environment by name {}: {}", name, e.getMessage());
        }
        return null;
    }

    @Override
    public Environment createEnvironment(Environment env) {
        if (env.getId() == null || env.getId().isEmpty()) {
            env.setId(IdGenerator.uuid());
        }
        long now = System.currentTimeMillis();
        env.setCreatedAt(now);
        env.setUpdatedAt(now);

        String sql = "INSERT INTO environments (id, name, mode, agent_ids_json, variables_json, " +
                "metadata_json, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, env.getId());
            setEnvironmentParams(ps, env, 2);
            ps.executeUpdate();
            return env;
        } catch (SQLException e) {
            log.error("Failed to create environment: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public Environment updateEnvironment(String id, Environment update) {
        Environment existing = getEnvironment(id);
        if (existing == null) return null;

        if (update.getName() != null) existing.setName(update.getName());
        if (update.getMode() != null) existing.setMode(update.getMode());
        if (update.getVariables() != null) existing.setVariables(update.getVariables());
        if (update.getMetadata() != null) existing.setMetadata(update.getMetadata());
        existing.setUpdatedAt(System.currentTimeMillis());

        String sql = "UPDATE environments SET name=?, mode=?, agent_ids_json=?, " +
                "variables_json=?, metadata_json=?, updated_at=? WHERE id=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            setEnvironmentUpdateParams(ps, existing);
            ps.setString(7, id);
            ps.executeUpdate();
            return existing;
        } catch (SQLException e) {
            log.error("Failed to update environment {}: {}", id, e.getMessage());
            return null;
        }
    }

    @Override
    public boolean deleteEnvironment(String id) {
        String sql = "DELETE FROM environments WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("Failed to delete environment {}: {}", id, e.getMessage());
            return false;
        }
    }

    private Environment mapEnvironment(ResultSet rs) throws SQLException {
        Environment env = new Environment();
        env.setId(rs.getString("id"));
        env.setName(rs.getString("name"));
        env.setMode(EnvironmentMode.fromValue(rs.getString("mode")));
        env.setCreatedAt(rs.getLong("created_at"));
        env.setUpdatedAt(rs.getLong("updated_at"));
        try {
            String agentIdsJson = rs.getString("agent_ids_json");
            if (agentIdsJson != null && !agentIdsJson.isEmpty()) {
                env.setAgentIds(mapper.readValue(agentIdsJson, new TypeReference<List<String>>() {}));
            }
            String varsJson = rs.getString("variables_json");
            if (varsJson != null && !varsJson.isEmpty()) {
                env.setVariables(mapper.readValue(varsJson, new TypeReference<Map<String, String>>() {}));
            }
            String metaJson = rs.getString("metadata_json");
            if (metaJson != null && !metaJson.isEmpty()) {
                env.setMetadata(mapper.readValue(metaJson, new TypeReference<Map<String, String>>() {}));
            }
        } catch (Exception e) {
            log.warn("Failed to deserialize environment fields: {}", e.getMessage());
        }
        return env;
    }

    private void setEnvironmentParams(PreparedStatement ps, Environment env, int offset) throws SQLException {
        try {
            ps.setString(offset, env.getName());
            ps.setString(offset + 1, env.getMode().getValue());
            ps.setString(offset + 2, env.getAgentIds() != null ? mapper.writeValueAsString(env.getAgentIds()) : null);
            ps.setString(offset + 3, env.getVariables() != null ? mapper.writeValueAsString(env.getVariables()) : null);
            ps.setString(offset + 4, env.getMetadata() != null ? mapper.writeValueAsString(env.getMetadata()) : null);
            ps.setLong(offset + 5, env.getCreatedAt());
            ps.setLong(offset + 6, env.getUpdatedAt());
        } catch (Exception e) {
            log.error("Failed to serialize environment fields: {}", e.getMessage());
        }
    }

    private void setEnvironmentUpdateParams(PreparedStatement ps, Environment env) throws SQLException {
        try {
            ps.setString(1, env.getName());
            ps.setString(2, env.getMode().getValue());
            ps.setString(3, env.getAgentIds() != null ? mapper.writeValueAsString(env.getAgentIds()) : null);
            ps.setString(4, env.getVariables() != null ? mapper.writeValueAsString(env.getVariables()) : null);
            ps.setString(5, env.getMetadata() != null ? mapper.writeValueAsString(env.getMetadata()) : null);
            ps.setLong(6, env.getUpdatedAt());
        } catch (Exception e) {
            log.error("Failed to serialize environment fields: {}", e.getMessage());
        }
    }

    // ==================== Scene Set CRUD ====================

    @Override
    public List<SceneSet> listScenes() {
        String sql = "SELECT * FROM scene_sets";
        List<SceneSet> result = new ArrayList<SceneSet>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(mapSceneSet(rs));
            }
        } catch (SQLException e) {
            log.error("Failed to list scenes: {}", e.getMessage());
        }
        return result;
    }

    @Override
    public SceneSet getScene(String id) {
        String sql = "SELECT * FROM scene_sets WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapSceneSet(rs);
            }
        } catch (SQLException e) {
            log.error("Failed to get scene {}: {}", id, e.getMessage());
        }
        return null;
    }

    @Override
    public SceneSet createScene(SceneSet scene) {
        if (scene.getId() == null || scene.getId().isEmpty()) {
            scene.setId(IdGenerator.uuid());
        }
        long now = System.currentTimeMillis();
        scene.setCreatedAt(now);
        scene.setUpdatedAt(now);

        String sql = "INSERT INTO scene_sets (id, name, description, item_ids_json, " +
                "active, tags_json, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, scene.getId());
            setSceneSetInsertParams(ps, scene);
            ps.executeUpdate();
            return scene;
        } catch (SQLException e) {
            log.error("Failed to create scene: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public SceneSet updateScene(String id, SceneSet update) {
        SceneSet existing = getScene(id);
        if (existing == null) return null;

        if (update.getName() != null) existing.setName(update.getName());
        if (update.getDescription() != null) existing.setDescription(update.getDescription());
        if (update.getItemIds() != null) existing.setItemIds(update.getItemIds());
        existing.setActive(update.isActive());
        existing.setUpdatedAt(System.currentTimeMillis());

        String sql = "UPDATE scene_sets SET name=?, description=?, item_ids_json=?, " +
                "active=?, tags_json=?, updated_at=? WHERE id=?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            setSceneSetParams(ps, existing);
            ps.setString(7, id);
            ps.executeUpdate();
            return existing;
        } catch (SQLException e) {
            log.error("Failed to update scene {}: {}", id, e.getMessage());
            return null;
        }
    }

    @Override
    public boolean deleteScene(String id) {
        String sql = "DELETE FROM scene_sets WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("Failed to delete scene {}: {}", id, e.getMessage());
            return false;
        }
    }

    private SceneSet mapSceneSet(ResultSet rs) throws SQLException {
        SceneSet s = new SceneSet();
        s.setId(rs.getString("id"));
        s.setName(rs.getString("name"));
        s.setDescription(rs.getString("description"));
        s.setActive(rs.getBoolean("active"));
        s.setCreatedAt(rs.getLong("created_at"));
        s.setUpdatedAt(rs.getLong("updated_at"));
        try {
            String itemIdsJson = rs.getString("item_ids_json");
            if (itemIdsJson != null && !itemIdsJson.isEmpty()) {
                s.setItemIds(mapper.readValue(itemIdsJson, new TypeReference<List<String>>() {}));
            }
            String tagsJson = rs.getString("tags_json");
            if (tagsJson != null && !tagsJson.isEmpty()) {
                s.setTags(mapper.readValue(tagsJson, new TypeReference<List<String>>() {}));
            }
        } catch (Exception e) {
            log.warn("Failed to deserialize scene set fields: {}", e.getMessage());
        }
        return s;
    }

    private void setSceneSetInsertParams(PreparedStatement ps, SceneSet s) throws SQLException {
        try {
            ps.setString(2, s.getName());
            ps.setString(3, s.getDescription());
            ps.setString(4, s.getItemIds() != null ? mapper.writeValueAsString(s.getItemIds()) : null);
            ps.setBoolean(5, s.isActive());
            ps.setString(6, s.getTags() != null ? mapper.writeValueAsString(s.getTags()) : null);
            ps.setLong(7, s.getCreatedAt());
            ps.setLong(8, s.getUpdatedAt());
        } catch (Exception e) {
            log.error("Failed to serialize scene set fields: {}", e.getMessage());
        }
    }

    private void setSceneSetParams(PreparedStatement ps, SceneSet s) throws SQLException {
        try {
            ps.setString(1, s.getName());
            ps.setString(2, s.getDescription());
            ps.setString(3, s.getItemIds() != null ? mapper.writeValueAsString(s.getItemIds()) : null);
            ps.setBoolean(4, s.isActive());
            ps.setString(5, s.getTags() != null ? mapper.writeValueAsString(s.getTags()) : null);
            ps.setLong(6, s.getUpdatedAt());
        } catch (Exception e) {
            log.error("Failed to serialize scene set fields: {}", e.getMessage());
        }
    }

    // ==================== Rule Set CRUD ====================

    @Override
    public List<RuleSet> listRuleSets() {
        String sql = "SELECT * FROM rule_sets";
        List<RuleSet> result = new ArrayList<RuleSet>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(mapRuleSet(rs));
            }
        } catch (SQLException e) {
            log.error("Failed to list rule sets: {}", e.getMessage());
        }
        return result;
    }

    @Override
    public RuleSet createRuleSet(RuleSet ruleSet) {
        if (ruleSet.getId() == null || ruleSet.getId().isEmpty()) {
            ruleSet.setId(IdGenerator.uuid());
        }
        long now = System.currentTimeMillis();
        ruleSet.setCreatedAt(now);
        ruleSet.setUpdatedAt(now);

        String sql = "INSERT INTO rule_sets (id, name, description, rule_ids_json, " +
                "enabled, tags_json, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, ruleSet.getId());
            setRuleSetInsertParams(ps, ruleSet);
            ps.executeUpdate();
            return ruleSet;
        } catch (SQLException e) {
            log.error("Failed to create rule set: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public boolean deleteRuleSet(String id) {
        String sql = "DELETE FROM rule_sets WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("Failed to delete rule set {}: {}", id, e.getMessage());
            return false;
        }
    }

    private RuleSet mapRuleSet(ResultSet rs) throws SQLException {
        RuleSet rs2 = new RuleSet();
        rs2.setId(rs.getString("id"));
        rs2.setName(rs.getString("name"));
        rs2.setDescription(rs.getString("description"));
        rs2.setEnabled(rs.getBoolean("enabled"));
        rs2.setCreatedAt(rs.getLong("created_at"));
        rs2.setUpdatedAt(rs.getLong("updated_at"));
        try {
            String ruleIdsJson = rs.getString("rule_ids_json");
            if (ruleIdsJson != null && !ruleIdsJson.isEmpty()) {
                rs2.setRuleIds(mapper.readValue(ruleIdsJson, new TypeReference<List<String>>() {}));
            }
            String tagsJson = rs.getString("tags_json");
            if (tagsJson != null && !tagsJson.isEmpty()) {
                rs2.setTags(mapper.readValue(tagsJson, new TypeReference<List<String>>() {}));
            }
        } catch (Exception e) {
            log.warn("Failed to deserialize rule set fields: {}", e.getMessage());
        }
        return rs2;
    }

    private void setRuleSetInsertParams(PreparedStatement ps, RuleSet rs) throws SQLException {
        try {
            ps.setString(2, rs.getName());
            ps.setString(3, rs.getDescription());
            ps.setString(4, rs.getRuleIds() != null ? mapper.writeValueAsString(rs.getRuleIds()) : null);
            ps.setBoolean(5, rs.isEnabled());
            ps.setString(6, rs.getTags() != null ? mapper.writeValueAsString(rs.getTags()) : null);
            ps.setLong(7, rs.getCreatedAt());
            ps.setLong(8, rs.getUpdatedAt());
        } catch (Exception e) {
            log.error("Failed to serialize rule set fields: {}", e.getMessage());
        }
    }

    private void setRuleSetParams(PreparedStatement ps, RuleSet rs) throws SQLException {
        try {
            ps.setString(1, rs.getName());
            ps.setString(2, rs.getDescription());
            ps.setString(3, rs.getRuleIds() != null ? mapper.writeValueAsString(rs.getRuleIds()) : null);
            ps.setBoolean(4, rs.isEnabled());
            ps.setString(5, rs.getTags() != null ? mapper.writeValueAsString(rs.getTags()) : null);
            ps.setLong(6, rs.getUpdatedAt());
        } catch (Exception e) {
            log.error("Failed to serialize rule set fields: {}", e.getMessage());
        }
    }

    // ==================== Recording ====================

    @Override
    public List<RecordingEntry> listRecordings(String ruleId, int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM recordings");
        boolean filterByRuleId = ruleId != null && !ruleId.isEmpty();
        if (filterByRuleId) {
            sql.append(" WHERE rule_id = ?");
        }
        sql.append(" ORDER BY recorded_at DESC LIMIT ?");
        List<RecordingEntry> result = new ArrayList<RecordingEntry>();
        try (PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            if (filterByRuleId) {
                ps.setString(idx++, ruleId);
            }
            ps.setInt(idx, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(mapRecording(rs));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to list recordings: {}", e.getMessage());
        }
        return result;
    }

    @Override
    public void addRecording(RecordingEntry recording) {
        if (recording.getId() == null || recording.getId().isEmpty()) {
            recording.setId(IdGenerator.uuid());
        }
        recording.setRecordedAt(System.currentTimeMillis());
        insertRecording(recording);
    }

    @Override
    public void addRecordings(List<RecordingEntry> batch) {
        for (RecordingEntry r : batch) {
            addRecording(r);
        }
    }

    @Override
    public boolean deleteRecording(String id) {
        String sql = "DELETE FROM recordings WHERE id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("Failed to delete recording {}: {}", id, e.getMessage());
            return false;
        }
    }

    private void insertRecording(RecordingEntry r) {
        String sql = "INSERT INTO recordings (id, rule_id, environment_id, agent_id, protocol, " +
                "host, port, service_name, method, path, request_headers_json, request_body, " +
                "response_status_code, response_headers_json, response_body, response_time_ms, " +
                "recorded_at, tags_json) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, r.getId());
            ps.setString(2, r.getRuleId());
            ps.setString(3, r.getEnvironmentId());
            ps.setString(4, r.getAgentId());
            ps.setString(5, r.getProtocol());
            ps.setString(6, r.getHost());
            ps.setInt(7, r.getPort());
            ps.setString(8, r.getServiceName());
            ps.setString(9, r.getMethod());
            ps.setString(10, r.getPath());
            ps.setString(11, r.getRequestHeaders() != null ? mapper.writeValueAsString(r.getRequestHeaders()) : null);
            ps.setString(12, r.getRequestBody());
            ps.setInt(13, r.getResponseStatusCode());
            ps.setString(14, r.getResponseHeaders() != null ? mapper.writeValueAsString(r.getResponseHeaders()) : null);
            ps.setString(15, r.getResponseBody());
            ps.setLong(16, r.getResponseTimeMs());
            ps.setLong(17, r.getRecordedAt());
            ps.setString(18, r.getTags() != null ? mapper.writeValueAsString(r.getTags()) : null);
            ps.executeUpdate();
        } catch (Exception e) {
            log.error("Failed to insert recording: {}", e.getMessage());
        }
    }

    private RecordingEntry mapRecording(ResultSet rs) throws SQLException {
        RecordingEntry r = new RecordingEntry();
        r.setId(rs.getString("id"));
        r.setRuleId(rs.getString("rule_id"));
        r.setEnvironmentId(rs.getString("environment_id"));
        r.setAgentId(rs.getString("agent_id"));
        r.setProtocol(rs.getString("protocol"));
        r.setHost(rs.getString("host"));
        r.setPort(rs.getInt("port"));
        r.setServiceName(rs.getString("service_name"));
        r.setMethod(rs.getString("method"));
        r.setPath(rs.getString("path"));
        r.setRequestBody(rs.getString("request_body"));
        r.setResponseStatusCode(rs.getInt("response_status_code"));
        r.setResponseBody(rs.getString("response_body"));
        r.setResponseTimeMs(rs.getLong("response_time_ms"));
        r.setRecordedAt(rs.getLong("recorded_at"));
        try {
            String reqHeadersJson = rs.getString("request_headers_json");
            if (reqHeadersJson != null && !reqHeadersJson.isEmpty()) {
                r.setRequestHeaders(mapper.readValue(reqHeadersJson, new TypeReference<Map<String, String>>() {}));
            }
            String respHeadersJson = rs.getString("response_headers_json");
            if (respHeadersJson != null && !respHeadersJson.isEmpty()) {
                r.setResponseHeaders(mapper.readValue(respHeadersJson, new TypeReference<Map<String, String>>() {}));
            }
            String tagsJson = rs.getString("tags_json");
            if (tagsJson != null && !tagsJson.isEmpty()) {
                r.setTags(mapper.readValue(tagsJson, new TypeReference<Map<String, String>>() {}));
            }
        } catch (Exception e) {
            log.warn("Failed to deserialize recording fields: {}", e.getMessage());
        }
        return r;
    }

    // ==================== Agent Management ====================

    @Override
    public AgentRegistration registerAgent(String agentId, String environment, String hostname,
                                            String version, List<String> protocols) {
        AgentRegistration reg = new AgentRegistration();
        reg.agentId = agentId;
        reg.environment = environment;
        reg.hostname = hostname;
        reg.version = version;
        reg.protocols = protocols;
        reg.registeredAt = System.currentTimeMillis();
        reg.lastHeartbeat = System.currentTimeMillis();

        // Upsert: INSERT ... ON DUPLICATE KEY UPDATE (H2 uses MERGE)
        String sql = "MERGE INTO agents (agent_id, environment, hostname, version, " +
                "protocols_json, registered_at, last_heartbeat) KEY(agent_id) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, agentId);
            ps.setString(2, environment);
            ps.setString(3, hostname);
            ps.setString(4, version);
            ps.setString(5, protocols != null ? mapper.writeValueAsString(protocols) : null);
            ps.setLong(6, reg.registeredAt);
            ps.setLong(7, reg.lastHeartbeat);
            ps.executeUpdate();
        } catch (Exception e) {
            log.error("Failed to register agent {}: {}", agentId, e.getMessage());
        }

        // Associate agent with environment
        Environment env = getEnvironmentByName(environment);
        if (env != null && !env.getAgentIds().contains(agentId)) {
            env.getAgentIds().add(agentId);
            updateEnvironment(env.getId(), env);
        }

        return reg;
    }

    @Override
    public void agentHeartbeat(String agentId) {
        String sql = "UPDATE agents SET last_heartbeat = ? WHERE agent_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, agentId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to update heartbeat for agent {}: {}", agentId, e.getMessage());
        }
    }

    @Override
    public List<AgentRegistration> listAgents() {
        String sql = "SELECT * FROM agents";
        List<AgentRegistration> result = new ArrayList<AgentRegistration>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(mapAgent(rs));
            }
        } catch (SQLException e) {
            log.error("Failed to list agents: {}", e.getMessage());
        }
        return result;
    }

    @Override
    public List<AgentRegistration> getAgentsForEnvironment(String envName) {
        String sql = "SELECT * FROM agents WHERE environment = ?";
        List<AgentRegistration> result = new ArrayList<AgentRegistration>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, envName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(mapAgent(rs));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to get agents for environment {}: {}", envName, e.getMessage());
        }
        return result;
    }

    private AgentRegistration mapAgent(ResultSet rs) throws SQLException {
        AgentRegistration reg = new AgentRegistration();
        reg.agentId = rs.getString("agent_id");
        reg.environment = rs.getString("environment");
        reg.hostname = rs.getString("hostname");
        reg.version = rs.getString("version");
        reg.registeredAt = rs.getLong("registered_at");
        reg.lastHeartbeat = rs.getLong("last_heartbeat");
        try {
            String protJson = rs.getString("protocols_json");
            if (protJson != null && !protJson.isEmpty()) {
                reg.protocols = mapper.readValue(protJson, new TypeReference<List<String>>() {});
            }
        } catch (Exception e) {
            log.warn("Failed to deserialize agent protocols: {}", e.getMessage());
        }
        return reg;
    }
}
