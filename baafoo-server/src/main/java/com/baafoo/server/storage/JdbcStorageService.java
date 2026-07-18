package com.baafoo.server.storage;

import com.baafoo.core.api.PaginatedResult;
import com.baafoo.core.config.ServerConfig;
import com.baafoo.core.model.*;
import com.baafoo.server.storage.dialect.DatabaseDialect;
import com.baafoo.server.storage.dialect.DdlBuilder;
import com.baafoo.server.storage.mybatis.EnvironmentModeTypeHandler;
import com.baafoo.server.storage.mybatis.JsonTypeHandler;
import com.baafoo.server.storage.mybatis.MatchConditionListHandler;
import com.baafoo.server.storage.mybatis.ResponseEntryListHandler;
import com.baafoo.server.storage.mybatis.UuidTypeHandler;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.apache.ibatis.mapping.DatabaseIdProvider;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactory;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * JDBC-based storage facade that supports multiple database dialects (H2, PostgreSQL).
 * Uses MyBatis for data access, abstracting SQL dialect differences via mapper XML files.
 *
 * <p>P0-4: this class is now a thin Facade over the per-aggregate-root sub-services
 * ({@link JdbcRuleService}, {@link JdbcEnvironmentService}, {@link JdbcRecordingService},
 * {@link JdbcAgentService}, {@link JdbcUserService}, {@link JdbcMqRelationshipService},
 * {@link JdbcRuleSetService}, {@link JdbcSceneService}). It retains the
 * {@link StorageService} contract for backwards compatibility — every method delegates
 * to the corresponding sub-service — and owns only the lifecycle
 * ({@link #init()} / {@link #shutdown()}), the {@link SqlSessionFactory} bootstrap,
 * and the cross-service cache-invalidation wiring.</p>
 *
 * <p>The sub-services reference each other only through callbacks (Runnables /
 * {@link JdbcSceneService.CacheInvalidator}) so that the cache ownership stays with
 * each sub-service while the Facade coordinates the invalidation topology. This
 * mirrors the pattern already established by {@link JdbcSceneService}.</p>
 */
public class JdbcStorageService implements StorageService {

    private static final Logger log = LoggerFactory.getLogger(JdbcStorageService.class);

    private final ServerConfig config;
    private final DatabaseDialect dialect;
    private HikariDataSource dataSource;
    private SqlSessionFactory sqlSessionFactory;

    /**
     * P1-3 / P0-4: delegated scene operations. Initialized in {@link #init()} after
     * the {@link SqlSessionFactory} is built. {@link JdbcStorageService} retains its
     * {@link StorageService} scene methods as thin delegations so existing callers
     * keep working, while new callers can depend on {@link SceneService} directly.
     */
    private JdbcSceneService sceneService;

    // --- Per-aggregate-root sub-services (initialized in init()) ---
    private JdbcRuleService ruleService;
    private JdbcEnvironmentService environmentService;
    private JdbcRecordingService recordingService;
    private JdbcAgentService agentService;
    private JdbcUserService userService;
    private JdbcMqRelationshipService mqRelationshipService;
    private JdbcRuleSetService ruleSetService;

    public JdbcStorageService(ServerConfig config) {
        this.config = config;
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

        // P0-4: construct the per-aggregate-root sub-services in dependency order.
        //
        // sceneService is built first so ruleService can reference it; its
        // CacheInvalidator callback lazily resolves the rule/environment cache
        // invalidators (which are only wired once ruleService/environmentService
        // are constructed below — the callbacks are invoked at runtime, never
        // during init()). The null guards are defensive: scene mutations only
        // happen after init() completes, by which point all fields are set.
        this.sceneService = new JdbcSceneService(sqlSessionFactory, new JdbcSceneService.CacheInvalidator() {
            @Override public void invalidateRulesCache() {
                if (ruleService != null) ruleService.invalidateCache();
            }
            @Override public void invalidateEnvironmentsCache() {
                if (environmentService != null) environmentService.invalidateCache();
            }
        });

        // ObjectMapper is used only for deep-cloning Rule snapshots (serialize
        // → deserialize). INDENT_OUTPUT would waste CPU/bytes on formatting
        // that is immediately discarded (Low 42).
        com.fasterxml.jackson.databind.ObjectMapper mapper = com.baafoo.core.util.JsonUtils.MAPPER;

        this.ruleService = new JdbcRuleService(sqlSessionFactory, mapper, sceneService);
        this.environmentService = new JdbcEnvironmentService(sqlSessionFactory,
                () -> { if (ruleService != null) ruleService.invalidateCache(); });
        this.agentService = new JdbcAgentService(sqlSessionFactory, environmentService,
                () -> { if (environmentService != null) environmentService.invalidateCache(); });
        this.recordingService = new JdbcRecordingService(sqlSessionFactory);
        this.userService = new JdbcUserService(sqlSessionFactory);
        this.mqRelationshipService = new JdbcMqRelationshipService(sqlSessionFactory);
        this.ruleSetService = new JdbcRuleSetService(sqlSessionFactory);

        log.info("{} storage initialized with HikariCP pool: {}", dialect.getConfigValue().toUpperCase(), jdbcUrl);
    }

    /** Public accessor so the bootstrap can wire {@link SceneService} consumers. */
    public SceneService getSceneService() {
        return sceneService;
    }

    /**
     * Public accessor for the {@link ServerConfig} so components that were
     * constructed with only a {@link StorageService} reference (e.g.
     * {@link com.baafoo.server.handler.AgentResolver}'s single-arg constructor)
     * can still read global configuration such as
     * {@code unknownEnvironmentDefault}. This avoids forcing every broker
     * and helper class to thread {@code ServerConfig} through their own
     * constructors.
     */
    public ServerConfig getServerConfig() {
        return config;
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
        configuration.getTypeHandlerRegistry().register(UuidTypeHandler.class);

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

    // --- Rule CRUD (delegated to JdbcRuleService) ---

    @Override
    public List<Rule> listRules() {
        return ruleService.listRules();
    }

    @Override
    public PaginatedResult<Rule> listRulesPaged(String protocol, String keyword, String environment, String host,
                                                String sortBy, String sortOrder, int page, int size) {
        return ruleService.listRulesPaged(protocol, keyword, environment, host, sortBy, sortOrder, page, size);
    }

    @Override
    public Rule getRule(String id) {
        return ruleService.getRule(id);
    }

    @Override
    public Rule createRule(Rule rule) {
        return ruleService.createRule(rule);
    }

    @Override
    public Rule updateRule(String id, Rule update) {
        return ruleService.updateRule(id, update);
    }

    @Override
    public boolean deleteRule(String id) {
        return ruleService.deleteRule(id);
    }

    @Override
    public boolean undoRule(String id) {
        return ruleService.undoRule(id);
    }

    // --- Environment CRUD (delegated to JdbcEnvironmentService) ---

    @Override
    public List<Environment> listEnvironments() {
        return environmentService.listEnvironments();
    }

    @Override
    public Environment getEnvironment(String id) {
        return environmentService.getEnvironment(id);
    }

    @Override
    public Environment getEnvironmentByName(String name) {
        return environmentService.getEnvironmentByName(name);
    }

    @Override
    public Environment createEnvironment(Environment env) {
        return environmentService.createEnvironment(env);
    }

    @Override
    public Environment updateEnvironment(String id, Environment update) {
        return environmentService.updateEnvironment(id, update);
    }

    @Override
    public boolean deleteEnvironment(String id) {
        return environmentService.deleteEnvironment(id);
    }

    @Override
    public void associateRulesToEnvironment(String envName, List<String> ruleIds) {
        environmentService.associateRulesToEnvironment(envName, ruleIds);
    }

    @Override
    public void dissociateRulesFromEnvironment(String envName, List<String> ruleIds) {
        environmentService.dissociateRulesFromEnvironment(envName, ruleIds);
    }

    // --- Scene Set CRUD (delegated to JdbcSceneService) ---

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

    @Override
    public List<String> getInheritedEnvironments(String ruleId) {
        return sceneService.getInheritedEnvironments(ruleId);
    }

    // --- Rule Set CRUD (delegated to JdbcRuleSetService) ---

    @Override
    public List<RuleSet> listRuleSets() {
        return ruleSetService.listRuleSets();
    }

    @Override
    public RuleSet createRuleSet(RuleSet ruleSet) {
        return ruleSetService.createRuleSet(ruleSet);
    }

    @Override
    public boolean deleteRuleSet(String id) {
        return ruleSetService.deleteRuleSet(id);
    }

    // --- MQ Relationship CRUD (delegated to JdbcMqRelationshipService) ---

    @Override
    public List<MqRelationship> listMqRelationships() {
        return mqRelationshipService.listMqRelationships();
    }

    @Override
    public List<MqRelationship> listMqRelationshipsByFrom(String fromProtocol, String fromTopic) {
        return mqRelationshipService.listMqRelationshipsByFrom(fromProtocol, fromTopic);
    }

    @Override
    public MqRelationship getMqRelationship(String id) {
        return mqRelationshipService.getMqRelationship(id);
    }

    @Override
    public MqRelationship createMqRelationship(MqRelationship relationship) {
        return mqRelationshipService.createMqRelationship(relationship);
    }

    @Override
    public MqRelationship updateMqRelationship(String id, MqRelationship update) {
        return mqRelationshipService.updateMqRelationship(id, update);
    }

    @Override
    public boolean deleteMqRelationship(String id) {
        return mqRelationshipService.deleteMqRelationship(id);
    }

    // --- Recording (delegated to JdbcRecordingService) ---

    @Override
    public List<RecordingEntry> listRecordings(String ruleId, int limit) {
        return recordingService.listRecordings(ruleId, limit);
    }

    @Override
    public PaginatedResult<RecordingEntry> listRecordingsPaged(String ruleId, String agentId, String agentIp,
                                                                String protocol, String method, String path,
                                                                Integer statusCode, String keyword,
                                                                int page, int size) {
        return recordingService.listRecordingsPaged(ruleId, agentId, agentIp, protocol, method, path,
                statusCode, keyword, page, size);
    }

    @Override
    public void addRecording(RecordingEntry recording) {
        recordingService.addRecording(recording);
    }

    @Override
    public void addRecordings(List<RecordingEntry> batch) {
        recordingService.addRecordings(batch);
    }

    @Override
    public boolean deleteRecording(String id) {
        return recordingService.deleteRecording(id);
    }

    /** L-2: bulk-delete oldest N recordings (delegated to JdbcRecordingService). */
    @Override
    public int deleteOldestN(int limit) {
        return recordingService.deleteOldestN(limit);
    }

    @Override
    public int deleteRecordingsOlderThan(int retentionDays) {
        return recordingService.deleteRecordingsOlderThan(retentionDays);
    }

    @Override
    public long getRecordingCount() {
        return recordingService.getRecordingCount();
    }

    @Override
    public long getRecordingTotalSizeBytes() {
        return recordingService.getRecordingTotalSizeBytes();
    }

    @Override
    public List<Map<String, Object>> getRecordingCountsByDay(long startTime) {
        return recordingService.getRecordingCountsByDay(startTime);
    }

    // --- Agent Management (delegated to JdbcAgentService) ---

    @Override
    public AgentRegistration registerAgent(String agentId, String environment, String hostname,
                                            String version, List<String> protocols, String agentIp) {
        return agentService.registerAgent(agentId, environment, hostname, version, protocols, agentIp);
    }

    @Override
    public void agentHeartbeat(String agentId, String agentIp) {
        agentService.agentHeartbeat(agentId, agentIp);
    }

    @Override
    public void updateAgentPluginStatuses(String agentId, Map<String, Object> pluginStatuses) {
        agentService.updateAgentPluginStatuses(agentId, pluginStatuses);
    }

    @Override
    public List<AgentRegistration> listAgents() {
        return agentService.listAgents();
    }

    @Override
    public List<AgentRegistration> getAgentsForEnvironment(String envName) {
        return agentService.getAgentsForEnvironment(envName);
    }

    // --- User CRUD (delegated to JdbcUserService) ---

    @Override
    public List<User> listUsers() {
        return userService.listUsers();
    }

    @Override
    public User getUserByUsername(String username) {
        return userService.getUserByUsername(username);
    }

    @Override
    public User getUserByApiKey(String apiKey) {
        return userService.getUserByApiKey(apiKey);
    }

    @Override
    public User createUser(User user) {
        return userService.createUser(user);
    }

    @Override
    public boolean updateUserRole(String username, String role) {
        return userService.updateUserRole(username, role);
    }

    @Override
    public boolean updateUserApiKey(String username, String apiKey) {
        return userService.updateUserApiKey(username, apiKey);
    }

    @Override
    public boolean updateUserPassword(String username, String password) {
        return userService.updateUserPassword(username, password);
    }

    @Override
    public boolean updateUserLastLogin(String username) {
        return userService.updateUserLastLogin(username);
    }

    @Override
    public boolean deleteUser(String username) {
        return userService.deleteUser(username);
    }

    /**
     * Resolve system property placeholders in a path string.
     * E.g., "${user.home}/.baafoo" → "C:/Users/john/.baafoo"
     * Also handles "~/" as user home shorthand.
     *
     * <p>System property is read once at startup (called only from the
     * constructor during {@code dataDir} resolution, see M-3). Subsequent
     * runtime changes to {@code -Duser.home} or other system properties
     * will NOT be picked up — this is intentional, as the JDBC URL is
     * built once and HikariCP holds the connection pool for the JVM life.</p>
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
