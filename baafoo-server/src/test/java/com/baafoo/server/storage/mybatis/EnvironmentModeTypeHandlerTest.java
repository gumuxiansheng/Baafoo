package com.baafoo.server.storage.mybatis;

import com.baafoo.core.model.EnvironmentMode;
import org.apache.ibatis.type.JdbcType;
import org.junit.Test;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class EnvironmentModeTypeHandlerTest {

    private final EnvironmentModeTypeHandler handler = new EnvironmentModeTypeHandler();

    @Test
    public void setNonNullParameterWritesValue() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);

        handler.setNonNullParameter(ps, 1, EnvironmentMode.STUB, JdbcType.VARCHAR);

        verify(ps).setString(1, "stub");
    }

    @Test
    public void getNullableResultByColumnReturnsMode() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("mode")).thenReturn("record");

        EnvironmentMode result = handler.getResult(rs, "mode");

        assertEquals(EnvironmentMode.RECORD, result);
    }

    @Test
    public void getNullableResultByColumnReturnsNull() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("mode")).thenReturn(null);

        assertNull(handler.getResult(rs, "mode"));
    }

    @Test
    public void getNullableResultByIndexReturnsMode() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString(1)).thenReturn("passthrough");

        EnvironmentMode result = handler.getResult(rs, 1);

        assertEquals(EnvironmentMode.PASSTHROUGH, result);
    }

    @Test
    public void getNullableResultByIndexReturnsNull() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString(1)).thenReturn(null);

        assertNull(handler.getResult(rs, 1));
    }

    @Test
    public void getNullableResultWithCallableStatement() throws Exception {
        java.sql.CallableStatement cs = mock(java.sql.CallableStatement.class);
        when(cs.getString(1)).thenReturn("record_and_stub");

        EnvironmentMode result = handler.getResult(cs, 1);

        assertEquals(EnvironmentMode.RECORD_AND_STUB, result);
    }

    @Test
    public void getNullableResultWithCallableStatementReturnsNull() throws Exception {
        java.sql.CallableStatement cs = mock(java.sql.CallableStatement.class);
        when(cs.getString(1)).thenReturn(null);

        assertNull(handler.getResult(cs, 1));
    }
}
