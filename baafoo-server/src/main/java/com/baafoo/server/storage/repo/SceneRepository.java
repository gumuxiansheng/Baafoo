package com.baafoo.server.storage.repo;

import com.baafoo.core.model.*;
import com.baafoo.core.util.IdGenerator;
import com.baafoo.server.storage.JsonColumnHelper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.*;

public class SceneRepository {
    private static final Logger log = LoggerFactory.getLogger(SceneRepository.class);
    private final HikariDataSource dataSource;
    private final JsonColumnHelper json;
    private final RuleRepository ruleRepo;

    public SceneRepository(HikariDataSource dataSource, JsonColumnHelper json, RuleRepository ruleRepo) {
        this.dataSource = dataSource;
        this.json = json;
        this.ruleRepo = ruleRepo;
    }

    public List<SceneSet> listScenes() {
        String sql = "SELECT * FROM scene_sets";
        List<SceneSet> result = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(mapSceneSet(rs));
            }
        } catch (SQLException e) {
            log.error("Failed to list scenes: {}", e.getMessage());
        }
        return result;
    }

    public SceneSet getScene(String id) {
        String sql = "SELECT * FROM scene_sets WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapSceneSet(rs);
            }
        } catch (SQLException e) {
            log.error("Failed to get scene {}: {}", id, e.getMessage());
        }
        return null;
    }

    public SceneSet createScene(SceneSet scene) {
        if (scene.getId() == null || scene.getId().isEmpty()) {
            scene.setId(IdGenerator.uuid());
        }
        long now = System.currentTimeMillis();
        scene.setCreatedAt(now);
        scene.setUpdatedAt(now);

        String sql = "INSERT INTO scene_sets (id, name, description, item_ids_json, " +
                "active, tags_json, environments_json, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, scene.getId());
            setSceneSetInsertParams(ps, scene);
            ps.executeUpdate();
            return scene;
        } catch (SQLException e) {
            log.error("Failed to create scene: {}", e.getMessage());
            return null;
        }
    }

    public SceneSet updateScene(String id, SceneSet update) {
        SceneSet existing = getScene(id);
        if (existing == null) return null;

        List<String> oldEnvironments = existing.getEnvironments() != null
                ? new ArrayList<>(existing.getEnvironments())
                : new ArrayList<>();
        List<String> oldItemIds = existing.getItemIds() != null
                ? new ArrayList<>(existing.getItemIds())
                : new ArrayList<>();

        if (update.getName() != null) existing.setName(update.getName());
        if (update.getDescription() != null) existing.setDescription(update.getDescription());
        if (update.getItemIds() != null) existing.setItemIds(update.getItemIds());
        if (update.getEnvironments() != null) existing.setEnvironments(update.getEnvironments());
        existing.setActive(update.isActive());
        existing.setUpdatedAt(System.currentTimeMillis());

        String sql = "UPDATE scene_sets SET name=?, description=?, item_ids_json=?, " +
                "active=?, tags_json=?, environments_json=?, updated_at=? WHERE id=?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            setSceneSetParams(ps, existing);
            ps.setString(8, id);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to update scene {}: {}", id, e.getMessage());
            return null;
        }

        syncSceneEnvironmentsToRules(existing, oldEnvironments, oldItemIds);

        return existing;
    }

    public boolean deleteScene(String id) {
        String sql = "DELETE FROM scene_sets WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("Failed to delete scene {}: {}", id, e.getMessage());
            return false;
        }
    }

    private void syncSceneEnvironmentsToRules(SceneSet scene, List<String> oldEnvironments, List<String> oldItemIds) {
        List<String> newEnvironments = scene.getEnvironments() != null ? scene.getEnvironments() : Collections.<String>emptyList();
        List<String> currentItemIds = scene.getItemIds() != null ? scene.getItemIds() : Collections.<String>emptyList();

        Set<String> allRuleIds = new HashSet<>();
        allRuleIds.addAll(oldItemIds);
        allRuleIds.addAll(currentItemIds);

        for (String ruleId : allRuleIds) {
            Rule rule = ruleRepo.getRule(ruleId);
            if (rule == null) continue;

            List<String> ruleEnvs = new ArrayList<>(rule.getEnvironments() != null ? rule.getEnvironments() : Collections.<String>emptyList());

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
            ruleRepo.updateRule(ruleId, rule);
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

    private SceneSet mapSceneSet(ResultSet rs) throws SQLException {
        SceneSet s = new SceneSet();
        s.setId(rs.getString("id"));
        s.setName(rs.getString("name"));
        s.setDescription(rs.getString("description"));
        s.setActive(rs.getBoolean("active"));
        s.setCreatedAt(rs.getLong("created_at"));
        s.setUpdatedAt(rs.getLong("updated_at"));
        List<String> itemIds = json.fromJson(rs, "item_ids_json", new TypeReference<List<String>>() {});
        if (itemIds != null) s.setItemIds(itemIds);
        List<String> tags = json.fromJson(rs, "tags_json", new TypeReference<List<String>>() {});
        if (tags != null) s.setTags(tags);
        List<String> environments = json.fromJson(rs, "environments_json", new TypeReference<List<String>>() {});
        if (environments != null) s.setEnvironments(environments);
        return s;
    }

    private void setSceneSetInsertParams(PreparedStatement ps, SceneSet s) throws SQLException {
        ps.setString(2, s.getName());
        ps.setString(3, s.getDescription());
        json.setJson(ps, 4, s.getItemIds());
        ps.setBoolean(5, s.isActive());
        json.setJson(ps, 6, s.getTags());
        json.setJson(ps, 7, s.getEnvironments());
        ps.setLong(8, s.getCreatedAt());
        ps.setLong(9, s.getUpdatedAt());
    }

    private void setSceneSetParams(PreparedStatement ps, SceneSet s) throws SQLException {
        ps.setString(1, s.getName());
        ps.setString(2, s.getDescription());
        json.setJson(ps, 3, s.getItemIds());
        ps.setBoolean(4, s.isActive());
        json.setJson(ps, 5, s.getTags());
        json.setJson(ps, 6, s.getEnvironments());
        ps.setLong(7, s.getUpdatedAt());
    }
}
