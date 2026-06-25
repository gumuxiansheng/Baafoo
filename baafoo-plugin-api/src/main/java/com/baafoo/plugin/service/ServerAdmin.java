package com.baafoo.plugin.service;

/**
 * Server-side administration API.
 * Allows plugins to register custom admin endpoints or trigger actions.
 */
public interface ServerAdmin {

    /**
     * Register a custom admin endpoint at the given path.
     *
     * @param path URL path (e.g., "/api/plugins/metrics")
     * @param handler request handler
     */
    void registerEndpoint(String path, AdminHandler handler);

    /**
     * Trigger a rule reload.
     */
    void reloadRules();

    /**
     * Get server config value by key.
     *
     * @param key config key
     * @return config value, or null if not found
     */
    String getConfig(String key);
}
