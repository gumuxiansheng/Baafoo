package com.baafoo.server.mcp;

import com.baafoo.server.auth.AuthService;
import com.baafoo.server.storage.StorageService;
import org.junit.Test;

import java.util.*;

import static org.junit.Assert.*;
import static org.mockito.Mockito.mock;

public class McpToolContextTest {

    private McpToolContext ctx(String role) {
        return new McpToolContext(mock(StorageService.class), mock(AuthService.class), role, "user1");
    }

    // --- Constructor / getters ---

    @Test
    public void gettersReturnConstructorValues() {
        StorageService storage = mock(StorageService.class);
        AuthService auth = mock(AuthService.class);
        McpToolContext ctx = new McpToolContext(storage, auth, "admin", "alice");
        assertSame(storage, ctx.getStorage());
        assertSame(auth, ctx.getAuthService());
        assertEquals("admin", ctx.getRole());
        assertEquals("alice", ctx.getUsername());
    }

    // --- requirePermission ---

    @Test
    public void requirePermissionPassesWhenAllowed() {
        McpToolContext ctx = ctx("admin");
        ctx.requirePermission("rules", "read");
    }

    @Test(expected = McpException.class)
    public void requirePermissionThrowsWhenDenied() {
        McpToolContext ctx = ctx("viewer");
        ctx.requirePermission("rules", "delete");
    }

    // --- requireAdmin ---

    @Test
    public void requireAdminPassesForAdmin() {
        ctx("admin").requireAdmin();
    }

    @Test(expected = McpException.class)
    public void requireAdminThrowsForNonAdmin() {
        ctx("viewer").requireAdmin();
    }

    // --- getString ---

    @Test
    public void getStringReturnsValue() {
        Map<String, Object> args = new HashMap<>();
        args.put("key", "val");
        assertEquals("val", McpToolContext.getString(args, "key"));
    }

    @Test
    public void getStringReturnsNullForMissing() {
        assertNull(McpToolContext.getString(Collections.emptyMap(), "key"));
    }

    @Test
    public void getStringConvertsNonString() {
        Map<String, Object> args = new HashMap<>();
        args.put("num", 42);
        assertEquals("42", McpToolContext.getString(args, "num"));
    }

    // --- requireString ---

    @Test
    public void requireStringReturnsValue() {
        Map<String, Object> args = new HashMap<>();
        args.put("name", "hello");
        assertEquals("hello", McpToolContext.requireString(args, "name"));
    }

    @Test(expected = McpException.class)
    public void requireStringThrowsWhenMissing() {
        McpToolContext.requireString(Collections.emptyMap(), "name");
    }

    @Test(expected = McpException.class)
    public void requireStringThrowsWhenEmpty() {
        Map<String, Object> args = new HashMap<>();
        args.put("name", "");
        McpToolContext.requireString(args, "name");
    }

    // --- getInteger ---

    @Test
    public void getIntegerReturnsNumber() {
        Map<String, Object> args = new HashMap<>();
        args.put("port", 8080);
        assertEquals(Integer.valueOf(8080), McpToolContext.getInteger(args, "port"));
    }

    @Test
    public void getIntegerParsesString() {
        Map<String, Object> args = new HashMap<>();
        args.put("port", "9090");
        assertEquals(Integer.valueOf(9090), McpToolContext.getInteger(args, "port"));
    }

    @Test
    public void getIntegerReturnsNullForMissing() {
        assertNull(McpToolContext.getInteger(Collections.emptyMap(), "port"));
    }

    @Test
    public void getIntegerReturnsNullForInvalidString() {
        Map<String, Object> args = new HashMap<>();
        args.put("port", "abc");
        assertNull(McpToolContext.getInteger(args, "port"));
    }

    // --- requireInteger ---

    @Test
    public void requireIntegerReturnsValueWhenPresent() {
        Map<String, Object> args = new HashMap<>();
        args.put("timeout", 5000);
        assertEquals(5000, McpToolContext.requireInteger(args, "timeout", 1000));
    }

    @Test
    public void requireIntegerReturnsDefaultWhenMissing() {
        assertEquals(1000, McpToolContext.requireInteger(Collections.emptyMap(), "timeout", 1000));
    }

    // --- getBoolean ---

    @Test
    public void getBooleanReturnsTrue() {
        Map<String, Object> args = new HashMap<>();
        args.put("flag", true);
        assertEquals(Boolean.TRUE, McpToolContext.getBoolean(args, "flag"));
    }

    @Test
    public void getBooleanParsesStringTrue() {
        Map<String, Object> args = new HashMap<>();
        args.put("flag", "true");
        assertEquals(Boolean.TRUE, McpToolContext.getBoolean(args, "flag"));
    }

    @Test
    public void getBooleanParsesStringFalse() {
        Map<String, Object> args = new HashMap<>();
        args.put("flag", "false");
        assertEquals(Boolean.FALSE, McpToolContext.getBoolean(args, "flag"));
    }

    @Test
    public void getBooleanReturnsNullForMissing() {
        assertNull(McpToolContext.getBoolean(Collections.emptyMap(), "flag"));
    }

    // --- getStringList ---

    @Test
    public void getStringListReturnsList() {
        Map<String, Object> args = new HashMap<>();
        args.put("tags", Arrays.asList("a", "b"));
        List<String> result = McpToolContext.getStringList(args, "tags");
        assertEquals(2, result.size());
        assertEquals("a", result.get(0));
    }

    @Test
    public void getStringListReturnsEmptyForMissing() {
        List<String> result = McpToolContext.getStringList(Collections.emptyMap(), "tags");
        assertTrue(result.isEmpty());
    }

    @Test
    public void getStringListReturnsEmptyForNonList() {
        Map<String, Object> args = new HashMap<>();
        args.put("tags", "not-a-list");
        List<String> result = McpToolContext.getStringList(args, "tags");
        assertTrue(result.isEmpty());
    }
}
