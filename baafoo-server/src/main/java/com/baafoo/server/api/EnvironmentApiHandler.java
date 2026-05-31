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

            if (isRulesSubPath && "POST".equals(method)) {
                ctx.requirePermission("environment", "associate");
                Map<String, Object> reqBody = ctx.mapper.readValue(body, Map.class);
                @SuppressWarnings("unchecked")
                List<String> ruleIds = (List<String>) reqBody.get("ruleIds");
                Environment env = ctx.storage.getEnvironment(id);
                if (env == null) return ApiResponse.notFound("Environment not found");
                ctx.storage.associateRulesToEnvironment(env.getName(), ruleIds != null ? ruleIds : new ArrayList<String>());
                return ApiResponse.ok("Associated " + (ruleIds != null ? ruleIds.size() : 0) + " rules", null);
            }

            if (isRulesSubPath && "DELETE".equals(method)) {
                ctx.requirePermission("environment", "associate");
                Map<String, Object> reqBody = ctx.mapper.readValue(body, Map.class);
                @SuppressWarnings("unchecked")
                List<String> ruleIds = (List<String>) reqBody.get("ruleIds");
                Environment env = ctx.storage.getEnvironment(id);
                if (env == null) return ApiResponse.notFound("Environment not found");
                ctx.storage.dissociateRulesFromEnvironment(env.getName(), ruleIds != null ? ruleIds : new ArrayList<String>());
                return ApiResponse.ok("Dissociated " + (ruleIds != null ? ruleIds.size() : 0) + " rules", null);
            }

            if ("GET".equals(method)) {
                Environment env = ctx.storage.getEnvironment(id);
                return env != null ? ApiResponse.ok(env) : ApiResponse.notFound("Environment not found");
            }
            if ("PUT".equals(method)) {
                ctx.requirePermission("environment", "update");
                Environment update = ctx.mapper.readValue(body, Environment.class);
                Environment updated = ctx.storage.updateEnvironment(id, update);
                return updated != null ? ApiResponse.ok(updated) : ApiResponse.notFound("Environment not found");
            }
            if ("DELETE".equals(method)) {
                ctx.requirePermission("environment", "delete");
                boolean deleted = ctx.storage.deleteEnvironment(id);
                return deleted ? ApiResponse.ok("Deleted", null) : ApiResponse.notFound("Environment not found");
            }
        }

        return null;
    }
}
