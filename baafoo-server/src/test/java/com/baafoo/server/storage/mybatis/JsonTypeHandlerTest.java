package com.baafoo.server.storage.mybatis;

import com.fasterxml.jackson.core.type.TypeReference;
import org.apache.ibatis.type.JdbcType;
import org.junit.Test;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class JsonTypeHandlerTest {

    @Test
    public void noArgConstructorUsesObjectClass() {
        JsonTypeHandler handler = new JsonTypeHandler();
        assertNotNull(handler);
    }

    @Test
    public void classConstructorStoresType() {
        JsonTypeHandler handler = new JsonTypeHandler(String.class);
        assertNotNull(handler);
    }

    @Test
    public void typeReferenceConstructorStoresRef() {
        JsonTypeHandler handler = new JsonTypeHandler(new TypeReference<List<String>>() {});
        assertNotNull(handler);
    }

    @Test
    public void setNonNullParameterSerializesObject() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        JsonTypeHandler handler = new JsonTypeHandler();

        Map<String, String> input = new HashMap<>();
        input.put("key", "value");
        handler.setNonNullParameter(ps, 1, input, JdbcType.VARCHAR);

        verify(ps).setString(eq(1), eq("{\"key\":\"value\"}"));
    }

    @Test
    public void setNonNullParameterSerializesNullOnError() throws Exception {
        PreparedStatement ps = mock(PreparedStatement.class);
        JsonTypeHandler handler = new JsonTypeHandler();

        handler.setNonNullParameter(ps, 1, new Object() {
            @Override
            public String toString() { throw new RuntimeException("fail"); }
        }, JdbcType.VARCHAR);

        verify(ps).setString(eq(1), isNull());
    }

    @Test
    public void getNullableResultByColumnReturnsParsedObject() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("data")).thenReturn("{\"name\":\"test\"}");
        JsonTypeHandler handler = new JsonTypeHandler(Map.class);

        @SuppressWarnings("unchecked")
        Map<String, String> result = (Map<String, String>) handler.getResult(rs, "data");

        assertNotNull(result);
        assertEquals("test", result.get("name"));
    }

    @Test
    public void getNullableResultByIndexReturnsParsedObject() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString(1)).thenReturn("42");
        JsonTypeHandler handler = new JsonTypeHandler(Integer.class);

        Object result = handler.getResult(rs, 1);

        assertEquals(42, result);
    }

    @Test
    public void getNullableResultReturnsNullForNullString() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("data")).thenReturn(null);
        JsonTypeHandler handler = new JsonTypeHandler(String.class);

        assertNull(handler.getResult(rs, "data"));
    }

    @Test
    public void getNullableResultReturnsNullForEmptyString() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("data")).thenReturn("");
        JsonTypeHandler handler = new JsonTypeHandler(String.class);

        assertNull(handler.getResult(rs, "data"));
    }

    @Test
    public void getNullableResultReturnsNullForMalformedJson() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("data")).thenReturn("{invalid}");
        JsonTypeHandler handler = new JsonTypeHandler(String.class);

        assertNull(handler.getResult(rs, "data"));
    }

    @Test
    public void getNullableResultWithTypeReferenceParsesGenericType() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getString("data")).thenReturn("[\"a\",\"b\",\"c\"]");
        JsonTypeHandler handler = new JsonTypeHandler(new TypeReference<List<String>>() {});

        @SuppressWarnings("unchecked")
        List<String> result = (List<String>) handler.getResult(rs, "data");

        assertEquals(Arrays.asList("a", "b", "c"), result);
    }

    @Test
    public void getNullableResultWithCallableStatement() throws Exception {
        java.sql.CallableStatement cs = mock(java.sql.CallableStatement.class);
        when(cs.getString(1)).thenReturn("true");
        JsonTypeHandler handler = new JsonTypeHandler(Boolean.class);

        Object result = handler.getResult(cs, 1);

        assertEquals(true, result);
    }
}
