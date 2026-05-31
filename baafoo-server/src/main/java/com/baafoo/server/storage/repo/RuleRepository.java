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

public class RuleRepository {
    private static final Logger log = LoggerFactory.getLogger(RuleRepository.class);
    private final HikariDataSource dataSource;
    private final JsonColumnHelper json;

    public RuleRepository(HikariDataSource dataSource, JsonColumnHelper json) {
        this.dataSource = dataSource;
        this.json = json;
    }

    public List<Rule> listRules() {
        String sql = "SELECT * FROM rules ORDER BY priority ASC";
        List<Rule> result = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(mapRule(rs));
            }
        } catch (SQLException e) {
            log.error("Failed to list rules: {}", e.getMessage());
        }
        return result;
    }

    public Rule getRule(String id) {
        String sql = "SELECT * FROM rules WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapRule(rs);
            }
        } catch (SQLException e) {
            log.error("Failed to get rule {}: {}", id, e.getMessage());
        }
        return null;
    }

    public Rule createRule(Rule rule) {
        if (rule.getId() == null || rule.getId().isEmpty()) {
            rule.setId(IdGenerator.uuid());
        }
        rule.setVersion(1);
        long now = System.currentTimeMillis();
        rule.setCreatedAt(now);
        rule.setUpdatedAt(now);

        String sql = "INSERT INTO rules (id, name, protocol, service_name, host, port, " +
                "conditions_json, responses_json, enabled, priority, tags_json, environments_json, version, " +
                "created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, rule.getId());
            setRuleParams(ps, rule, 2);
            ps.executeUpdate();
            return rule;
        } catch (SQLException e) {
            log.error("Failed to create rule: {}", e.getMessage());
            return null;
        }
    }

    public Rule updateRule(String id, Rule update) {
        Rule existing = getRule(id);
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

        try (Connection conn = dataSource.getConnection()) {
            saveVersion(conn, id, existing);

            String sql = "UPDATE rules SET name=?, protocol=?, service_name=?, host=?, port=?, " +
                    "conditions_json=?, responses_json=?, enabled=?, priority=?, tags_json=?, " +
                    "environments_json=?, version=?, created_at=?, updated_at=? WHERE id=?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                setRuleParams(ps, existing, 1);
                ps.setString(15, id);
                ps.executeUpdate();
            }
            return existing;
        } catch (Exception e) {
            log.error("Failed to update rule {}: {}", id, e.getMessage());
            return null;
        }
    }

    public boolean deleteRule(String id) {
        try (Connection conn = dataSource.getConnection()) {
            try (PreparedStatement ps = conn.prepareStatement("DELETE FROM rule_history WHERE rule_id = ?")) {
                ps.setString(1, id);
                ps.executeUpdate();
            }

            String sql = "DELETE FROM rules WHERE id = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, id);
                return ps.executeUpdate() > 0;
            }
        } catch (SQLException e) {
            log.error("Failed to delete rule {}: {}", id, e.getMessage());
            return false;
        }
    }

    public boolean undoRule(String id) {
        try (Connection conn = dataSource.getConnection()) {
            String findSql = "SELECT rule_snapshot FROM rule_history " +
                    "WHERE rule_id = ? ORDER BY created_at DESC LIMIT 1";
            try (PreparedStatement ps = conn.prepareStatement(findSql)) {
                ps.setString(1, id);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) return false;
                    String snapshot = rs.getString("rule_snapshot");
                    Rule previous = json.fromJsonString(snapshot, Rule.class);
                    if (previous == null) return false;

                    String updateSql = "UPDATE rules SET name=?, protocol=?, service_name=?, " +
                            "host=?, port=?, conditions_json=?, responses_json=?, enabled=?, " +
                            "priority=?, tags_json=?, environments_json=?, version=?, created_at=?, updated_at=? WHERE id=?";
                    try (PreparedStatement ups = conn.prepareStatement(updateSql)) {
                        setRuleParams(ups, previous, 1);
                        ups.setString(15, id);
                        ups.executeUpdate();
                    }

                    long historyId = -1;
                    try (PreparedStatement hps = conn.prepareStatement(
                            "SELECT id FROM rule_history WHERE rule_id = ? ORDER BY created_at DESC LIMIT 1")) {
                        hps.setString(1, id);
                        try (ResultSet hrs = hps.executeQuery()) {
                            if (hrs.next()) {
                                historyId = hrs.getLong("id");
                            }
                        }
                    }
                    if (historyId != -1) {
                        try (PreparedStatement dps = conn.prepareStatement(
                                "DELETE FROM rule_history WHERE rule_id = ? AND id = ?")) {
                            dps.setString(1, id);
                            dps.setLong(2, historyId);
                            dps.executeUpdate();
                        }
                    }

                    return true;
                }
            }
        } catch (Exception e) {
            log.error("Failed to undo rule {}: {}", id, e.getMessage());
            return false;
        }
    }

    public List<RuleSet> listRuleSets() {
        String sql = "SELECT * FROM rule_sets";
        List<RuleSet> result = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(mapRuleSet(rs));
            }
        } catch (SQLException e) {
            log.error("Failed to list rule sets: {}", e.getMessage());
        }
        return result;
    }

    public RuleSet createRuleSet(RuleSet ruleSet) {
        if (ruleSet.getId() == null || ruleSet.getId().isEmpty()) {
            ruleSet.setId(IdGenerator.uuid());
        }
        long now = System.currentTimeMillis();
        ruleSet.setCreatedAt(now);
        ruleSet.setUpdatedAt(now);

        String sql = "INSERT INTO rule_sets (id, name, description, rule_ids_json, " +
                "enabled, tags_json, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, ruleSet.getId());
            setRuleSetInsertParams(ps, ruleSet);
            ps.executeUpdate();
            return ruleSet;
        } catch (SQLException e) {
            log.error("Failed to create rule set: {}", e.getMessage());
            return null;
        }
    }

    public boolean deleteRuleSet(String id) {
        String sql = "DELETE FROM rule_sets WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("Failed to delete rule set {}: {}", id, e.getMessage());
            return false;
        }
    }

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

    private void saveVersion(Connection conn, String ruleId, Rule previous) {
        try {
            String snapshot = json.toJson(previous);
            String sql = "INSERT INTO rule_history (rule_id, rule_snapshot, created_at) VALUES (?, ?, ?)";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, ruleId);
                ps.setString(2, snapshot);
                ps.setLong(3, System.currentTimeMillis());
                ps.executeUpdate();
            }
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
        List<MatchCondition> conditions = json.fromJson(rs, "conditions_json", new TypeReference<List<MatchCondition>>() {});
        if (conditions != null) r.setConditions(conditions);
        List<ResponseEntry> responses = json.fromJson(rs, "responses_json", new TypeReference<List<ResponseEntry>>() {});
        if (responses != null) r.setResponses(responses);
        List<String> tags = json.fromJson(rs, "tags_json", new TypeReference<List<String>>() {});
        if (tags != null) r.setTags(tags);
        List<String> environments = json.fromJson(rs, "environments_json", new TypeReference<List<String>>() {});
        if (environments != null) r.setEnvironments(environments);
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
        json.setJson(ps, offset + 5, r.getConditions());
        json.setJson(ps, offset + 6, r.getResponses());
        ps.setBoolean(offset + 7, r.isEnabled());
        ps.setInt(offset + 8, r.getPriority());
        json.setJson(ps, offset + 9, r.getTags());
        json.setJson(ps, offset + 10, r.getEnvironments());
        ps.setInt(offset + 11, r.getVersion());
        ps.setLong(offset + 12, r.getCreatedAt());
        ps.setLong(offset + 13, r.getUpdatedAt());
    }

    private RuleSet mapRuleSet(ResultSet rs) throws SQLException {
        RuleSet rs2 = new RuleSet();
        rs2.setId(rs.getString("id"));
        rs2.setName(rs.getString("name"));
        rs2.setDescription(rs.getString("description"));
        rs2.setEnabled(rs.getBoolean("enabled"));
        rs2.setCreatedAt(rs.getLong("created_at"));
        rs2.setUpdatedAt(rs.getLong("updated_at"));
        List<String> ruleIds = json.fromJson(rs, "rule_ids_json", new TypeReference<List<String>>() {});
        if (ruleIds != null) rs2.setRuleIds(ruleIds);
        List<String> tags = json.fromJson(rs, "tags_json", new TypeReference<List<String>>() {});
        if (tags != null) rs2.setTags(tags);
        return rs2;
    }

    private void setRuleSetInsertParams(PreparedStatement ps, RuleSet rs) throws SQLException {
        ps.setString(2, rs.getName());
        ps.setString(3, rs.getDescription());
        json.setJson(ps, 4, rs.getRuleIds());
        ps.setBoolean(5, rs.isEnabled());
        json.setJson(ps, 6, rs.getTags());
        ps.setLong(7, rs.getCreatedAt());
        ps.setLong(8, rs.getUpdatedAt());
    }
}
