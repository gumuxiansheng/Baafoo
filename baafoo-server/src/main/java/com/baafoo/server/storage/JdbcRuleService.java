package com.baafoo.server.storage;

import com.baafoo.core.api.PaginatedResult;
import com.baafoo.core.model.Rule;
import com.baafoo.core.util.IdGenerator;
import com.baafoo.server.storage.mapper.RuleMapper;
import com.baafoo.server.storage.mapper.SceneMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * JDBC implementation of {@link RuleService}.
 *
 * <p>P0-4: extracted from {@code JdbcStorageService}. Owns rule CRUD, paged
 * query, and undo (version history). The local rules cache (AtomicReference +
 * DCL lock) is preserved verbatim; cross-service cache invalidation is exposed
 * via {@link #invalidateCache()} for the Facade and sibling services to call.</p>
 *
 * <p>Rule updates merge scene-inherited environments — the read-side lookup
 * is delegated to {@link SceneService#getInheritedEnvironments(String)}, so a
 * {@link SceneService} reference is injected.</p>
 */
public class JdbcRuleService extends BaseJdbcService implements RuleService {

    private static final Logger log = LoggerFactory.getLogger(JdbcRuleService.class);

    private final ObjectMapper mapper;
    private final SceneService sceneService;

    // --- Local cache for high-frequency reads ---
    //
    // Cache value + timestamp are bundled in an immutable CacheEntry and
    // published atomically via a single AtomicReference (Medium 19).
    // Previously the value (rulesCache.set) and timestamp (rulesCacheTime=)
    // were updated as two separate volatile writes — readers could observe
    // a fresh value with a stale timestamp and treat the cache as expired,
    // then redundantly reload from the DB.
    private final AtomicReference<CacheEntry<List<Rule>>> rulesCache = new AtomicReference<>();
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
    private final Object rulesCacheLock = new Object();

    public JdbcRuleService(SqlSessionFactory sqlSessionFactory, ObjectMapper mapper, SceneService sceneService) {
        super(sqlSessionFactory);
        // ObjectMapper is used only for deep-cloning Rule snapshots (serialize
        // → deserialize). INDENT_OUTPUT would waste CPU/bytes on formatting
        // that is immediately discarded (Low 42).
        this.mapper = mapper;
        this.sceneService = sceneService;
    }

    /** Public cache-invalidation hook for the Facade / sibling services. */
    public void invalidateCache() {
        invalidateRulesCache();
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
    public PaginatedResult<Rule> listRulesPaged(String protocol, String keyword, String environment, String host, String sortBy, String sortOrder, int page, int size) {
        try (SqlSession session = openSession()) {
            RuleMapper rm = session.getMapper(RuleMapper.class);
            long total = rm.countRules(protocol, keyword, environment, host);
            int offset = (page - 1) * size;
            List<Rule> items = rm.listRulesPaged(protocol, keyword, environment, host, sortBy, sortOrder, size, offset);
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
}
