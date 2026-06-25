package com.baafoo.plugin.service;

import java.util.Map;

/**
 * Custom admin endpoint handler.
 * Plugins can register custom admin API endpoints via
 * {@link ServerAdmin#registerEndpoint(String, AdminHandler)}.
 */
@FunctionalInterface
public interface AdminHandler {

    /**
     * Handle an admin API request.
     *
     * @param method HTTP method (GET, POST, etc.)
     * @param path request path
     * @param headers request headers
     * @param body request body (may be empty)
     * @return response body (string)
     */
    String handle(String method, String path, Map<String, String> headers, String body);
}
