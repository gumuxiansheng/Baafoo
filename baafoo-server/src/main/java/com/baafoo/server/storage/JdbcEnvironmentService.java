package com.baafoo.server.storage;

import com.baafoo.core.model.Environment;
import com.baafoo.core.model.Rule;
import com.baafoo.core.util.IdGenerator;
import com.baafoo.server.storage.mapper.AgentMapper;
import com.baafoo.server.storage.mapper.EnvironmentMapper;
import com.baafoo.server.storage.mapper.RuleMapper;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JDBC implementation of {@link EnvironmentService}.
 *
 * <p>P0-4: extracted from {@code JdbcStorageService}. Owns environment CRUD
 * and the Rule-Environment association operations
 * ({@link #associateRulesToEnvironment} / {@link #dissociateRulesFromEnvironment}).
 * The local environments cache (AtomicReference + DCL lock) is preserved
 * verbatim; cross-service cache invalidation (rule cache, touched by the
 * association methods) is delegated to a {@code ruleCacheInvalidator} callback
 * wired by the Facade, and the environment cache itself is exposed via
 * {@link #invalidateCache()}.</p>
 */
public class JdbcEnvironmentService extends BaseJdbcService implements EnvironmentService {

    private static final Logger log = LoggerFactory.getLogger(JdbcEnvironmentService.class);

    /**
     * Invoked after Rule-Environment association changes mutate rules, so the
     * owning Facade can invalidate the rule cache owned by {@link JdbcRuleService}.
     */
    private final Runnable ruleCacheInvalidator;

    private final AtomicReference<CacheEntry<List<Environment>>> environmentsCache = new AtomicReference<>();
    private static final long CACHE_TTL_MS = 2000; // 2 seconds

    /**
     * Per-cache load lock to prevent cache stampede. Under high throughput,
     * when the TTL expires many concurrent threads could simultaneously
     * observe the stale cache, all fire the same SQL, and waste DB
     * connections (HikariCP max=10). Double-checked locking (DCL) on this
     * monitor ensures only one thread reloads the cache while others wait
     * and then read the freshly-published entry. Each cache has its own
     * monitor so listRules() and listAgents() never block each other.
     */
    private final Object environmentsCacheLock = new Object();

    public JdbcEnvironmentService(SqlSessionFactory sqlSessionFactory, Runnable ruleCacheInvalidator) {
        super(sqlSessionFactory);
        this.ruleCacheInvalidator = ruleCacheInvalidator;
    }

    /** Public cache-invalidation hook for the Facade / sibling services. */
    public void invalidateCache() {
        invalidateEnvironmentsCache();
    }

    private void invalidateEnvironmentsCache() {
        environmentsCache.set(null);
    }

    private void invalidateRulesCache() {
        if (ruleCacheInvalidator != null) {
            ruleCacheInvalidator.run();
        }
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
}
