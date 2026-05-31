package com.baafoo.server.storage;

import com.baafoo.core.config.ServerConfig;
import com.baafoo.core.model.*;
import com.baafoo.core.util.IdGenerator;
import com.baafoo.server.mapper.*;
import com.baafoo.server.mapper.entity.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.io.File;
import java.io.InputStream;
import java.sql.*;
import java.util.*;
import java.util.stream.Collectors;

public class MybatisStorageService implements StorageService {

    private static final Logger log = LoggerFactory.getLogger(MybatisStorageService.class);

    private final ServerConfig config;
    private final ObjectMapper objectMapper;
    private SqlSessionFactory sqlSessionFactory;

    public MybatisStorageService(ServerConfig config) {
        this.config = config;
        this.objectMapper = new ObjectMapper();
        this.objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
    }

    private SqlSessionFactory buildSqlSessionFactory(DataSource dataSource) {
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("mybatis-config.xml")) {
            if (is == null) {
                throw new RuntimeException("mybatis-config.xml not found in classpath");
            }
            org.apache.ibatis.session.Configuration configuration;
            SqlSessionFactoryBuilder builder = new SqlSessionFactoryBuilder();
            configuration = builder.build(is).getConfiguration();
            org.apache.ibatis.mapping.Environment mybatisEnv = new org.apache.ibatis.mapping.Environment("baafoo", new JdbcTransactionFactory(), dataSource);
            configuration.setEnvironment(mybatisEnv);
            return new SqlSessionFactoryBuilder().build(configuration);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build MyBatis SqlSessionFactory", e);
        }
    }

    @Override
    public void init() throws Exception {
        String dbType = config.getDatabase().getType();
        DataSource dataSource;

        if ("postgresql".equalsIgnoreCase(dbType)) {
            dataSource = createPostgresqlDataSource();
        } else {
            dataSource = createH2DataSource();
        }

        try (Connection conn = dataSource.getConnection()) {
            createTablesIfNotExist(conn, dbType);
        }

        this.sqlSessionFactory = buildSqlSessionFactory(dataSource);
        log.info("MyBatis + {} storage initialized", dbType);
    }

    private DataSource createH2DataSource() {
        String dbPath = config.getDataDir() + "/baafoo";
        File dbDir = new File(config.getDataDir());
        if (!dbDir.exists()) {
            dbDir.mkdirs();
        }
        String jdbcUrl = "jdbc:h2:file:" + dbPath + ";DB_CLOSE_DELAY=-1";
        return org.h2.jdbcx.JdbcConnectionPool.create(jdbcUrl, "sa", "");
    }

    private DataSource createPostgresqlDataSource() {
        ServerConfig.DatabaseConfig dbConfig = config.getDatabase();
        HikariDataSource ds = new HikariDataSource();
        ds.setDriverClassName("org.postgresql.Driver");
        ds.setJdbcUrl(dbConfig.getUrl());
        ds.setUsername(dbConfig.getUsername());
        ds.setPassword(dbConfig.getPassword());
        ds.setMaximumPoolSize(10);
        ds.setMinimumIdle(2);
        return ds;
    }

    @Override
    public void shutdown() {
        log.info("MyBatis storage shutdown");
    }

    private void createTablesIfNotExist(Connection conn, String dbType) throws SQLException {
        String autoIncrement = "postgresql".equalsIgnoreCase(dbType) ? "BIGSERIAL" : "BIGINT AUTO_INCREMENT";
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
                stmt.executeUpdate("ALTER TABLE rules ADD COLUMN IF NOT EXISTS environments_json TEXT");
            } catch (SQLException e) {
            }

            stmt.executeUpdate(
                "CREATE TABLE IF NOT EXISTS rule_history (" +
                "  id " + autoIncrement + " PRIMARY KEY," +
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
                stmt.executeUpdate("ALTER TABLE scene_sets ADD COLUMN IF NOT EXISTS environments_json TEXT");
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
        }
        log.info("Database tables verified/created");
    }

    @Override
    public List<Rule> listRules() {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            RuleMapper mapper = session.getMapper(RuleMapper.class);
            return mapper.selectAll().stream()
                    .map(this::entityToRule)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Failed to list rules: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    @Override
    public Rule getRule(String id) {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            RuleMapper mapper = session.getMapper(RuleMapper.class);
            RuleEntity entity = mapper.selectById(id);
            return entity != null ? entityToRule(entity) : null;
        } catch (Exception e) {
            log.error("Failed to get rule {}: {}", id, e.getMessage());
            return null;
        }
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

        try (SqlSession session = sqlSessionFactory.openSession()) {
            RuleMapper mapper = session.getMapper(RuleMapper.class);
            mapper.insert(ruleToEntity(rule));
            session.commit();
            return rule;
        } catch (Exception e) {
            log.error("Failed to create rule: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public Rule updateRule(String id, Rule update) {
        Rule existing = getRule(id);
        if (existing == null) return null;

        saveVersion(id, existing);

        if (update.getName() != null) existing.setName(update.getName());
        if (update.getProtocol() != null) existing.setProtocol(update.getProtocol());
        if (update.getServiceName() != null) existing.setServiceName(update.getServiceName());
        if (update.getHost() != null) existing.setHost(update.getHost());
        if (update.getPort() != null) existing.setPort(update.getPort());
        if (update.getConditions() != null && !update.getConditions().isEmpty())
            existing.setConditions(update.getConditions());
        if (update.getResponses() != null && !update.getResponses().isEmpty())
            existing.setResponses(update.getResponses());
        existing.setEnabled(update.isEnabled());
        existing.setPriority(update.getPriority());
        if (update.getTags() != null) existing.setTags(update.getTags());
        if (update.getEnvironments() != null) existing.setEnvironments(update.getEnvironments());
        existing.setVersion(existing.getVersion() + 1);
        existing.setUpdatedAt(System.currentTimeMillis());

        try (SqlSession session = sqlSessionFactory.openSession()) {
            RuleMapper mapper = session.getMapper(RuleMapper.class);
            mapper.update(ruleToEntity(existing));
            session.commit();
            return existing;
        } catch (Exception e) {
            log.error("Failed to update rule {}: {}", id, e.getMessage());
            return null;
        }
    }

    @Override
    public boolean deleteRule(String id) {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            RuleMapper mapper = session.getMapper(RuleMapper.class);
            mapper.deleteHistoryByRuleId(id);
            int result = mapper.deleteById(id);
            session.commit();
            return result > 0;
        } catch (Exception e) {
            log.error("Failed to delete rule {}: {}", id, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean undoRule(String id) {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            RuleMapper mapper = session.getMapper(RuleMapper.class);
            String snapshot = mapper.selectLatestHistory(id);
            if (snapshot == null) return false;

            Rule previous = objectMapper.readValue(snapshot, Rule.class);
            mapper.update(ruleToEntity(previous));
            session.commit();
            return true;
        } catch (Exception e) {
            log.error("Failed to undo rule {}: {}", id, e.getMessage());
            return false;
        }
    }

    private void saveVersion(String ruleId, Rule previous) {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            RuleMapper mapper = session.getMapper(RuleMapper.class);
            String snapshot = objectMapper.writeValueAsString(previous);
            mapper.insertHistory(ruleId, snapshot, System.currentTimeMillis());
            mapper.deleteOldHistory(ruleId, 10);
            session.commit();
        } catch (Exception e) {
            log.error("Failed to save rule version: {}", e.getMessage());
        }
    }

    private Rule entityToRule(RuleEntity e) {
        Rule r = new Rule();
        r.setId(e.getId());
        r.setName(e.getName());
        r.setProtocol(e.getProtocol());
        r.setServiceName(e.getServiceName());
        r.setHost(e.getHost());
        r.setPort(e.getPort());
        r.setEnabled(e.getEnabled());
        r.setPriority(e.getPriority());
        r.setVersion(e.getVersion());
        r.setCreatedAt(e.getCreatedAt());
        r.setUpdatedAt(e.getUpdatedAt());
        try {
            String condJson = e.getConditionsJson();
            if (condJson != null && !condJson.isEmpty()) {
                r.setConditions(objectMapper.readValue(condJson, new TypeReference<List<MatchCondition>>() {}));
            }
            String respJson = e.getResponsesJson();
            if (respJson != null && !respJson.isEmpty()) {
                r.setResponses(objectMapper.readValue(respJson, new TypeReference<List<ResponseEntry>>() {}));
            }
            String tagsJson = e.getTagsJson();
            if (tagsJson != null && !tagsJson.isEmpty()) {
                r.setTags(objectMapper.readValue(tagsJson, new TypeReference<List<String>>() {}));
            }
            String envJson = e.getEnvironmentsJson();
            if (envJson != null && !envJson.isEmpty()) {
                r.setEnvironments(objectMapper.readValue(envJson, new TypeReference<List<String>>() {}));
            }
        } catch (Exception ex) {
            log.warn("Failed to deserialize rule fields: {}", ex.getMessage());
        }
        return r;
    }

    private RuleEntity ruleToEntity(Rule r) {
        RuleEntity e = new RuleEntity();
        e.setId(r.getId());
        e.setName(r.getName());
        e.setProtocol(r.getProtocol());
        e.setServiceName(r.getServiceName());
        e.setHost(r.getHost());
        e.setPort(r.getPort());
        e.setEnabled(r.isEnabled());
        e.setPriority(r.getPriority());
        e.setVersion(r.getVersion());
        e.setCreatedAt(r.getCreatedAt());
        e.setUpdatedAt(r.getUpdatedAt());
        try {
            e.setConditionsJson(r.getConditions() != null ? objectMapper.writeValueAsString(r.getConditions()) : null);
            e.setResponsesJson(r.getResponses() != null ? objectMapper.writeValueAsString(r.getResponses()) : null);
            e.setTagsJson(r.getTags() != null ? objectMapper.writeValueAsString(r.getTags()) : null);
            e.setEnvironmentsJson(r.getEnvironments() != null ? objectMapper.writeValueAsString(r.getEnvironments()) : null);
        } catch (Exception ex) {
            log.error("Failed to serialize rule fields: {}", ex.getMessage());
        }
        return e;
    }

    @Override
    public List<Environment> listEnvironments() {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            EnvironmentMapper mapper = session.getMapper(EnvironmentMapper.class);
            return mapper.selectAll().stream()
                    .map(this::entityToEnvironment)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Failed to list environments: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    @Override
    public Environment getEnvironment(String id) {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            EnvironmentMapper mapper = session.getMapper(EnvironmentMapper.class);
            EnvironmentEntity entity = mapper.selectById(id);
            return entity != null ? entityToEnvironment(entity) : null;
        } catch (Exception e) {
            log.error("Failed to get environment {}: {}", id, e.getMessage());
            return null;
        }
    }

    @Override
    public Environment getEnvironmentByName(String name) {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            EnvironmentMapper mapper = session.getMapper(EnvironmentMapper.class);
            EnvironmentEntity entity = mapper.selectByName(name);
            return entity != null ? entityToEnvironment(entity) : null;
        } catch (Exception e) {
            log.error("Failed to get environment by name {}: {}", name, e.getMessage());
            return null;
        }
    }

    @Override
    public Environment createEnvironment(Environment env) {
        if (env.getId() == null || env.getId().isEmpty()) {
            env.setId(IdGenerator.uuid());
        }
        long now = System.currentTimeMillis();
        env.setCreatedAt(now);
        env.setUpdatedAt(now);

        try (SqlSession session = sqlSessionFactory.openSession()) {
            EnvironmentMapper mapper = session.getMapper(EnvironmentMapper.class);
            mapper.insert(environmentToEntity(env));
            session.commit();
            return env;
        } catch (Exception e) {
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

        try (SqlSession session = sqlSessionFactory.openSession()) {
            EnvironmentMapper mapper = session.getMapper(EnvironmentMapper.class);
            mapper.update(environmentToEntity(existing));
            session.commit();
            return existing;
        } catch (Exception e) {
            log.error("Failed to update environment {}: {}", id, e.getMessage());
            return null;
        }
    }

    @Override
    public boolean deleteEnvironment(String id) {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            EnvironmentMapper mapper = session.getMapper(EnvironmentMapper.class);
            int result = mapper.deleteById(id);
            session.commit();
            return result > 0;
        } catch (Exception e) {
            log.error("Failed to delete environment {}: {}", id, e.getMessage());
            return false;
        }
    }

    private Environment entityToEnvironment(EnvironmentEntity e) {
        Environment env = new Environment();
        env.setId(e.getId());
        env.setName(e.getName());
        env.setMode(EnvironmentMode.fromValue(e.getMode()));
        env.setCreatedAt(e.getCreatedAt());
        env.setUpdatedAt(e.getUpdatedAt());
        try {
            if (e.getAgentIdsJson() != null && !e.getAgentIdsJson().isEmpty()) {
                env.setAgentIds(objectMapper.readValue(e.getAgentIdsJson(), new TypeReference<List<String>>() {}));
            }
            if (e.getVariablesJson() != null && !e.getVariablesJson().isEmpty()) {
                env.setVariables(objectMapper.readValue(e.getVariablesJson(), new TypeReference<Map<String, String>>() {}));
            }
            if (e.getMetadataJson() != null && !e.getMetadataJson().isEmpty()) {
                env.setMetadata(objectMapper.readValue(e.getMetadataJson(), new TypeReference<Map<String, String>>() {}));
            }
        } catch (Exception ex) {
            log.warn("Failed to deserialize environment fields: {}", ex.getMessage());
        }
        return env;
    }

    private EnvironmentEntity environmentToEntity(Environment env) {
        EnvironmentEntity e = new EnvironmentEntity();
        e.setId(env.getId());
        e.setName(env.getName());
        e.setMode(env.getMode().getValue());
        e.setCreatedAt(env.getCreatedAt());
        e.setUpdatedAt(env.getUpdatedAt());
        try {
            e.setAgentIdsJson(env.getAgentIds() != null ? objectMapper.writeValueAsString(env.getAgentIds()) : null);
            e.setVariablesJson(env.getVariables() != null ? objectMapper.writeValueAsString(env.getVariables()) : null);
            e.setMetadataJson(env.getMetadata() != null ? objectMapper.writeValueAsString(env.getMetadata()) : null);
        } catch (Exception ex) {
            log.error("Failed to serialize environment fields: {}", ex.getMessage());
        }
        return e;
    }

    @Override
    public List<SceneSet> listScenes() {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            SceneMapper mapper = session.getMapper(SceneMapper.class);
            return mapper.selectAll().stream()
                    .map(this::entityToScene)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Failed to list scenes: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    @Override
    public SceneSet getScene(String id) {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            SceneMapper mapper = session.getMapper(SceneMapper.class);
            SceneEntity entity = mapper.selectById(id);
            return entity != null ? entityToScene(entity) : null;
        } catch (Exception e) {
            log.error("Failed to get scene {}: {}", id, e.getMessage());
            return null;
        }
    }

    @Override
    public SceneSet createScene(SceneSet scene) {
        if (scene.getId() == null || scene.getId().isEmpty()) {
            scene.setId(IdGenerator.uuid());
        }
        long now = System.currentTimeMillis();
        scene.setCreatedAt(now);
        scene.setUpdatedAt(now);

        try (SqlSession session = sqlSessionFactory.openSession()) {
            SceneMapper mapper = session.getMapper(SceneMapper.class);
            mapper.insert(sceneToEntity(scene));
            session.commit();
            return scene;
        } catch (Exception e) {
            log.error("Failed to create scene: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public SceneSet updateScene(String id, SceneSet update) {
        SceneSet existing = getScene(id);
        if (existing == null) return null;

        List<String> oldEnvironments = existing.getEnvironments() != null
                ? new ArrayList<>(existing.getEnvironments()) : new ArrayList<>();
        List<String> oldItemIds = existing.getItemIds() != null
                ? new ArrayList<>(existing.getItemIds()) : new ArrayList<>();

        if (update.getName() != null) existing.setName(update.getName());
        if (update.getDescription() != null) existing.setDescription(update.getDescription());
        if (update.getItemIds() != null) existing.setItemIds(update.getItemIds());
        if (update.getEnvironments() != null) existing.setEnvironments(update.getEnvironments());
        existing.setActive(update.isActive());
        existing.setUpdatedAt(System.currentTimeMillis());

        try (SqlSession session = sqlSessionFactory.openSession()) {
            SceneMapper mapper = session.getMapper(SceneMapper.class);
            mapper.update(sceneToEntity(existing));
            session.commit();
        } catch (Exception e) {
            log.error("Failed to update scene {}: {}", id, e.getMessage());
            return null;
        }

        syncSceneEnvironmentsToRules(existing, oldEnvironments, oldItemIds);
        return existing;
    }

    private void syncSceneEnvironmentsToRules(SceneSet scene, List<String> oldEnvironments, List<String> oldItemIds) {
        List<String> newEnvironments = scene.getEnvironments() != null ? scene.getEnvironments() : Collections.emptyList();
        List<String> currentItemIds = scene.getItemIds() != null ? scene.getItemIds() : Collections.emptyList();

        Set<String> allRuleIds = new HashSet<>();
        allRuleIds.addAll(oldItemIds);
        allRuleIds.addAll(currentItemIds);

        for (String ruleId : allRuleIds) {
            Rule rule = getRule(ruleId);
            if (rule == null) continue;

            List<String> ruleEnvs = new ArrayList<>(rule.getEnvironments() != null ? rule.getEnvironments() : Collections.emptyList());

            for (String oldEnv : oldEnvironments) {
                if (!newEnvironments.contains(oldEnv)) {
                    boolean stillInherited = isEnvironmentInheritedFromOtherScene(ruleId, oldEnv, scene.getId());
                    if (!stillInherited) {
                        ruleEnvs.remove(oldEnv);
                    }
                }
            }

            for (String newEnv : newEnvironments) {
                if (!ruleEnvs.contains(newEnv)) {
                    ruleEnvs.add(newEnv);
                }
            }

            rule.setEnvironments(ruleEnvs);
            updateRule(ruleId, rule);
        }
    }

    private boolean isEnvironmentInheritedFromOtherScene(String ruleId, String envName, String excludeSceneId) {
        for (SceneSet otherScene : listScenes()) {
            if (otherScene.getId().equals(excludeSceneId)) continue;
            if (!otherScene.isActive()) continue;
            List<String> envs = otherScene.getEnvironments();
            if (envs == null || !envs.contains(envName)) continue;
            List<String> items = otherScene.getItemIds();
            if (items != null && items.contains(ruleId)) return true;
        }
        return false;
    }

    @Override
    public boolean deleteScene(String id) {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            SceneMapper mapper = session.getMapper(SceneMapper.class);
            int result = mapper.deleteById(id);
            session.commit();
            return result > 0;
        } catch (Exception e) {
            log.error("Failed to delete scene {}: {}", id, e.getMessage());
            return false;
        }
    }

    private SceneSet entityToScene(SceneEntity e) {
        SceneSet s = new SceneSet();
        s.setId(e.getId());
        s.setName(e.getName());
        s.setDescription(e.getDescription());
        s.setActive(e.getActive());
        s.setCreatedAt(e.getCreatedAt());
        s.setUpdatedAt(e.getUpdatedAt());
        try {
            if (e.getItemIdsJson() != null && !e.getItemIdsJson().isEmpty()) {
                s.setItemIds(objectMapper.readValue(e.getItemIdsJson(), new TypeReference<List<String>>() {}));
            }
            if (e.getTagsJson() != null && !e.getTagsJson().isEmpty()) {
                s.setTags(objectMapper.readValue(e.getTagsJson(), new TypeReference<List<String>>() {}));
            }
            if (e.getEnvironmentsJson() != null && !e.getEnvironmentsJson().isEmpty()) {
                s.setEnvironments(objectMapper.readValue(e.getEnvironmentsJson(), new TypeReference<List<String>>() {}));
            }
        } catch (Exception ex) {
            log.warn("Failed to deserialize scene set fields: {}", ex.getMessage());
        }
        return s;
    }

    private SceneEntity sceneToEntity(SceneSet s) {
        SceneEntity e = new SceneEntity();
        e.setId(s.getId());
        e.setName(s.getName());
        e.setDescription(s.getDescription());
        e.setActive(s.isActive());
        e.setCreatedAt(s.getCreatedAt());
        e.setUpdatedAt(s.getUpdatedAt());
        try {
            e.setItemIdsJson(s.getItemIds() != null ? objectMapper.writeValueAsString(s.getItemIds()) : null);
            e.setTagsJson(s.getTags() != null ? objectMapper.writeValueAsString(s.getTags()) : null);
            e.setEnvironmentsJson(s.getEnvironments() != null ? objectMapper.writeValueAsString(s.getEnvironments()) : null);
        } catch (Exception ex) {
            log.error("Failed to serialize scene set fields: {}", ex.getMessage());
        }
        return e;
    }

    @Override
    public List<RuleSet> listRuleSets() {
        return new ArrayList<>();
    }

    @Override
    public RuleSet createRuleSet(RuleSet ruleSet) {
        return null;
    }

    @Override
    public boolean deleteRuleSet(String id) {
        return false;
    }

    @Override
    public List<RecordingEntry> listRecordings(String ruleId, int limit) {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            RecordingMapper mapper = session.getMapper(RecordingMapper.class);
            List<RecordingEntity> entities;
            if (ruleId != null && !ruleId.isEmpty()) {
                entities = mapper.selectByRuleId(ruleId, limit);
            } else {
                entities = mapper.selectAll(limit);
            }
            return entities.stream()
                    .map(this::entityToRecording)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Failed to list recordings: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    @Override
    public void addRecording(RecordingEntry recording) {
        if (recording.getId() == null || recording.getId().isEmpty()) {
            recording.setId(IdGenerator.uuid());
        }
        recording.setRecordedAt(System.currentTimeMillis());

        try (SqlSession session = sqlSessionFactory.openSession()) {
            RecordingMapper mapper = session.getMapper(RecordingMapper.class);
            mapper.insert(recordingToEntity(recording));
            mapper.trimRecordings(1000);
            session.commit();
        } catch (Exception e) {
            log.error("Failed to add recording: {}", e.getMessage());
        }
    }

    @Override
    public void addRecordings(List<RecordingEntry> batch) {
        for (RecordingEntry r : batch) {
            addRecording(r);
        }
    }

    @Override
    public boolean deleteRecording(String id) {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            RecordingMapper mapper = session.getMapper(RecordingMapper.class);
            int result = mapper.deleteById(id);
            session.commit();
            return result > 0;
        } catch (Exception e) {
            log.error("Failed to delete recording {}: {}", id, e.getMessage());
            return false;
        }
    }

    private RecordingEntry entityToRecording(RecordingEntity e) {
        RecordingEntry r = new RecordingEntry();
        r.setId(e.getId());
        r.setRuleId(e.getRuleId());
        r.setEnvironmentId(e.getEnvironmentId());
        r.setAgentId(e.getAgentId());
        r.setProtocol(e.getProtocol());
        r.setHost(e.getHost());
        r.setPort(e.getPort());
        r.setServiceName(e.getServiceName());
        r.setMethod(e.getMethod());
        r.setPath(e.getPath());
        r.setRequestBody(e.getRequestBody());
        r.setResponseStatusCode(e.getResponseStatusCode());
        r.setResponseBody(e.getResponseBody());
        r.setResponseTimeMs(e.getResponseTimeMs());
        r.setRecordedAt(e.getRecordedAt());
        try {
            if (e.getRequestHeadersJson() != null && !e.getRequestHeadersJson().isEmpty()) {
                r.setRequestHeaders(objectMapper.readValue(e.getRequestHeadersJson(), new TypeReference<Map<String, String>>() {}));
            }
            if (e.getResponseHeadersJson() != null && !e.getResponseHeadersJson().isEmpty()) {
                r.setResponseHeaders(objectMapper.readValue(e.getResponseHeadersJson(), new TypeReference<Map<String, String>>() {}));
            }
            if (e.getTagsJson() != null && !e.getTagsJson().isEmpty()) {
                r.setTags(objectMapper.readValue(e.getTagsJson(), new TypeReference<Map<String, String>>() {}));
            }
        } catch (Exception ex) {
            log.warn("Failed to deserialize recording fields: {}", ex.getMessage());
        }
        return r;
    }

    private RecordingEntity recordingToEntity(RecordingEntry r) {
        RecordingEntity e = new RecordingEntity();
        e.setId(r.getId());
        e.setRuleId(r.getRuleId());
        e.setEnvironmentId(r.getEnvironmentId());
        e.setAgentId(r.getAgentId());
        e.setProtocol(r.getProtocol());
        e.setHost(r.getHost());
        e.setPort(r.getPort());
        e.setServiceName(r.getServiceName());
        e.setMethod(r.getMethod());
        e.setPath(r.getPath());
        e.setRequestBody(r.getRequestBody());
        e.setResponseStatusCode(r.getResponseStatusCode());
        e.setResponseBody(r.getResponseBody());
        e.setResponseTimeMs(r.getResponseTimeMs());
        e.setRecordedAt(r.getRecordedAt());
        try {
            e.setRequestHeadersJson(r.getRequestHeaders() != null ? objectMapper.writeValueAsString(r.getRequestHeaders()) : null);
            e.setResponseHeadersJson(r.getResponseHeaders() != null ? objectMapper.writeValueAsString(r.getResponseHeaders()) : null);
            e.setTagsJson(r.getTags() != null ? objectMapper.writeValueAsString(r.getTags()) : null);
        } catch (Exception ex) {
            log.error("Failed to serialize recording fields: {}", ex.getMessage());
        }
        return e;
    }

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

        try (SqlSession session = sqlSessionFactory.openSession()) {
            AgentMapper mapper = session.getMapper(AgentMapper.class);
            AgentEntity entity = agentToEntity(reg);
            mapper.merge(entity);
            session.commit();
        } catch (Exception e) {
            log.error("Failed to register agent {}: {}", agentId, e.getMessage());
        }

        Environment env = getEnvironmentByName(environment);
        if (env != null && !env.getAgentIds().contains(agentId)) {
            env.getAgentIds().add(agentId);
            updateEnvironment(env.getId(), env);
        }

        return reg;
    }

    @Override
    public void agentHeartbeat(String agentId) {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            AgentMapper mapper = session.getMapper(AgentMapper.class);
            mapper.updateHeartbeat(agentId, System.currentTimeMillis());
            session.commit();
        } catch (Exception e) {
            log.error("Failed to update heartbeat for agent {}: {}", agentId, e.getMessage());
        }
    }

    @Override
    public List<AgentRegistration> listAgents() {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            AgentMapper mapper = session.getMapper(AgentMapper.class);
            return mapper.selectAll().stream()
                    .map(this::entityToAgent)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Failed to list agents: {}", e.getMessage());
            return new ArrayList<>();
        }
    }

    @Override
    public List<AgentRegistration> getAgentsForEnvironment(String envName) {
        try (SqlSession session = sqlSessionFactory.openSession()) {
            AgentMapper mapper = session.getMapper(AgentMapper.class);
            return mapper.selectByEnvironment(envName).stream()
                    .map(this::entityToAgent)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.error("Failed to get agents for environment {}: {}", envName, e.getMessage());
            return new ArrayList<>();
        }
    }

    private AgentRegistration entityToAgent(AgentEntity e) {
        AgentRegistration reg = new AgentRegistration();
        reg.agentId = e.getAgentId();
        reg.environment = e.getEnvironment();
        reg.hostname = e.getHostname();
        reg.version = e.getVersion();
        reg.registeredAt = e.getRegisteredAt();
        reg.lastHeartbeat = e.getLastHeartbeat();
        try {
            if (e.getProtocolsJson() != null && !e.getProtocolsJson().isEmpty()) {
                reg.protocols = objectMapper.readValue(e.getProtocolsJson(), new TypeReference<List<String>>() {});
            }
        } catch (Exception ex) {
            log.warn("Failed to deserialize agent protocols: {}", ex.getMessage());
        }
        return reg;
    }

    private AgentEntity agentToEntity(AgentRegistration reg) {
        AgentEntity e = new AgentEntity();
        e.setAgentId(reg.agentId);
        e.setEnvironment(reg.environment);
        e.setHostname(reg.hostname);
        e.setVersion(reg.version);
        e.setRegisteredAt(reg.registeredAt);
        e.setLastHeartbeat(reg.lastHeartbeat);
        try {
            e.setProtocolsJson(reg.protocols != null ? objectMapper.writeValueAsString(reg.protocols) : null);
        } catch (Exception ex) {
            log.error("Failed to serialize agent protocols: {}", ex.getMessage());
        }
        return e;
    }

    @Override
    public void associateRulesToEnvironment(String envName, List<String> ruleIds) {
        for (String ruleId : ruleIds) {
            Rule rule = getRule(ruleId);
            if (rule == null) continue;
            List<String> envs = new ArrayList<>(rule.getEnvironments() != null ? rule.getEnvironments() : Collections.emptyList());
            if (!envs.contains(envName)) {
                envs.add(envName);
                rule.setEnvironments(envs);
                updateRule(ruleId, rule);
            }
        }
    }

    @Override
    public void dissociateRulesFromEnvironment(String envName, List<String> ruleIds) {
        for (String ruleId : ruleIds) {
            Rule rule = getRule(ruleId);
            if (rule == null) continue;
            List<String> envs = new ArrayList<>(rule.getEnvironments() != null ? rule.getEnvironments() : Collections.emptyList());
            if (envs.remove(envName)) {
                rule.setEnvironments(envs);
                updateRule(ruleId, rule);
            }
        }
    }
}
