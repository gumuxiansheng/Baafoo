package com.baafoo.server.storage;

import com.baafoo.core.api.PaginatedResult;
import com.baafoo.core.config.ServerConfig;
import com.baafoo.core.model.*;
import com.baafoo.core.util.IdGenerator;
import com.baafoo.server.storage.dialect.DatabaseDialect;
import com.baafoo.server.storage.dialect.DdlBuilder;
import com.baafoo.server.storage.mapper.*;
import com.baafoo.server.storage.mybatis.EnvironmentModeTypeHandler;
import com.baafoo.server.storage.mybatis.JsonTypeHandler;
import com.baafoo.server.storage.mybatis.MatchConditionListHandler;
import com.baafoo.server.storage.mybatis.ResponseEntryListHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.mapping.DatabaseIdProvider;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.util.*;

/**
 * JDBC-based storage service that supports multiple database dialects (H2, PostgreSQL).
 * Uses MyBatis for data access, abstracting SQL dialect differences via mapper XML files.
 */
public class JdbcStorageService implements StorageService {

    private static final Logger log = LoggerFactory.getLogger(JdbcStorageService.class);

    private final ServerConfig config;
    private final ObjectMapper mapper;
    private final DatabaseDialect dialect;
    private HikariDataSource dataSource;
    private SqlSessionFactory sqlSessionFactory;

    // --- Local caches for high-frequency reads ---
    private volatile List<Rule> rulesCache;
    private volatile long rulesCacheTime;
    private volatile List<Environment> environmentsCache;
    private volatile long environmentsCacheTime;
    private volatile List<AgentRegistration> agentsCache;
    private volatile long agentsCacheTime;
    private static final long CACHE_TTL_MS = 2000; // 2 seconds
    private static final long AGENTS_CACHE_TTL_MS = 5000; // 5 seconds (heartbeat frequent)

    public JdbcStorageService(ServerConfig config) {
        this.config = config;
        this.mapper = new ObjectMapper();
        this.mapper.enable(SerializationFeature.INDENT_OUTPUT);
        ServerConfig.DatabaseConfig dbConfig = config.getDatabase();
        this.dialect = DatabaseDialect.fromConfig(dbConfig != null ? dbConfig.getType() : null);
    }

    @Override
    public void init() throws Exception {
        ServerConfig.DatabaseConfig dbConfig = config.getDatabase();
        if (dbConfig == null) {
            dbConfig = new ServerConfig.DatabaseConfig();
        }

        // Build JDBC URL
        String jdbcUrl = dbConfig.getUrl();
        if (jdbcUrl == null || jdbcUrl.isEmpty()) {
            String dataDir = config.getDataDir();
            java.io.File dbDir = new java.io.File(dataDir);
            if (!dbDir.exists()) {
                dbDir.mkdirs();
            }
            jdbcUrl = dialect.buildDefaultJdbcUrl(dataDir);
        }

        // Configure HikariCP
        HikariConfig hkConfig = new HikariConfig();
        hkConfig.setJdbcUrl(jdbcUrl);
        hkConfig.setDriverClassName(dialect.getDriverClassName());
        hkConfig.setUsername(dbConfig.getUsername());
        hkConfig.setPassword(dbConfig.getPassword());
        hkConfig.setMaximumPoolSize(10);
        hkConfig.setMinimumIdle(2);
        hkConfig.setIdleTimeout(30000);
        hkConfig.setConnectionTimeout(10000);
        hkConfig.setAutoCommit(true);
        hkConfig.setPoolName("baafoo-" + dialect.getConfigValue() + "-pool");

        dataSource = new HikariDataSource(hkConfig);

        // Create tables using dialect-specific DDL
        try (java.sql.Connection conn = dataSource.getConnection()) {
            new DdlBuilder(dialect).createTablesIfNotExist(conn);
        }

        // Initialize MyBatis
        sqlSessionFactory = buildSqlSessionFactory(dataSource);

        log.info("{} storage initialized with HikariCP pool: {}", dialect.getConfigValue().toUpperCase(), jdbcUrl);
    }

