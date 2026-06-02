package com.baafoo.server.api;

import com.baafoo.core.api.ApiResponse;
import com.baafoo.core.api.PaginatedResult;
import com.baafoo.core.model.*;

import java.util.ArrayList;
import java.util.List;

class RuleApiHandler implements ResourceHandler {
    @Override
    public Object handle(String method, String path, String body, ApiContext ctx) throws Exception {
        String API_PREFIX = "/__baafoo__/api/";

        if (path.equals(API_PREFIX + "rules")) {
            if ("GET".equals(method)) {
                // Pagination support: page & size params
                String pageStr = ctx.queryParam("page");
                String sizeStr = ctx.queryParam("size");

                if (pageStr != null || sizeStr != null) {
                    // Paginated mode with server-side filtering
                    int page = ctx.queryParamInt("page", 1);
                    int size = ctx.queryParamInt("size", 20);
                    if (page < 1) page = 1;
                    if (size < 1) size = 20;
                    if (size > 100) size = 100;
                    String protocol = ctx.queryParam("protocol");
                    String keyword = ctx.queryParam("keyword");
                    PaginatedResult<Rule> result = ctx.storage.listRulesPaged(protocol, keyword, page, size);
                    return ApiResponse.ok(result);
                } else {
                    // Legacy mode: return all rules (backward compatible)
                    return ApiResponse.ok(ctx.storage.listRules());
                }
            }
            if ("POST".equals(method)) {
                ctx.requirePermission("rule", "create");
                Rule rule = ctx.mapper.readValue(body, Rule.class);
                return ApiResponse.created(ctx.storage.createRule(rule));
            }
        }

        if (path.startsWith(API_PREFIX + "rules/") && path.contains("/undo")) {
            String id = ApiUtils.extractId(path, API_PREFIX + "rules/", "/undo");
            ctx.requirePermission("rule", "update");
            boolean success = ctx.storage.undoRule(id);
            return success ? ApiResponse.ok("Undo successful", null) : ApiResponse.notFound("Rule not found or no undo history");
        }

        if (path.startsWith(API_PREFIX + "rules/") && path.contains("/inherited-environments")) {
            String id = ApiUtils.extractId(path, API_PREFIX + "rules/", "/inherited-environments");
            return ApiResponse.ok(ApiUtils.getInheritedEnvironments(ctx.storage, id));
        }

        if (path.startsWith(API_PREFIX + "rules/")) {
            String id = ApiUtils.extractId(path, API_PREFIX + "rules/", null);
            if ("GET".equals(method)) {
                Rule rule = ctx.storage.getRule(id);
                return rule != null ? ApiResponse.ok(rule) : ApiResponse.notFound("Rule not found");
            }
            if ("PUT".equals(method)) {
                ctx.requirePermission("rule", "update");
                Rule update = ctx.mapper.readValue(body, Rule.class);
                Rule existing = ctx.storage.getRule(id);
                if (existing == null) return ApiResponse.notFound("Rule not found");
                List<String> inheritedEnvs = ApiUtils.getInheritedEnvironments(ctx.storage, id);
                List<String> requestedEnvs = update.getEnvironments() != null ? update.getEnvironments() : new ArrayList<String>();
                List<String> mergedEnvs = new ArrayList<String>(requestedEnvs);
                for (String inherited : inheritedEnvs) {
                    if (!mergedEnvs.contains(inherited)) mergedEnvs.add(inherited);
                }
                update.setEnvironments(mergedEnvs);
                Rule updated = ctx.storage.updateRule(id, update);
                return updated != null ? ApiResponse.ok(updated) : ApiResponse.notFound("Rule not found");
            }
            if ("DELETE".equals(method)) {
                ctx.requirePermission("rule", "delete");
                boolean deleted = ctx.storage.deleteRule(id);
                return deleted ? ApiResponse.ok("Deleted", null) : ApiResponse.notFound("Rule not found");
            }
        }

        if (path.equals(API_PREFIX + "rulesets")) {
            if ("GET".equals(method)) return ApiResponse.ok(ctx.storage.listRuleSets());
            if ("POST".equals(method)) {
                ctx.requirePermission("rule", "create");
                RuleSet set = ctx.mapper.readValue(body, RuleSet.class);
                return ApiResponse.created(ctx.storage.createRuleSet(set));
            }
        }

        return null;
    }
}
