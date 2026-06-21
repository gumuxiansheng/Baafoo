package com.baafoo.server.mcp;

import com.baafoo.server.storage.StorageService;
import com.baafoo.server.auth.AuthService;

import java.util.Map;

/**
 * Context for MCP tool execution.
 * Provides access to storage, auth, and user info.
 */
public class McpToolContext {

    private final StorageService storage;
    private final AuthService authService;
    private final String role;
    private final String username;

    public McpToolContext(StorageService storage, AuthService authService, String role, String username) {
        this.storage = storage;
        this.authService = authService;
        this.role = role;
        this.username = username;
    }

    public StorageService getStorage() { return storage; }
    public AuthService getAuthService() { return authService; }
    public String getRole() { return role; }
    public String getUsername() { return username; }

    /**
     * Check if the current user has permission for the given resource and action.
     */
    public void requirePermission(String resource, String action) {
        if (!AuthService.hasPermission(role, resource, action)) {
            throw new McpException(403, "Permission denied: " + role + " cannot " + action + " " + resource);
        }
    }

    /**
     * Check if the current user is admin.
     */
    public void requireAdmin() {
        if (!"admin".equals(role)) {
            throw new McpException(403, "Admin permission required");
        }
    }

    // --- Argument helpers ---

    public static String getString(Map<String, Object> args, String key) {
        Object val = args.get(key);
        return val != null ? val.toString() : null;
    }

    public static String requireString(Map<String, Object> args, String key) {
        String val = getString(args, key);
        if (val == null || val.isEmpty()) {
            throw new McpException(400, "Missing required parameter: " + key);
        }
        return val;
    }

    public static Integer getInteger(Map<String, Object> args, String key) {
        Object val = args.get(key);
        if (val == null) return null;
        if (val instanceof Number) return ((Number) val).intValue();
        try { return Integer.parseInt(val.toString()); } catch (NumberFormatException e) { return null; }
    }

    public static int requireInteger(Map<String, Object> args, String key, int defaultValue) {
        Integer val = getInteger(args, key);
        return val != null ? val : defaultValue;
    }

    public static Boolean getBoolean(Map<String, Object> args, String key) {
        Object val = args.get(key);
        if (val == null) return null;
        if (val instanceof Boolean) return (Boolean) val;
        return Boolean.parseBoolean(val.toString());
    }

    @SuppressWarnings("unchecked")
    public static java.util.List<String> getStringList(Map<String, Object> args, String key) {
        Object val = args.get(key);
        if (val instanceof java.util.List) {
            return (java.util.List<String>) val;
        }
        return java.util.Collections.emptyList();
    }
}
