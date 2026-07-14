package com.baafoo.server.storage;

import com.baafoo.core.model.Environment;
import com.baafoo.server.storage.mapper.AgentMapper;
import com.baafoo.server.storage.mapper.EnvironmentMapper;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JDBC implementation of {@link AgentService}.
 *
 * <p>P0-4: extracted from {@code JdbcStorageService}. Owns agent registration,
 * heartbeat, listing (with TTL cache), and the in-memory plugin-health map.
 * Cross-service cache invalidation (environment cache, touched by
 * {@link #registerAgent} when backfilling {@code agentIds}) is delegated to an
 * {@code environmentCacheInvalidator} callback wired by the Facade; the agent
 * cache itself is exposed via {@link #invalidateCache()}.</p>
 */
public class JdbcAgentService extends BaseJdbcService implements AgentService {

    private static final Logger log = LoggerFactory.getLogger(JdbcAgentService.class);

    /**
     * Used by {@link #registerAgent} to look up the environment by name and
     * backfill its {@code agentIds}. Injected rather than reaching into the
     * environment mapper directly so environment lookup semantics stay owned
     * by {@link EnvironmentService}.
     */
    private final EnvironmentService environmentService;

    /**
     * Invoked after {@link #registerAgent} mutates an environment's
     * {@code agentIds}, so the owning Facade can invalidate the environment
     * cache owned by {@link JdbcEnvironmentService}.
     */
    private final Runnable environmentCacheInvalidator;

    private final AtomicReference<CacheEntry<List<AgentRegistration>>> agentsCache = new AtomicReference<>();
    private static final long AGENTS_CACHE_TTL_MS = 5000; // 5 seconds (heartbeat frequent)

    /**
     * Per-cache load lock to prevent cache stampede. Under high throughput,
     * when the TTL expires many concurrent threads could simultaneously
     * observe the stale cache, all fire the same SQL, and waste DB
     * connections (HikariCP max=10). Double-checked locking (DCL) on this
     * monitor ensures only one thread reloads the cache while others wait
     * and then read the freshly-published entry. Each cache has its own
     * monitor so listRules() and listAgents() never block each other.
     */
    private final Object agentsCacheLock = new Object();

    /** P3: In-memory plugin health statuses per agent (refreshed via heartbeat, not persisted). */
    private final ConcurrentHashMap<String, Map<String, Object>> agentPluginStatuses =
            new ConcurrentHashMap<String, Map<String, Object>>();

    public JdbcAgentService(SqlSessionFactory sqlSessionFactory, EnvironmentService environmentService,
                            Runnable environmentCacheInvalidator) {
        super(sqlSessionFactory);
        this.environmentService = environmentService;
        this.environmentCacheInvalidator = environmentCacheInvalidator;
    }

    /** Public cache-invalidation hook for the Facade / sibling services. */
    public void invalidateCache() {
        invalidateAgentsCache();
    }

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
            Environment env = environmentService.getEnvironmentByName(environment);
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

    private void invalidateEnvironmentsCache() {
        if (environmentCacheInvalidator != null) {
            environmentCacheInvalidator.run();
        }
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
}
