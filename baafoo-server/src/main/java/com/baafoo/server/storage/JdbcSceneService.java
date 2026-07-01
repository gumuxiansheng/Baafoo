package com.baafoo.server.storage;

import com.baafoo.core.model.Rule;
import com.baafoo.core.model.SceneSet;
import com.baafoo.core.util.IdGenerator;
import com.baafoo.server.storage.mapper.RuleMapper;
import com.baafoo.server.storage.mapper.SceneMapper;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * JDBC implementation of {@link SceneService}.
 *
 * <p>P1-3: extracted from {@code JdbcStorageService}. Owns all scene-set CRUD
 * and the bidirectional Rule-Scene environment synchronization. The rule-cache
 * and environment-cache invalidation side effects are delegated to a
 * {@link CacheInvalidator} callback so this class does not need to reach into
 * {@code JdbcStorageService}'s private cache fields.</p>
 */
public class JdbcSceneService implements SceneService {

    private static final Logger log = LoggerFactory.getLogger(JdbcSceneService.class);

    private final SqlSessionFactory sqlSessionFactory;
    private final CacheInvalidator cacheInvalidator;

    /**
     * Callback invoked after scene updates mutate rules, so the owning
     * {@code JdbcStorageService} can invalidate its rule/environment caches.
     */
    public interface CacheInvalidator {
        void invalidateRulesCache();
        void invalidateEnvironmentsCache();
    }

    public JdbcSceneService(SqlSessionFactory sqlSessionFactory, CacheInvalidator cacheInvalidator) {
        this.sqlSessionFactory = sqlSessionFactory;
        this.cacheInvalidator = cacheInvalidator;
    }

    private SqlSession openSession() {
        return sqlSessionFactory.openSession(true);
    }

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
            boolean wasActive = existing.isActive();

            if (update.getName() != null) existing.setName(update.getName());
            if (update.getDescription() != null) existing.setDescription(update.getDescription());
            if (update.getItemIds() != null)
                existing.setItemIds(update.getItemIds());
            if (update.getEnvironments() != null)
                existing.setEnvironments(update.getEnvironments());
            existing.setActive(update.isActive());
            existing.setUpdatedAt(System.currentTimeMillis());

            sm.updateScene(existing);
            syncSceneEnvironmentsToRules(rm, sm, existing, oldEnvironments, oldItemIds, wasActive);

            return existing;
        } catch (Exception e) {
            log.error("Failed to update scene {}: {}", id, e.getMessage());
            return null;
        }
    }

    @Override
    public boolean deleteScene(String id) {
        try (SqlSession session = openSession()) {
            SceneMapper sm = session.getMapper(SceneMapper.class);
            RuleMapper rm = session.getMapper(RuleMapper.class);
            SceneSet scene = sm.getScene(id);
            if (scene == null) return false;

            // Remove this scene's environments from associated rules before deleting
            if (scene.isActive()) {
                List<String> envs = scene.getEnvironments() != null ? scene.getEnvironments() : Collections.<String>emptyList();
                List<String> itemIds = scene.getItemIds() != null ? scene.getItemIds() : Collections.<String>emptyList();
                for (String ruleId : itemIds) {
                    Rule rule = rm.getRule(ruleId);
                    if (rule == null) continue;
                    List<String> ruleEnvs = new ArrayList<>(rule.getEnvironments() != null ? rule.getEnvironments() : Collections.<String>emptyList());
                    for (String env : envs) {
                        boolean stillInherited = isEnvironmentInheritedFromOtherScene(sm, ruleId, env, id);
                        if (!stillInherited) {
                            ruleEnvs.remove(env);
                        }
                    }
                    rule.setEnvironments(ruleEnvs);
                    rm.updateRule(rule);
                }
                invalidateCaches();
            }

            return sm.deleteScene(id) > 0;
        } catch (Exception e) {
            log.error("Failed to delete scene {}: {}", id, e.getMessage());
            return false;
        }
    }

    @Override
    public List<String> getInheritedEnvironments(String ruleId) {
        List<String> inherited = new ArrayList<>();
        try (SqlSession session = openSession()) {
            SceneMapper sm = session.getMapper(SceneMapper.class);
            for (SceneSet scene : sm.listScenes()) {
                if (!scene.isActive()) continue;
                List<String> items = scene.getItemIds();
                if (items == null || !items.contains(ruleId)) continue;
                List<String> envs = scene.getEnvironments();
                if (envs != null) {
                    for (String env : envs) {
                        if (!inherited.contains(env)) inherited.add(env);
                    }
                }
            }
        }
        return inherited;
    }

    // --- Private helpers (moved verbatim from JdbcStorageService) ---

    private void syncSceneEnvironmentsToRules(RuleMapper rm, SceneMapper sm,
                                               SceneSet scene, List<String> oldEnvironments,
                                               List<String> oldItemIds, boolean wasActive) {
        List<String> newEnvironments = scene.getEnvironments() != null ? scene.getEnvironments() : Collections.<String>emptyList();
        List<String> currentItemIds = scene.getItemIds() != null ? scene.getItemIds() : Collections.<String>emptyList();
        boolean isActive = scene.isActive();

        Set<String> allRuleIds = new HashSet<>();
        allRuleIds.addAll(oldItemIds);
        allRuleIds.addAll(currentItemIds);

        Set<String> removedRuleIds = new HashSet<>(oldItemIds);
        removedRuleIds.removeAll(currentItemIds);

        for (String ruleId : allRuleIds) {
            Rule rule = rm.getRule(ruleId);
            if (rule == null) continue;

            List<String> ruleEnvs = new ArrayList<>(rule.getEnvironments() != null ? rule.getEnvironments() : Collections.<String>emptyList());

            if (wasActive && !isActive) {
                for (String env : oldEnvironments) {
                    boolean stillInherited = isEnvironmentInheritedFromOtherScene(sm, ruleId, env, scene.getId());
                    if (!stillInherited) {
                        ruleEnvs.remove(env);
                    }
                }
            } else if (removedRuleIds.contains(ruleId)) {
                if (wasActive) {
                    for (String env : oldEnvironments) {
                        boolean stillInherited = isEnvironmentInheritedFromOtherScene(sm, ruleId, env, scene.getId());
                        if (!stillInherited) {
                            ruleEnvs.remove(env);
                        }
                    }
                }
            } else {
                for (String oldEnv : oldEnvironments) {
                    if (!newEnvironments.contains(oldEnv)) {
                        boolean stillInherited = isEnvironmentInheritedFromOtherScene(sm, ruleId, oldEnv, scene.getId());
                        if (!stillInherited) {
                            ruleEnvs.remove(oldEnv);
                        }
                    }
                }

                if (isActive) {
                    for (String newEnv : newEnvironments) {
                        if (!ruleEnvs.contains(newEnv)) {
                            ruleEnvs.add(newEnv);
                        }
                    }
                }
            }

            rule.setEnvironments(ruleEnvs);
            rm.updateRule(rule);
        }
        invalidateCaches();
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

    private void invalidateCaches() {
        if (cacheInvalidator != null) {
            cacheInvalidator.invalidateRulesCache();
            cacheInvalidator.invalidateEnvironmentsCache();
        }
    }
}
