package com.baafoo.server.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class JsonColumnHelper {
    private static final Logger log = LoggerFactory.getLogger(JsonColumnHelper.class);
    private final ObjectMapper mapper;

    public JsonColumnHelper(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public String toJson(Object value) {
        if (value == null) return null;
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            log.error("Failed to serialize to JSON: {}", e.getMessage());
            return null;
        }
    }

    public <T> T fromJson(ResultSet rs, String column, TypeReference<T> typeRef) throws SQLException {
        String json = rs.getString(column);
        if (json == null || json.isEmpty()) return null;
        try {
            return mapper.readValue(json, typeRef);
        } catch (Exception e) {
            log.warn("Failed to deserialize JSON column {}: {}", column, e.getMessage());
            return null;
        }
    }

    public void setJson(PreparedStatement ps, int index, Object value) throws SQLException {
        ps.setString(index, toJson(value));
    }

    public <T> T fromJsonString(String json, Class<T> clazz) {
        if (json == null || json.isEmpty()) return null;
        try {
            return mapper.readValue(json, clazz);
        } catch (Exception e) {
            log.warn("Failed to deserialize JSON string: {}", e.getMessage());
            return null;
        }
    }
}