    private SqlSessionFactory buildSqlSessionFactory(DataSource ds) {
        org.apache.ibatis.mapping.Environment environment = new org.apache.ibatis.mapping.Environment("baafoo", new JdbcTransactionFactory(), ds);

        Configuration configuration = new Configuration(environment);
        configuration.setMapUnderscoreToCamelCase(false); // We handle mapping explicitly

        // Register type handlers
        configuration.getTypeHandlerRegistry().register(JsonTypeHandler.class);
        configuration.getTypeHandlerRegistry().register(MatchConditionListHandler.class);
        configuration.getTypeHandlerRegistry().register(ResponseEntryListHandler.class);
        configuration.getTypeHandlerRegistry().register(EnvironmentModeTypeHandler.class);

        // Set database ID provider
        DatabaseIdProvider databaseIdProvider = new org.apache.ibatis.mapping.VendorDatabaseIdProvider();
        Properties props = new Properties();
        props.setProperty("H2", "h2");
        props.setProperty("PostgreSQL", "postgresql");
        databaseIdProvider.setProperties(props);
        try {
            configuration.setDatabaseId(databaseIdProvider.getDatabaseId(ds));
        } catch (java.sql.SQLException e) {
            log.warn("Failed to determine database ID: {}", e.getMessage());
            configuration.setDatabaseId(dialect.getDatabaseId());
        }

        // Load mapper XML files
        String[] mapperXmls = {
            "mapper/RuleMapper.xml",
            "mapper/EnvironmentMapper.xml",
            "mapper/SceneMapper.xml",
            "mapper/RecordingMapper.xml",
            "mapper/AgentMapper.xml",
            "mapper/UserMapper.xml"
        };
        for (String mapperXml : mapperXmls) {
            try {
                org.apache.ibatis.io.Resources.getResourceAsReader(mapperXml);
                org.apache.ibatis.builder.xml.XMLMapperBuilder xmlMapperBuilder =
                        new org.apache.ibatis.builder.xml.XMLMapperBuilder(
                                org.apache.ibatis.io.Resources.getResourceAsReader(mapperXml),
                                configuration, mapperXml, configuration.getSqlFragments());
                xmlMapperBuilder.parse();
            } catch (Exception e) {
                log.error("Failed to load mapper XML: {}", mapperXml, e);
            }
        }

        return new SqlSessionFactoryBuilder().build(configuration);
    }

