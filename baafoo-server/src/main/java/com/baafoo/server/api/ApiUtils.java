package com.baafoo.server.api;

import com.baafoo.server.storage.StorageService;

import java.util.ArrayList;
import java.util.List;

class ApiUtils {
    static String extractPath(String uri) {
        int queryIdx = uri.indexOf('?');
        return queryIdx >= 0 ? uri.substring(0, queryIdx) : uri;
    }

    static String extractId(String path, String prefix, String suffix) {
        return suffix != null
                ? path.substring(prefix.length(), path.length() - suffix.length())
                : path.substring(prefix.length());
    }

    static String parseQueryParam(String uri, String key) {
        int queryIdx = uri.indexOf('?');
        if (queryIdx < 0) return null;
        String query = uri.substring(queryIdx + 1);
        for (String pair : query.split("&")) {
            int eqIdx = pair.indexOf('=');
            if (eqIdx > 0 && pair.substring(0, eqIdx).equals(key)) {
                return pair.substring(eqIdx + 1);
            }
        }
        return null;
    }

    static int parseIntParam(String uri, String key, int defaultValue) {
        String val = parseQueryParam(uri, key);
        if (val == null) return defaultValue;
        try { return Integer.parseInt(val); } catch (NumberFormatException e) { return defaultValue; }
    }

    static String getRequiredRoleForAction(String resource, String action) {
        if ("rule".equals(resource)) return "developer";
        if ("scene".equals(resource)) return "tester";
        if ("environment".equals(resource)) return "admin";
        if ("recording".equals(resource)) return "tester";
        if ("user".equals(resource)) return "admin";
        return "admin";
    }

    static boolean isValidRole(String role) {
        return "admin".equals(role) || "developer".equals(role) || "tester".equals(role) || "guest".equals(role);
    }

    static List<String> getInheritedEnvironments(StorageService storage, String ruleId) {
        List<String> inherited = new ArrayList<String>();
        for (com.baafoo.core.model.SceneSet scene : storage.listScenes()) {
            if (!scene.isActive()) continue;
            List<String> items = scene.getItemIds();
            if (items == null || !items.contains(ruleId)) continue;
            List<String> envs = scene.getEnvironments();
            if (envs != null) {
                for (String env : envs) {
                    if (!inherited.contains(env)) inherited.add(env);
                }
            }
        }
        return inherited;
    }
}
