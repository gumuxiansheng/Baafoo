package com.baafoo.server.api;

import com.baafoo.core.api.ApiResponse;
import com.baafoo.core.model.Environment;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

class EnvironmentApiHandler implements ResourceHandler {
    @Override
    public Object handle(String method, String path, String body, ApiContext ctx) throws Exception {
        String API_PREFIX = "/__baafoo__/api/";

        if (path.equals(API_PREFIX + "environments")) {
            if ("GET".equals(method)) return ApiResponse.ok(ctx.storage.listEnvironments());
            if ("POST".equals(method)) {
                ctx.requirePermission("environment", "create");
                Environment env = ctx.mapper.readValue(body, Environment.class);
                return ApiResponse.created(ctx.storage.createEnvironment(env));
            }
        }

        if (path.startsWith(API_PREFIX + "environments/")) {
            String id;
            boolean isRulesSubPath = path.endsWith("/rules");
            if (isRulesSubPath) {
                id = ApiUtils.extractId(path, API_PREFIX + "environments/", "/rules");
            } else {
                id = ApiUtils.extractId(path, API_PREFIX + "environments/", null);
            }

            // Resolve the actual environment: try by internal ID first, then by name.
            // This allows API consumers to use /api/environments/{name} as well as
            // /api/environments/{id} — the test scripts and web console use names.
            String resolvedId = resolveEnvironmentId(ctx, id);

            if (isRulesSubPath && "POST".equals(method)) {
                ctx.requirePermission("environment", "associate");
                Map<String, Object> reqBody = ctx.mapper.readValue(body, Map.class);
                @SuppressWarnings("unchecked")
                List<String> ruleIds = (List<String>) reqBody.get("ruleIds");
                Environment env = ctx.storage.getEnvironment(resolvedId);
                if (env == null) return ApiResponse.notFound("Environment not found");
                ctx.storage.associateRulesToEnvironment(env.getName(), ruleIds != null ? ruleIds : new ArrayList<String>());
                return ApiResponse.ok("Associated " + (ruleIds != null ? ruleIds.size() : 0) + " rules", null);
            }

            if (isRulesSubPath && "DELETE".equals(method)) {
                ctx.requirePermission("environment", "associate");
                Map<String, Object> reqBody = ctx.mapper.readValue(body, Map.class);
                @SuppressWarnings("unchecked")
                List<String> ruleIds = (List<String>) reqBody.get("ruleIds");
                Environment env = ctx.storage.getEnvironment(resolvedId);
                if (env == null) return ApiResponse.notFound("Environment not found");
                ctx.storage.dissociateRulesFromEnvironment(env.getName(), ruleIds != null ? ruleIds : new ArrayList<String>());
                return ApiResponse.ok("Dissociated " + (ruleIds != null ? ruleIds.size() : 0) + " rules", null);
            }

            if ("GET".equals(method)) {
                Environment env = ctx.storage.getEnvironment(resolvedId);
                return env != null ? ApiResponse.ok(env) : ApiResponse.notFound("Environment not found");
            }
            if ("PUT".equals(method)) {
                ctx.requirePermission("environment", "update");
                Environment update = ctx.mapper.readValue(body, Environment.class);
                Environment updated = ctx.storage.updateEnvironment(resolvedId, update);
                return updated != null ? ApiResponse.ok(updated) : ApiResponse.notFound("Environment not found");
            }
            if ("DELETE".equals(method)) {
                ctx.requirePermission("environment", "delete");
                boolean deleted = ctx.storage.deleteEnvironment(resolvedId);
                return deleted ? ApiResponse.ok("Deleted", null) : ApiResponse.notFound("Environment not found");
            }
        }

        return null;
    }

    /**
     * Resolve the path segment to an actual environment ID.
     * Tries internal ID first, then falls back to name-based lookup.
     * Returns the original {@code idOrName} if neither lookup matches (caller
     * will get a 404 from the subsequent getEnvironment call).
     */
    private String resolveEnvironmentId(ApiContext ctx, String idOrName) {
        if (idOrName == null || idOrName.isEmpty()) return idOrName;
        // Fast path: exact ID match
        if (ctx.storage.getEnvironment(idOrName) != null) return idOrName;
        // Fallback: try by name
        Environment byName = ctx.storage.getEnvironmentByName(idOrName);
        return byName != null ? byName.getId() : idOrName;
    }
}