    @Override
    public void shutdown() {
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
            log.info("{} storage connection pool closed", dialect.getConfigValue().toUpperCase());
        }
    }

    // --- Helper: open a new SqlSession with auto-commit ---

    private SqlSession openSession() {
        return sqlSessionFactory.openSession(true);
    }

    private void invalidateRulesCache() {
        rulesCache = null;
        rulesCacheTime = 0;
    }

    @Override
    public List<Rule> listRules() {
        long now = System.currentTimeMillis();
        if (rulesCache != null && (now - rulesCacheTime) < CACHE_TTL_MS) {
            return rulesCache;
        }
        try (SqlSession session = openSession()) {
            List<Rule> result = session.getMapper(RuleMapper.class).listRules();
            rulesCache = result;
            rulesCacheTime = System.currentTimeMillis();
            return result;
        }
    }

    @Override
    public PaginatedResult<Rule> listRulesPaged(String protocol, String keyword, int page, int size) {
        try (SqlSession session = openSession()) {
            RuleMapper rm = session.getMapper(RuleMapper.class);
            long total = rm.countRules(protocol, keyword);
            int offset = (page - 1) * size;
            List<Rule> items = rm.listRulesPaged(protocol, keyword, size, offset);
            return new PaginatedResult<>(page, size, total, items);
        }
    }

    @Override
    public Rule getRule(String id) {
        try (SqlSession session = openSession()) {
            return session.getMapper(RuleMapper.class).getRule(id);
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

        try (SqlSession session = openSession()) {
            session.getMapper(RuleMapper.class).createRule(rule);
            invalidateRulesCache();
            return rule;
        } catch (Exception e) {
            log.error("Failed to create rule: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public Rule updateRule(String id, Rule update) {
        try (SqlSession session = openSession()) {
            RuleMapper rm = session.getMapper(RuleMapper.class);
            Rule existing = rm.getRule(id);
            if (existing == null) return null;

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

            // Save version history
            saveVersion(rm, id, existing);

            rm.updateRule(existing);
            invalidateRulesCache();
            return existing;
        } catch (Exception e) {
            log.error("Failed to update rule {}: {}", id, e.getMessage());
            return null;
        }
    }

    private void saveVersion(RuleMapper rm, String ruleId, Rule previous) {
        try {
            String snapshot = mapper.writeValueAsString(previous);
            rm.insertRuleHistory(ruleId, snapshot, System.currentTimeMillis());
            rm.deleteOldRuleHistory(ruleId, 10);
        } catch (Exception e) {
            log.error("Failed to save rule version: {}", e.getMessage());
        }
    }

    @Override
    public boolean deleteRule(String id) {
        try (SqlSession session = openSession()) {
            RuleMapper rm = session.getMapper(RuleMapper.class);
            rm.deleteRuleHistoryByRuleId(id);
            boolean deleted = rm.deleteRule(id) > 0;
            if (deleted) invalidateRulesCache();
            return deleted;
        } catch (Exception e) {
            log.error("Failed to delete rule {}: {}", id, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean undoRule(String id) {
        try (SqlSession session = openSession()) {
            RuleMapper rm = session.getMapper(RuleMapper.class);

            String snapshot = rm.getLatestRuleSnapshot(id);
            if (snapshot == null) return false;

            Rule previous = mapper.readValue(snapshot, Rule.class);
            if (previous == null) return false;

            rm.updateRule(previous);
            invalidateRulesCache();

            Long historyId = rm.getLatestRuleHistoryId(id);
            if (historyId != null && historyId != -1) {
                rm.deleteRuleHistoryById(id, historyId);
            }

            return true;
        } catch (Exception e) {
            log.error("Failed to undo rule {}: {}", id, e.getMessage());
            return false;
        }
    }

    // --- Environment CRUD ---

    private void invalidateEnvironmentsCache() {
        environmentsCache = null;
        environmentsCacheTime = 0;
    }

    @Override
    public List<Environment> listEnvironments() {
        long now = System.currentTimeMillis();
        if (environmentsCache != null && (now - environmentsCacheTime) < CACHE_TTL_MS) {
            return environmentsCache;
        }
        try (SqlSession session = openSession()) {
            List<Environment> result = session.getMapper(EnvironmentMapper.class).listEnvironments();
            environmentsCache = result;
            environmentsCacheTime = System.currentTimeMillis();
            return result;
        }
    }

    @Override
    public Environment getEnvironment(String id) {
        try (SqlSession session = openSession()) {
            return session.getMapper(EnvironmentMapper.class).getEnvironment(id);
        }
    }

    @Override
    public Environment getEnvironmentByName(String name) {
        try (SqlSession session = openSession()) {
            return session.getMapper(EnvironmentMapper.class).getEnvironmentByName(name);
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

        try (SqlSession session = openSession()) {
            session.getMapper(EnvironmentMapper.class).createEnvironment(env);
            invalidateEnvironmentsCache();
            return env;
        } catch (Exception e) {
            log.error("Failed to create environment: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public Environment updateEnvironment(String id, Environment update) {
        try (SqlSession session = openSession()) {
            EnvironmentMapper em = session.getMapper(EnvironmentMapper.class);
            Environment existing = em.getEnvironment(id);
            if (existing == null) return null;

            if (update.getName() != null) existing.setName(update.getName());
            if (update.getMode() != null) existing.setMode(update.getMode());
            if (update.getVariables() != null) existing.setVariables(update.getVariables());
            if (update.getMetadata() != null) existing.setMetadata(update.getMetadata());
            existing.setUpdatedAt(System.currentTimeMillis());

            em.updateEnvironment(existing);
            invalidateEnvironmentsCache();
            return existing;
        } catch (Exception e) {
            log.error("Failed to update environment {}: {}", id, e.getMessage());
            return null;
        }
    }

    @Override
    public boolean deleteEnvironment(String id) {
        try (SqlSession session = openSession()) {
            boolean deleted = session.getMapper(EnvironmentMapper.class).deleteEnvironment(id) > 0;
            if (deleted) invalidateEnvironmentsCache();
            return deleted;
        } catch (Exception e) {
            log.error("Failed to delete environment {}: {}", id, e.getMessage());
            return false;
        }
    }

    // --- Scene Set CRUD ---

    @Override
    public List<SceneSet> listScenes() {
        try (SqlSession session = openSession()) {
            return session.getMapper(SceneMapper.class).listScenes();
        }
    }

    @Override
    public SceneSet getScene(String id) {
        try (SqlSession session = openSession()) {
            return session.getMapper(SceneMapper.class).getScene(id);
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

        try (SqlSession session = openSession()) {
            session.getMapper(SceneMapper.class).createScene(scene);
            return scene;
        } catch (Exception e) {
            log.error("Failed to create scene: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public SceneSet updateScene(String id, SceneSet update) {
        try (SqlSession session = openSession()) {
            SceneMapper sm = session.getMapper(SceneMapper.class);
            RuleMapper rm = session.getMapper(RuleMapper.class);
            SceneSet existing = sm.getScene(id);
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

            sm.updateScene(existing);
            syncSceneEnvironmentsToRules(rm, sm, existing, oldEnvironments, oldItemIds);

            return existing;
        } catch (Exception e) {
            log.error("Failed to update scene {}: {}", id, e.getMessage());
            return null;
        }
    }

    private void syncSceneEnvironmentsToRules(RuleMapper rm, SceneMapper sm,
                                               SceneSet scene, List<String> oldEnvironments, List<String> oldItemIds) {
        List<String> newEnvironments = scene.getEnvironments() != null ? scene.getEnvironments() : Collections.<String>emptyList();
        List<String> currentItemIds = scene.getItemIds() != null ? scene.getItemIds() : Collections.<String>emptyList();

        Set<String> allRuleIds = new HashSet<>();
        allRuleIds.addAll(oldItemIds);
        allRuleIds.addAll(currentItemIds);

        for (String ruleId : allRuleIds) {
            Rule rule = rm.getRule(ruleId);
            if (rule == null) continue;

            List<String> ruleEnvs = new ArrayList<>(rule.getEnvironments() != null ? rule.getEnvironments() : Collections.<String>emptyList());

            for (String oldEnv : oldEnvironments) {
                if (!newEnvironments.contains(oldEnv)) {
                    boolean stillInherited = isEnvironmentInheritedFromOtherScene(sm, ruleId, oldEnv, scene.getId());
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
            rm.updateRule(rule);
            invalidateRulesCache();
            invalidateEnvironmentsCache();
        }
    }

    private boolean isEnvironmentInheritedFromOtherScene(SceneMapper sm, String ruleId, String envName, String excludeSceneId) {
        for (SceneSet otherScene : sm.listScenes()) {
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
        try (SqlSession session = openSession()) {
            return session.getMapper(SceneMapper.class).deleteScene(id) > 0;
        } catch (Exception e) {
            log.error("Failed to delete scene {}: {}", id, e.getMessage());
            return false;
        }
    }

    // --- Rule Set CRUD ---

    @Override
    public List<RuleSet> listRuleSets() {
        try (SqlSession session = openSession()) {
            return session.getMapper(RuleMapper.class).listRuleSets();
        }
    }

    @Override
    public RuleSet createRuleSet(RuleSet ruleSet) {
        if (ruleSet.getId() == null || ruleSet.getId().isEmpty()) {
            ruleSet.setId(IdGenerator.uuid());
        }
        long now = System.currentTimeMillis();
        ruleSet.setCreatedAt(now);
        ruleSet.setUpdatedAt(now);

        try (SqlSession session = openSession()) {
            session.getMapper(RuleMapper.class).createRuleSet(ruleSet);
            return ruleSet;
        } catch (Exception e) {
            log.error("Failed to create rule set: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public boolean deleteRuleSet(String id) {
        try (SqlSession session = openSession()) {
            return session.getMapper(RuleMapper.class).deleteRuleSet(id) > 0;
        } catch (Exception e) {
            log.error("Failed to delete rule set {}: {}", id, e.getMessage());
            return false;
        }
    }

    // --- Recording ---

    @Override
    public List<RecordingEntry> listRecordings(String ruleId, int limit) {
        try (SqlSession session = openSession()) {
            return session.getMapper(RecordingMapper.class).listRecordings(ruleId, limit);
        }
    }

    @Override
    public PaginatedResult<RecordingEntry> listRecordingsPaged(String ruleId, int page, int size) {
        try (SqlSession session = openSession()) {
            RecordingMapper rcm = session.getMapper(RecordingMapper.class);
            long total = rcm.countRecordings(ruleId);
            int offset = (page - 1) * size;
            List<RecordingEntry> items = rcm.listRecordingsPaged(ruleId, size, offset);
            return new PaginatedResult<>(page, size, total, items);
        }
    }

    @Override
    public void addRecording(RecordingEntry recording) {
        if (recording.getId() == null || recording.getId().isEmpty()) {
            recording.setId(IdGenerator.uuid());
        }
        recording.setRecordedAt(System.currentTimeMillis());
        try (SqlSession session = openSession()) {
            RecordingMapper rcm = session.getMapper(RecordingMapper.class);
            rcm.insertRecording(recording);
            rcm.trimRecordings(1000);
        } catch (Exception e) {
            log.error("Failed to add recording: {}", e.getMessage());
        }
    }

    @Override
    public void addRecordings(List<RecordingEntry> batch) {
        try (SqlSession session = sqlSessionFactory.openSession(false)) {
            RecordingMapper rcm = session.getMapper(RecordingMapper.class);
            for (RecordingEntry r : batch) {
                if (r.getId() == null || r.getId().isEmpty()) {
                    r.setId(IdGenerator.uuid());
                }
                r.setRecordedAt(System.currentTimeMillis());
                rcm.insertRecording(r);
            }
            session.commit();
            rcm.trimRecordings(1000);
        } catch (Exception e) {
            log.error("Failed to batch insert recordings: {}", e.getMessage());
        }
    }

    @Override
    public boolean deleteRecording(String id) {
        try (SqlSession session = openSession()) {
            return session.getMapper(RecordingMapper.class).deleteRecording(id) > 0;
        } catch (Exception e) {
            log.error("Failed to delete recording {}: {}", id, e.getMessage());
            return false;
        }
    }

    // --- Agent Management ---

    @Override
    public AgentRegistration registerAgent(String agentId, String environment, String hostname,
                                            String version, List<String> protocols, String agentIp) {
        AgentRegistration reg = new AgentRegistration();
        reg.agentId = agentId;
        reg.environment = environment;
        reg.hostname = hostname;
        reg.version = version;
        reg.protocols = protocols;
        reg.agentIp = agentIp;
        reg.registeredAt = System.currentTimeMillis();
        reg.lastHeartbeat = System.currentTimeMillis();

        try (SqlSession session = openSession()) {
            session.getMapper(AgentMapper.class).upsertAgent(reg);
            invalidateAgentsCache();
        } catch (Exception e) {
            log.error("Failed to register agent {}: {}", agentId, e.getMessage());
        }

        // Update environment's agent list
        try (SqlSession session = openSession()) {
            EnvironmentMapper em = session.getMapper(EnvironmentMapper.class);
            Environment env = em.getEnvironmentByName(environment);
            if (env != null && !env.getAgentIds().contains(agentId)) {
                env.getAgentIds().add(agentId);
                em.updateEnvironment(env);
            }
        } catch (Exception e) {
            log.warn("Failed to update environment agent list: {}", e.getMessage());
        }

        return reg;
    }

    private void invalidateAgentsCache() {
        agentsCache = null;
        agentsCacheTime = 0;
    }

    @Override
    public void agentHeartbeat(String agentId, String agentIp) {
        try (SqlSession session = openSession()) {
            session.getMapper(AgentMapper.class).updateHeartbeat(agentId, System.currentTimeMillis(), agentIp);
            invalidateAgentsCache();
        } catch (Exception e) {
            log.error("Failed to update heartbeat for agent {}: {}", agentId, e.getMessage());
        }
    }

    @Override
    public List<AgentRegistration> listAgents() {
        long now = System.currentTimeMillis();
        if (agentsCache != null && (now - agentsCacheTime) < AGENTS_CACHE_TTL_MS) {
            return agentsCache;
        }
        try (SqlSession session = openSession()) {
            List<AgentRegistration> result = session.getMapper(AgentMapper.class).listAgents();
            agentsCache = result;
            agentsCacheTime = System.currentTimeMillis();
            return result;
        }
    }

    @Override
    public List<AgentRegistration> getAgentsForEnvironment(String envName) {
        try (SqlSession session = openSession()) {
            return session.getMapper(AgentMapper.class).getAgentsForEnvironment(envName);
        }
    }

    // --- Environment-Rule Association ---

    @Override
    public void associateRulesToEnvironment(String envName, List<String> ruleIds) {
        for (String ruleId : ruleIds) {
            Rule rule = getRule(ruleId);
            if (rule == null) continue;
            List<String> envs = new ArrayList<>(rule.getEnvironments() != null ? rule.getEnvironments() : Collections.<String>emptyList());
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
            List<String> envs = new ArrayList<>(rule.getEnvironments() != null ? rule.getEnvironments() : Collections.<String>emptyList());
            if (envs.remove(envName)) {
                rule.setEnvironments(envs);
                updateRule(ruleId, rule);
            }
        }
    }

    // --- User CRUD ---

    @Override
    public List<User> listUsers() {
        try (SqlSession session = openSession()) {
            return session.getMapper(UserMapper.class).listUsers();
        }
    }

    @Override
    public User getUserByUsername(String username) {
        try (SqlSession session = openSession()) {
            return session.getMapper(UserMapper.class).getUserByUsername(username);
        }
    }

    @Override
    public User getUserByApiKey(String apiKey) {
        try (SqlSession session = openSession()) {
            return session.getMapper(UserMapper.class).getUserByApiKey(apiKey);
        }
    }

    @Override
    public User createUser(User user) {
        if (user.getId() == null || user.getId().isEmpty()) {
            user.setId(IdGenerator.uuid());
        }
        long now = System.currentTimeMillis();
        user.setCreatedAt(now);
        user.setUpdatedAt(now);

        try (SqlSession session = openSession()) {
            session.getMapper(UserMapper.class).createUser(user);
            return user;
        } catch (Exception e) {
            log.error("Failed to create user: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public boolean updateUserRole(String username, String role) {
        try (SqlSession session = openSession()) {
            return session.getMapper(UserMapper.class).updateUserRole(username, role, System.currentTimeMillis()) > 0;
        } catch (Exception e) {
            log.error("Failed to update role for user {}: {}", username, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean updateUserApiKey(String username, String apiKey) {
        try (SqlSession session = openSession()) {
            return session.getMapper(UserMapper.class).updateUserApiKey(username, apiKey, System.currentTimeMillis()) > 0;
        } catch (Exception e) {
            log.error("Failed to update API key for user {}: {}", username, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean updateUserLastLogin(String username) {
        try (SqlSession session = openSession()) {
            return session.getMapper(UserMapper.class).updateUserLastLogin(username, System.currentTimeMillis()) > 0;
        } catch (Exception e) {
            log.error("Failed to update last login for user {}: {}", username, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean deleteUser(String username) {
        try (SqlSession session = openSession()) {
            return session.getMapper(UserMapper.class).deleteUser(username) > 0;
        } catch (Exception e) {
            log.error("Failed to delete user {}: {}", username, e.getMessage());
            return false;
        }
    }
}
