package com.baafoo.server.storage.repo;

import com.baafoo.core.model.*;
import com.baafoo.server.storage.JsonColumnHelper;
import com.baafoo.server.storage.StorageService.AgentRegistration;
import com.fasterxml.jackson.core.type.TypeReference;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.*;

public class AgentRepository {
    private static final Logger log = LoggerFactory.getLogger(AgentRepository.class);
    private final HikariDataSource dataSource;
    private final JsonColumnHelper json;
    private final EnvironmentRepository envRepo;

    public AgentRepository(HikariDataSource dataSource, JsonColumnHelper json, EnvironmentRepository envRepo) {
        this.dataSource = dataSource;
        this.json = json;
        this.envRepo = envRepo;
    }

    public AgentRegistration registerAgent(String agentId, String environment, String hostname,
                                           String version, List<String> protocols) {
        AgentRegistration reg = new AgentRegistration();
        reg.agentId = agentId;
        reg.environment = environment;
        reg.hostname = hostname;
        reg.version = version;
        reg.protocols = protocols;
        reg.registeredAt = System.currentTimeMillis();
        reg.lastHeartbeat = System.currentTimeMillis();

        String sql = "MERGE INTO agents (agent_id, environment, hostname, version, " +
                "protocols_json, registered_at, last_heartbeat) KEY(agent_id) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, agentId);
            ps.setString(2, environment);
            ps.setString(3, hostname);
            ps.setString(4, version);
            json.setJson(ps, 5, protocols);
            ps.setLong(6, reg.registeredAt);
            ps.setLong(7, reg.lastHeartbeat);
            ps.executeUpdate();
        } catch (Exception e) {
            log.error("Failed to register agent {}: {}", agentId, e.getMessage());
        }

        Environment env = envRepo.getEnvironmentByName(environment);
        if (env != null && !env.getAgentIds().contains(agentId)) {
            env.getAgentIds().add(agentId);
            envRepo.updateEnvironment(env.getId(), env);
        }

        return reg;
    }

    public void agentHeartbeat(String agentId) {
        String sql = "UPDATE agents SET last_heartbeat = ? WHERE agent_id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, agentId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.error("Failed to update heartbeat for agent {}: {}", agentId, e.getMessage());
        }
    }

    public List<AgentRegistration> listAgents() {
        String sql = "SELECT * FROM agents";
        List<AgentRegistration> result = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(mapAgent(rs));
            }
        } catch (SQLException e) {
            log.error("Failed to list agents: {}", e.getMessage());
        }
        return result;
    }

    public List<AgentRegistration> getAgentsForEnvironment(String envName) {
        String sql = "SELECT * FROM agents WHERE environment = ?";
        List<AgentRegistration> result = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, envName);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(mapAgent(rs));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to get agents for environment {}: {}", envName, e.getMessage());
        }
        return result;
    }

    private AgentRegistration mapAgent(ResultSet rs) throws SQLException {
        AgentRegistration reg = new AgentRegistration();
        reg.agentId = rs.getString("agent_id");
        reg.environment = rs.getString("environment");
        reg.hostname = rs.getString("hostname");
        reg.version = rs.getString("version");
        reg.registeredAt = rs.getLong("registered_at");
        reg.lastHeartbeat = rs.getLong("last_heartbeat");
        List<String> protocols = json.fromJson(rs, "protocols_json", new TypeReference<List<String>>() {});
        if (protocols != null) reg.protocols = protocols;
        return reg;
    }
}
