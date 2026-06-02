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

public class RecordingRepository {
    private static final Logger log = LoggerFactory.getLogger(RecordingRepository.class);
    private final HikariDataSource dataSource;
    private final JsonColumnHelper json;

    public RecordingRepository(HikariDataSource dataSource, JsonColumnHelper json) {
        this.dataSource = dataSource;
        this.json = json;
    }

    public List<RecordingEntry> listRecordings(String ruleId, int limit) {
        StringBuilder sql = new StringBuilder("SELECT * FROM recordings");
        boolean filterByRuleId = ruleId != null && !ruleId.isEmpty();
        if (filterByRuleId) {
            sql.append(" WHERE rule_id = ?");
        }
        sql.append(" ORDER BY recorded_at DESC LIMIT ?");
        List<RecordingEntry> result = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            if (filterByRuleId) {
                ps.setString(idx++, ruleId);
            }
            ps.setInt(idx, limit);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(mapRecording(rs));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to list recordings: {}", e.getMessage());
        }
        return result;
    }

    /**
     * Count recordings matching the optional ruleId filter.
     */
    public long countRecordings(String ruleId) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM recordings");
        boolean filterByRuleId = ruleId != null && !ruleId.isEmpty();
        if (filterByRuleId) {
            sql.append(" WHERE rule_id = ?");
        }
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            if (filterByRuleId) {
                ps.setString(1, ruleId);
            }
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        } catch (SQLException e) {
            log.error("Failed to count recordings: {}", e.getMessage());
        }
        return 0;
    }

    /**
     * List recordings with pagination.
     *
     * @param ruleId optional rule ID filter (null/empty = no filter)
     * @param page   1-based page number
     * @param size   page size
     * @return list of recordings for the given page
     */
    public List<RecordingEntry> listRecordingsPaged(String ruleId, int page, int size) {
        StringBuilder sql = new StringBuilder("SELECT * FROM recordings");
        boolean filterByRuleId = ruleId != null && !ruleId.isEmpty();
        if (filterByRuleId) {
            sql.append(" WHERE rule_id = ?");
        }
        sql.append(" ORDER BY recorded_at DESC LIMIT ? OFFSET ?");
        int offset = (page - 1) * size;
        List<RecordingEntry> result = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql.toString())) {
            int idx = 1;
            if (filterByRuleId) {
                ps.setString(idx++, ruleId);
            }
            ps.setInt(idx++, size);
            ps.setInt(idx, offset);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    result.add(mapRecording(rs));
                }
            }
        } catch (SQLException e) {
            log.error("Failed to list recordings paged: {}", e.getMessage());
        }
        return result;
    }

    public void addRecording(RecordingEntry recording) {
        if (recording.getId() == null || recording.getId().isEmpty()) {
            recording.setId(IdGenerator.uuid());
        }
        recording.setRecordedAt(System.currentTimeMillis());
        try (Connection conn = dataSource.getConnection()) {
            insertRecording(conn, recording);
            trimRecordings(conn);
        } catch (SQLException e) {
            log.error("Failed to add recording: {}", e.getMessage());
        }
    }

    public void addRecordings(List<RecordingEntry> batch) {
        try (Connection conn = dataSource.getConnection()) {
            conn.setAutoCommit(false);
            for (RecordingEntry r : batch) {
                if (r.getId() == null || r.getId().isEmpty()) {
                    r.setId(IdGenerator.uuid());
                }
                r.setRecordedAt(System.currentTimeMillis());
                insertRecording(conn, r);
            }
            conn.commit();
            conn.setAutoCommit(true);
            trimRecordings(conn);
        } catch (Exception e) {
            log.error("Failed to batch insert recordings: {}", e.getMessage());
        }
    }

    public boolean deleteRecording(String id) {
        String sql = "DELETE FROM recordings WHERE id = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, id);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("Failed to delete recording {}: {}", id, e.getMessage());
            return false;
        }
    }

    private void insertRecording(Connection conn, RecordingEntry r) {
        String sql = "INSERT INTO recordings (id, rule_id, environment_id, agent_id, protocol, " +
                "host, port, service_name, method, path, request_headers_json, request_body, " +
                "response_status_code, response_headers_json, response_body, response_time_ms, " +
                "recorded_at, tags_json) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, r.getId());
            ps.setString(2, r.getRuleId());
            ps.setString(3, r.getEnvironmentId());
            ps.setString(4, r.getAgentId());
            ps.setString(5, r.getProtocol());
            ps.setString(6, r.getHost());
            ps.setInt(7, r.getPort());
            ps.setString(8, r.getServiceName());
            ps.setString(9, r.getMethod());
            ps.setString(10, r.getPath());
            json.setJson(ps, 11, r.getRequestHeaders());
            ps.setString(12, r.getRequestBody());
            ps.setInt(13, r.getResponseStatusCode());
            json.setJson(ps, 14, r.getResponseHeaders());
            ps.setString(15, r.getResponseBody());
            ps.setLong(16, r.getResponseTimeMs());
            ps.setLong(17, r.getRecordedAt());
            json.setJson(ps, 18, r.getTags());
            ps.executeUpdate();
        } catch (Exception e) {
            log.error("Failed to insert recording: {}", e.getMessage());
        }
    }

    private void trimRecordings(Connection conn) {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM recordings WHERE id NOT IN " +
                "(SELECT id FROM recordings ORDER BY recorded_at DESC LIMIT 1000)")) {
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("Failed to trim recordings: {}", e.getMessage());
        }
    }

    private RecordingEntry mapRecording(ResultSet rs) throws SQLException {
        RecordingEntry r = new RecordingEntry();
        r.setId(rs.getString("id"));
        r.setRuleId(rs.getString("rule_id"));
        r.setEnvironmentId(rs.getString("environment_id"));
        r.setAgentId(rs.getString("agent_id"));
        r.setProtocol(rs.getString("protocol"));
        r.setHost(rs.getString("host"));
        r.setPort(rs.getInt("port"));
        r.setServiceName(rs.getString("service_name"));
        r.setMethod(rs.getString("method"));
        r.setPath(rs.getString("path"));
        r.setRequestBody(rs.getString("request_body"));
        r.setResponseStatusCode(rs.getInt("response_status_code"));
        r.setResponseBody(rs.getString("response_body"));
        r.setResponseTimeMs(rs.getLong("response_time_ms"));
        r.setRecordedAt(rs.getLong("recorded_at"));
        Map<String, String> reqHeaders = json.fromJson(rs, "request_headers_json", new TypeReference<Map<String, String>>() {});
        if (reqHeaders != null) r.setRequestHeaders(reqHeaders);
        Map<String, String> respHeaders = json.fromJson(rs, "response_headers_json", new TypeReference<Map<String, String>>() {});
        if (respHeaders != null) r.setResponseHeaders(respHeaders);
        Map<String, String> tags = json.fromJson(rs, "tags_json", new TypeReference<Map<String, String>>() {});
        if (tags != null) r.setTags(tags);
        return r;
    }
}
