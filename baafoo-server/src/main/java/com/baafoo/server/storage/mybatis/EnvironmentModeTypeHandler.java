package com.baafoo.server.storage.mybatis;

import com.baafoo.core.model.EnvironmentMode;
import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * MyBatis TypeHandler for EnvironmentMode enum.
 * Converts between EnvironmentMode enum and its string value in the database.
 */
@MappedTypes(EnvironmentMode.class)
@MappedJdbcTypes(JdbcType.VARCHAR)
public class EnvironmentModeTypeHandler extends BaseTypeHandler<EnvironmentMode> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, EnvironmentMode parameter, JdbcType jdbcType) throws SQLException {
        ps.setString(i, parameter.getValue());
    }

    @Override
    public EnvironmentMode getNullableResult(ResultSet rs, String columnName) throws SQLException {
        String value = rs.getString(columnName);
        return value == null ? null : EnvironmentMode.fromValue(value);
    }

    @Override
    public EnvironmentMode getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        String value = rs.getString(columnIndex);
        return value == null ? null : EnvironmentMode.fromValue(value);
    }

    @Override
    public EnvironmentMode getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        String value = cs.getString(columnIndex);
        return value == null ? null : EnvironmentMode.fromValue(value);
    }
}
