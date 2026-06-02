package com.baafoo.server.storage.mybatis;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * MyBatis TypeHandler that serializes/deserializes Java objects as JSON TEXT columns.
 * This handler is generic and works with any object type via Jackson.
 */
@MappedJdbcTypes(JdbcType.VARCHAR)
public class JsonTypeHandler extends BaseTypeHandler<Object> {

    private static final Logger log = LoggerFactory.getLogger(JsonTypeHandler.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final Class<?> javaType;
    private final TypeReference<?> typeReference;

    /**
     * No-arg constructor required by MyBatis TypeHandlerRegistry.
     */
    public JsonTypeHandler() {
        this.javaType = Object.class;
        this.typeReference = null;
    }

    /**
     * Constructor used by MyBatis when specifying javaType in mapper XML.
     */
    public JsonTypeHandler(Class<?> javaType) {
        this.javaType = javaType;
        this.typeReference = null;
    }

    /**
     * Constructor for explicit TypeReference usage (e.g. List&lt;String&gt;, Map&lt;String,String&gt;).
     */
    public JsonTypeHandler(TypeReference<?> typeReference) {
        this.javaType = Object.class;
        this.typeReference = typeReference;
    }

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, Object parameter, JdbcType jdbcType) throws SQLException {
        try {
            ps.setString(i, MAPPER.writeValueAsString(parameter));
        } catch (Exception e) {
            log.error("Failed to serialize JSON parameter: {}", e.getMessage());
            ps.setString(i, null);
        }
    }

    @Override
    public Object getNullableResult(ResultSet rs, String columnName) throws SQLException {
        return parseJson(rs.getString(columnName));
    }

    @Override
    public Object getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        return parseJson(rs.getString(columnIndex));
    }

    @Override
    public Object getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        return parseJson(cs.getString(columnIndex));
    }

    private Object parseJson(String json) {
        if (json == null || json.isEmpty()) return null;
        try {
            if (typeReference != null) {
                return MAPPER.readValue(json, typeReference);
            }
            return MAPPER.readValue(json, javaType);
        } catch (Exception e) {
            log.warn("Failed to deserialize JSON: {}", e.getMessage());
            return null;
        }
    }
}
