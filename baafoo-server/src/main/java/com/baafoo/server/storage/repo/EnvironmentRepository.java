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

public class EnvironmentRepository {
    private static final Logger log = LoggerFactory.getLogger(EnvironmentRepository.class);
    private final HikariDataSource dataSource;
    private final JsonColumnHelper json;

    public EnvironmentRepository(HikariDataSource dataSource, JsonColumnHelper json) {
        this.dataSource = dataSource;
        this.json = json;
    }

    public List<Environment> listEnvironments() {
        String sql = "SELECT * FROM environments";
        List<Environment> result = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(mapEnvironment(rs));
            }
        } catch (SQLException e) {
            log.error("Failed to list environments: {}", e.getMessage());
        }
        return result;
    }

    public Environment getEnvironment(String id) {
        String sql = "SELECT * FROM environments WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapEnvironment(rs);
            }
        } catch (SQLException e) {
            log.error("Failed to get environment {}: {}", id, e.getMessage());
        }
        return null;
    }

    public Environment getEnvironmentByName(String name) {
        String sql = "SELECT * FROM environments WHERE name = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, name);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapEnvironment(rs);
            }
        } catch (SQLException e) {
            log.error("Failed to get environment by name {}: {}", name, e.getMessage());
        }
        return null;
    }

    public Environment createEnvironment(Environment env) {
        if (env.getId() == null || env.getId().isEmpty()) {
            env.setId(IdGenerator.uuid());
        }
        long now = System.currentTimeMillis();
        env.setCreatedAt(now);
        env.setUpdatedAt(now);

        String sql = "INSERT INTO environments (id, name, mode, agent_ids_json, variables_json, " +
                "metadata_json, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, env.getId());
            setEnvironmentParams(ps, env, 2);
            ps.executeUpdate();
            return env;
        } catch (SQLException e) {
            log.error("Failed to create environment: {}", e.getMessage());
            return null;
        }
    }

    public Environment updateEnvironment(String id, Environment update) {
        Environment existing = getEnvironment(id);
        if (existing == null) return null;

        if (update.getName() != null) existing.setName(update.getName());
        if (update.getMode() != null) existing.setMode(update.getMode());
        if (update.getVariables() != null) existing.setVariables(update.getVariables());
        if (update.getMetadata() != null) existing.setMetadata(update.getMetadata());
        existing.setUpdatedAt(System.currentTimeMillis());

        String sql = "UPDATE environments SET name=?, mode=?, agent_ids_json=?, " +
                "variables_json=?, metadata_json=?, updated_at=? WHERE id=?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            setEnvironmentUpdateParams(ps, existing);
            ps.setString(7, id);
            ps.executeUpdate();
            return existing;
        } catch (SQLException e) {
            log.error("Failed to update environment {}: {}", id, e.getMessage());
            return null;
        }
    }

    public boolean deleteEnvironment(String id) {
        String sql = "DELETE FROM environments WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("Failed to delete environment {}: {}", id, e.getMessage());
            return false;
        }
    }

    private Environment mapEnvironment(ResultSet rs) throws SQLException {
        Environment env = new Environment();
        env.setId(rs.getString("id"));
        env.setName(rs.getString("name"));
        env.setMode(EnvironmentMode.fromValue(rs.getString("mode")));
        env.setCreatedAt(rs.getLong("created_at"));
        env.setUpdatedAt(rs.getLong("updated_at"));
        List<String> agentIds = json.fromJson(rs, "agent_ids_json", new TypeReference<List<String>>() {});
        if (agentIds != null) env.setAgentIds(agentIds);
        Map<String, String> variables = json.fromJson(rs, "variables_json", new TypeReference<Map<String, String>>() {});
        if (variables != null) env.setVariables(variables);
        Map<String, String> metadata = json.fromJson(rs, "metadata_json", new TypeReference<Map<String, String>>() {});
        if (metadata != null) env.setMetadata(metadata);
        return env;
    }

    private void setEnvironmentParams(PreparedStatement ps, Environment env, int offset) throws SQLException {
        ps.setString(offset, env.getName());
        ps.setString(offset + 1, env.getMode().getValue());
        json.setJson(ps, offset + 2, env.getAgentIds());
        json.setJson(ps, offset + 3, env.getVariables());
        json.setJson(ps, offset + 4, env.getMetadata());
        ps.setLong(offset + 5, env.getCreatedAt());
        ps.setLong(offset + 6, env.getUpdatedAt());
    }

    private void setEnvironmentUpdateParams(PreparedStatement ps, Environment env) throws SQLException {
        ps.setString(1, env.getName());
        ps.setString(2, env.getMode().getValue());
        json.setJson(ps, 3, env.getAgentIds());
        json.setJson(ps, 4, env.getVariables());
        json.setJson(ps, 5, env.getMetadata());
        ps.setLong(6, env.getUpdatedAt());
    }
}
