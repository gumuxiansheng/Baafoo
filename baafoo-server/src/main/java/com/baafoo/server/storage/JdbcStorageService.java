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
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

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

    /**
     * P1-3: delegated scene operations. Initialized in {@link #init()} after
     * the SqlSessionFactory is built. {@link JdbcStorageService} retains its
     * {@link StorageService} scene methods as thin delegations so existing
     * callers keep working, while new callers can depend on
     * {@link SceneService} directly.
     */
    private JdbcSceneService sceneService;

    // --- Local caches for high-frequency reads ---
    //
    // Cache value + timestamp are bundled in an immutable CacheEntry and
    // published atomically via a single AtomicReference (Medium 19).
    // Previously the value (rulesCache.set) and timestamp (rulesCacheTime=)
    // were updated as two separate volatile writes — readers could observe
    // a fresh value with a stale timestamp and treat the cache as expired,
    // then redundantly reload from the DB.
    private static final class CacheEntry<T> {
        final T value;
        final long timestamp;
        CacheEntry(T value, long timestamp) {
            this.value = value;
            this.timestamp = timestamp;
        }
    }
    private final AtomicReference<CacheEntry<List<Rule>>> rulesCache = new AtomicReference<>();
    private final AtomicReference<CacheEntry<List<Environment>>> environmentsCache = new AtomicReference<>();
    private final AtomicReference<CacheEntry<List<AgentRegistration>>> agentsCache = new AtomicReference<>();
    private static final long CACHE_TTL_MS = 2000; // 2 seconds
    private static final long AGENTS_CACHE_TTL_MS = 5000; // 5 seconds (heartbeat frequent)

    /**
     * Per-cache load locks to prevent cache stampede. Under high throughput,
     * when the TTL expires many concurrent threads could simultaneously
     * observe the stale cache, all fire the same SQL, and waste DB
     * connections (HikariCP max=10). Double-checked locking (DCL) on these
     * monitors ensures only one thread reloads the cache while others wait
     * and then read the freshly-published entry. Each cache has its own
     * monitor so listRules() and listAgents() never block each other.
     */
    private final Object rulesCacheLock = new Object();
    private final Object environmentsCacheLock = new Object();
    private final Object agentsCacheLock = new Object();

    /** P3: In-memory plugin health statuses per agent (refreshed via heartbeat, not persisted). */
    private final ConcurrentHashMap<String, Map<String, Object>> agentPluginStatuses =
            new ConcurrentHashMap<String, Map<String, Object>>();

    /** H8: trimRecordings is expensive — only run every 50 inserts, not every call. */
    private static final int TRIM_INTERVAL = 50;
    private final java.util.concurrent.atomic.AtomicInteger addRecordingCounter = new java.util.concurrent.atomic.AtomicInteger(0);

    public JdbcStorageService(ServerConfig config) {
        this.config = config;
        // ObjectMapper is used only for deep-cloning Rule snapshots (serialize
        // → deserialize). INDENT_OUTPUT would waste CPU/bytes on formatting
        // that is immediately discarded (Low 42).
        this.mapper = new ObjectMapper();
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
            // Resolve system property placeholders like ${user.home}
            dataDir = resolvePath(dataDir);
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

        // P1-3: initialize the delegated scene service with a cache-invalidation
        // callback that reaches back into this class's private cache fields.
        sceneService = new JdbcSceneService(sqlSessionFactory, new JdbcSceneService.CacheInvalidator() {
            @Override public void invalidateRulesCache() { JdbcStorageService.this.invalidateRulesCache(); }
            @Override public void invalidateEnvironmentsCache() { JdbcStorageService.this.invalidateEnvironmentsCache(); }
        });

        log.info("{} storage initialized with HikariCP pool: {}", dialect.getConfigValue().toUpperCase(), jdbcUrl);
    }

    /** Public accessor so the bootstrap can wire {@link SceneService} consumers. */
    public SceneService getSceneService() {
        return sceneService;
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
            "mapper/MqRelationshipMapper.xml",
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
        rulesCache.set(null);
    }

    @Override
    public List<Rule> listRules() {
        long now = System.currentTimeMillis();
        CacheEntry<List<Rule>> cached = rulesCache.get();
        if (cached != null && (now - cached.timestamp) < CACHE_TTL_MS) {
            return cached.value;
        }
        // DCL: prevent cache stampede — when TTL expires under high RPS,
        // many threads would otherwise all fire the same SQL concurrently.
        synchronized (rulesCacheLock) {
            cached = rulesCache.get();
            if (cached != null && (System.currentTimeMillis() - cached.timestamp) < CACHE_TTL_MS) {
                return cached.value;
            }
            try (SqlSession session = openSession()) {
                List<Rule> result = session.getMapper(RuleMapper.class).listRules();
                // Atomic publish: a single set() makes both value and timestamp
                // visible to other threads (Medium 19).
                rulesCache.set(new CacheEntry<>(result, System.currentTimeMillis()));
                return result;
            }
        }
    }

    @Override
    public PaginatedResult<Rule> listRulesPaged(String protocol, String keyword, String environment, String host, int page, int size) {
        try (SqlSession session = openSession()) {
            RuleMapper rm = session.getMapper(RuleMapper.class);
            long total = rm.countRules(protocol, keyword, environment, host);
            int offset = (page - 1) * size;
            List<Rule> items = rm.listRulesPaged(protocol, keyword, environment, host, size, offset);
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
            SceneMapper sm = session.getMapper(SceneMapper.class);
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
            if (update.getEnvironments() != null && !update.getEnvironments().isEmpty()) {
                // Merge inherited environments from active scenes so that direct
                // storage updates (not just the API handler) preserve inheritance.
                List<String> requested = update.getEnvironments();
                List<String> merged = new ArrayList<>(requested);
                for (String inherited : getInheritedEnvironments(sm, id)) {
                    if (!merged.contains(inherited)) merged.add(inherited);
                }
                existing.setEnvironments(merged);
            }
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

    /**
     * Compute the set of environments inherited by {@code ruleId} from active scenes
     * that include this rule in their itemIds. Moved here from ApiUtils so that all
     * update paths (API handler, scene sync, etc.) consistently preserve inheritance.
     *
     * <p>P1-3: now delegates to {@link JdbcSceneService#getInheritedEnvironments(String)}
     * so the scene-rule coupling logic lives in one place.</p>
     */
    private List<String> getInheritedEnvironments(SceneMapper sm, String ruleId) {
        return sceneService.getInheritedEnvironments(ruleId);
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
            if (deleted) {
                invalidateRulesCache();
                // Clean up the per-rule counter to prevent unbounded map growth
                // (Medium 28). Previously only the RuleApiHandler did this, leaving
                // leaks when rules were deleted via ChaosApiHandler / RuleTools /
                // direct storage calls.
                com.baafoo.core.util.StatefulCounterStore.global().reset(id);
            }
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
        environmentsCache.set(null);
    }

    @Override
    public List<Environment> listEnvironments() {
        long now = System.currentTimeMillis();
        CacheEntry<List<Environment>> cached = environmentsCache.get();
        if (cached != null && (now - cached.timestamp) < CACHE_TTL_MS) {
            return cached.value;
        }
        synchronized (environmentsCacheLock) {
            cached = environmentsCache.get();
            if (cached != null && (System.currentTimeMillis() - cached.timestamp) < CACHE_TTL_MS) {
                return cached.value;
            }
            try (SqlSession session = openSession()) {
                List<Environment> result = session.getMapper(EnvironmentMapper.class).listEnvironments();
                environmentsCache.set(new CacheEntry<>(result, System.currentTimeMillis()));
                return result;
            }
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
            List<Environment> list = session.getMapper(EnvironmentMapper.class).listEnvironments();
            for (Environment env : list) {
                if (name.equals(env.getName())) {
                    return env;
                }
            }
            return null;
        }
    }

    @Override
    public Environment createEnvironment(Environment env) {
        // Check for duplicate name first
        Environment existing = getEnvironmentByName(env.getName());
        if (existing != null) {
            return existing;
        }
        if (env.getId() == null || env.getId().isEmpty()) {
            env.setId(IdGenerator.uuid());
        }
        long now = System.currentTimeMillis();
        env.setCreatedAt(now);
        env.setUpdatedAt(now);

        try (SqlSession session = openSession()) {
            session.getMapper(EnvironmentMapper.class).createEnvironment(env);
            // Backfill agentIds from already-registered agents for this environment
            List<AgentRegistration> existingAgents = session.getMapper(AgentMapper.class).getAgentsForEnvironment(env.getName());
            if (existingAgents != null && !existingAgents.isEmpty()) {
                for (AgentRegistration a : existingAgents) {
                    if (!env.getAgentIds().contains(a.agentId)) {
                        env.getAgentIds().add(a.agentId);
                    }
                }
                session.getMapper(EnvironmentMapper.class).updateEnvironment(env);
            }
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
    //
    // P1-3: scene operations are delegated to JdbcSceneService. These methods
    // remain as thin wrappers so existing callers of StorageService keep
    // working. New callers should depend on SceneService directly.

    @Override
    public List<SceneSet> listScenes() {
        return sceneService.listScenes();
    }

    @Override
    public SceneSet getScene(String id) {
        return sceneService.getScene(id);
    }

    @Override
    public SceneSet createScene(SceneSet scene) {
        return sceneService.createScene(scene);
    }

    @Override
    public SceneSet updateScene(String id, SceneSet update) {
        return sceneService.updateScene(id, update);
    }

    @Override
    public boolean deleteScene(String id) {
        return sceneService.deleteScene(id);
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

    // --- MQ Relationship CRUD ---

    @Override
    public List<MqRelationship> listMqRelationships() {
        try (SqlSession session = openSession()) {
            return session.getMapper(MqRelationshipMapper.class).listMqRelationships();
        }
    }

    @Override
    public List<MqRelationship> listMqRelationshipsByFrom(String fromProtocol, String fromTopic) {
        try (SqlSession session = openSession()) {
            return session.getMapper(MqRelationshipMapper.class)
                    .listMqRelationshipsByFrom(fromProtocol, fromTopic);
        }
    }

    @Override
    public MqRelationship getMqRelationship(String id) {
        try (SqlSession session = openSession()) {
            return session.getMapper(MqRelationshipMapper.class).getMqRelationship(id);
        }
    }

    @Override
    public MqRelationship createMqRelationship(MqRelationship relationship) {
        if (relationship.getId() == null || relationship.getId().isEmpty()) {
            relationship.setId(IdGenerator.uuid());
        }
        long now = System.currentTimeMillis();
        relationship.setCreatedAt(now);
        relationship.setUpdatedAt(now);

        try (SqlSession session = openSession()) {
            session.getMapper(MqRelationshipMapper.class).createMqRelationship(relationship);
            return relationship;
        } catch (Exception e) {
            log.error("Failed to create MQ relationship: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public MqRelationship updateMqRelationship(String id, MqRelationship update) {
        try (SqlSession session = openSession()) {
            MqRelationshipMapper mapper = session.getMapper(MqRelationshipMapper.class);
            MqRelationship existing = mapper.getMqRelationship(id);
            if (existing == null) return null;

            if (update.getName() != null) existing.setName(update.getName());
            if (update.getFromProtocol() != null) existing.setFromProtocol(update.getFromProtocol());
            if (update.getFromTopic() != null) existing.setFromTopic(update.getFromTopic());
            if (update.getToProtocol() != null) existing.setToProtocol(update.getToProtocol());
            if (update.getToTopic() != null) existing.setToTopic(update.getToTopic());
            if (update.getKeyTemplate() != null) existing.setKeyTemplate(update.getKeyTemplate());
            if (update.getValueTemplate() != null) existing.setValueTemplate(update.getValueTemplate());
            existing.setDelayMs(update.getDelayMs());
            existing.setEnabled(update.isEnabled());
            existing.setUpdatedAt(System.currentTimeMillis());

            mapper.updateMqRelationship(existing);
            return existing;
        } catch (Exception e) {
            log.error("Failed to update MQ relationship {}: {}", id, e.getMessage());
            return null;
        }
    }

    @Override
    public boolean deleteMqRelationship(String id) {
        try (SqlSession session = openSession()) {
            return session.getMapper(MqRelationshipMapper.class).deleteMqRelationship(id) > 0;
        } catch (Exception e) {
            log.error("Failed to delete MQ relationship {}: {}", id, e.getMessage());
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
    public PaginatedResult<RecordingEntry> listRecordingsPaged(String ruleId, String agentId, String agentIp,
                                                                String protocol, String method, String path,
                                                                Integer statusCode, String keyword,
                                                                int page, int size) {
        try (SqlSession session = openSession()) {
            RecordingMapper rcm = session.getMapper(RecordingMapper.class);
            long total = rcm.countRecordings(ruleId, agentId, agentIp, protocol, method, path, statusCode, keyword);
            int offset = (page - 1) * size;
            List<RecordingEntry> items = rcm.listRecordingsPaged(ruleId, agentId, agentIp, protocol, method, path, statusCode, keyword, size, offset);
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
            // H8: only trim every TRIM_INTERVAL inserts to avoid blocking IO thread
            if (addRecordingCounter.incrementAndGet() % TRIM_INTERVAL == 0) {
                rcm.trimRecordings(1000);
            }
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
            // H8: only trim every TRIM_INTERVAL inserts
            if (addRecordingCounter.addAndGet(batch.size()) % TRIM_INTERVAL == 0) {
                rcm.trimRecordings(1000);
            }
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

    @Override
    public int deleteRecordingsOlderThan(int retentionDays) {
        long cutoffTime = System.currentTimeMillis() - (long) retentionDays * 24 * 60 * 60 * 1000;
        try (SqlSession session = openSession()) {
            return session.getMapper(RecordingMapper.class).deleteRecordingsOlderThan(cutoffTime);
        } catch (Exception e) {
            log.error("Failed to delete old recordings: {}", e.getMessage());
            return 0;
        }
    }

    @Override
    public long getRecordingCount() {
        try (SqlSession session = openSession()) {
            return session.getMapper(RecordingMapper.class).countAllRecordings();
        } catch (Exception e) {
            log.error("Failed to count recordings: {}", e.getMessage());
            return 0;
        }
    }

    @Override
    public long getRecordingTotalSizeBytes() {
        // Aggregate the actual body bytes (request + response) from the database
        // instead of estimating with a fixed 2KB-per-recording heuristic.
        try (SqlSession session = openSession()) {
            return session.getMapper(RecordingMapper.class).sumAllRecordingBodyBytes();
        } catch (Exception e) {
            log.error("Failed to sum recording body bytes: {}", e.getMessage());
            return 0;
        }
    }

    @Override
    public List<Map<String, Object>> getRecordingCountsByDay(long startTime) {
        try (SqlSession session = openSession()) {
            return session.getMapper(RecordingMapper.class).countRecordingsByDay(startTime);
        } catch (Exception e) {
            log.error("Failed to get recording counts by day: {}", e.getMessage());
            return java.util.Collections.emptyList();
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
            Environment env = getEnvironmentByName(environment);
            if (env != null && !env.getAgentIds().contains(agentId)) {
                env.getAgentIds().add(agentId);
                session.getMapper(EnvironmentMapper.class).updateEnvironment(env);
                invalidateEnvironmentsCache();
            }
        } catch (Exception e) {
            log.warn("Failed to update environment agent list: {}", e.getMessage());
        }

        return reg;
    }

    private void invalidateAgentsCache() {
        agentsCache.set(null);
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
    public void updateAgentPluginStatuses(String agentId, Map<String, Object> pluginStatuses) {
        if (agentId == null) return;
        if (pluginStatuses != null && !pluginStatuses.isEmpty()) {
            agentPluginStatuses.put(agentId, pluginStatuses);
        } else {
            agentPluginStatuses.remove(agentId);
        }
    }

    @Override
    public List<AgentRegistration> listAgents() {
        long now = System.currentTimeMillis();
        CacheEntry<List<AgentRegistration>> cached = agentsCache.get();
        if (cached != null && (now - cached.timestamp) < AGENTS_CACHE_TTL_MS) {
            return cached.value;
        }
        synchronized (agentsCacheLock) {
            cached = agentsCache.get();
            if (cached != null && (System.currentTimeMillis() - cached.timestamp) < AGENTS_CACHE_TTL_MS) {
                return cached.value;
            }
            try (SqlSession session = openSession()) {
                List<AgentRegistration> result = session.getMapper(AgentMapper.class).listAgents();
                // P3: Populate in-memory plugin statuses into AgentRegistration
                for (AgentRegistration reg : result) {
                    reg.pluginStatuses = agentPluginStatuses.get(reg.agentId);
                }
                agentsCache.set(new CacheEntry<>(result, System.currentTimeMillis()));
                return result;
            }
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
        if (ruleIds == null || ruleIds.isEmpty() || envName == null) return;
        // Batched into a single SqlSession — previously this opened 2 sessions
        // per ruleId (getRule + updateRule), causing 2N database round-trips
        // (High 11). All N rules are now processed with 2 queries total per rule
        // (SELECT + UPDATE) inside one shared session.
        try (SqlSession session = openSession()) {
            RuleMapper rm = session.getMapper(RuleMapper.class);
            for (String ruleId : ruleIds) {
                Rule rule = rm.getRule(ruleId);
                if (rule == null) continue;
                List<String> envs = new ArrayList<>(rule.getEnvironments() != null
                        ? rule.getEnvironments() : Collections.<String>emptyList());
                if (!envs.contains(envName)) {
                    envs.add(envName);
                    rule.setEnvironments(envs);
                    rule.setVersion(rule.getVersion() + 1);
                    rule.setUpdatedAt(System.currentTimeMillis());
                    rm.updateRule(rule);
                }
            }
            invalidateRulesCache();
        } catch (Exception e) {
            log.error("Failed to associate rules to environment {}: {}", envName, e.getMessage());
        }
    }

    @Override
    public void dissociateRulesFromEnvironment(String envName, List<String> ruleIds) {
        if (ruleIds == null || ruleIds.isEmpty() || envName == null) return;
        // Same batching pattern as associateRulesToEnvironment (High 11).
        try (SqlSession session = openSession()) {
            RuleMapper rm = session.getMapper(RuleMapper.class);
            for (String ruleId : ruleIds) {
                Rule rule = rm.getRule(ruleId);
                if (rule == null) continue;
                List<String> envs = new ArrayList<>(rule.getEnvironments() != null
                        ? rule.getEnvironments() : Collections.<String>emptyList());
                if (envs.remove(envName)) {
                    rule.setEnvironments(envs);
                    rule.setVersion(rule.getVersion() + 1);
                    rule.setUpdatedAt(System.currentTimeMillis());
                    rm.updateRule(rule);
                }
            }
            invalidateRulesCache();
        } catch (Exception e) {
            log.error("Failed to dissociate rules from environment {}: {}", envName, e.getMessage());
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
    public boolean updateUserPassword(String username, String passwordHash) {
        try (SqlSession session = openSession()) {
            return session.getMapper(UserMapper.class).updateUserPassword(username, passwordHash, System.currentTimeMillis()) > 0;
        } catch (Exception e) {
            log.error("Failed to update password for user {}: {}", username, e.getMessage());
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

    /**
     * Resolve system property placeholders in a path string.
     * E.g., "${user.home}/.baafoo" → "C:/Users/john/.baafoo"
     * Also handles "~/" as user home shorthand.
     */
    private static String resolvePath(String path) {
        if (path == null) return path;
        // Handle ~/ shorthand
        if (path.startsWith("~/")) {
            path = System.getProperty("user.home") + path.substring(1);
        }
        // Handle ${property.name} placeholders
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\$\\{([^}]+)\\}");
        java.util.regex.Matcher matcher = pattern.matcher(path);
        StringBuffer sb = new StringBuffer();
        while (matcher.find()) {
            String prop = System.getProperty(matcher.group(1));
            matcher.appendReplacement(sb, prop != null ? java.util.regex.Matcher.quoteReplacement(prop) : "");
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}
