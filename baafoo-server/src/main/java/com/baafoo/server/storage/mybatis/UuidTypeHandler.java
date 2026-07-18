package com.baafoo.server.storage.mybatis;

import org.apache.ibatis.type.BaseTypeHandler;
import org.apache.ibatis.type.JdbcType;
import org.apache.ibatis.type.MappedJdbcTypes;
import org.apache.ibatis.type.MappedTypes;

import java.sql.CallableStatement;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

/**
 * MyBatis TypeHandler that bridges a Java {@code String} property and a
 * database {@code UUID} column.
 *
 * <p>Without an explicit handler, MyBatis falls back to its default
 * {@code StringTypeHandler}, which reads via {@code ResultSet.getString(...)}. On
 * most drivers this works because the JDBC layer converts {@code UUID} to its
 * canonical string form, but the behaviour is driver-dependent: PostgreSQL's
 * driver returns {@code java.util.UUID} from {@code getObject(...)} (and some
 * code paths use {@code getObject} internally), which can cause
 * {@code ClassCastException} when assigning to a String property.</p>
 *
 * <p>This handler reads via {@code getObject(...).toString()} (works uniformly
 * across H2 and PostgreSQL, regardless of whether the driver returns a String
 * or a UUID), and writes via {@code setObject(..., UUID)} so the driver can use
 * the binary UUID protocol on PostgreSQL instead of parsing a string literal.</p>
 *
 * <p>Currently applied to {@code user_account.external_id}. The DB column is
 * always populated by a server-side default ({@code uuid_generate_v4()} on
 * PostgreSQL / {@code RANDOM_UUID()} on H2), so {@code setNonNullParameter} is
 * only exercised if application code ever starts assigning external_id
 * explicitly (e.g. for cross-system correlation).</p>
 */
@MappedTypes(String.class)
@MappedJdbcTypes({JdbcType.OTHER, JdbcType.VARCHAR})
public class UuidTypeHandler extends BaseTypeHandler<String> {

    @Override
    public void setNonNullParameter(PreparedStatement ps, int i, String parameter, JdbcType jdbcType) throws SQLException {
        // Bind as a proper UUID instance so PostgreSQL uses the UUID wire
        // protocol rather than relying on driver-side string→UUID coercion.
        ps.setObject(i, UUID.fromString(parameter));
    }

    @Override
    public String getNullableResult(ResultSet rs, String columnName) throws SQLException {
        Object value = rs.getObject(columnName);
        return value == null ? null : value.toString();
    }

    @Override
    public String getNullableResult(ResultSet rs, int columnIndex) throws SQLException {
        Object value = rs.getObject(columnIndex);
        return value == null ? null : value.toString();
    }

    @Override
    public String getNullableResult(CallableStatement cs, int columnIndex) throws SQLException {
        Object value = cs.getObject(columnIndex);
        return value == null ? null : value.toString();
    }
}
