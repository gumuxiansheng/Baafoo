package com.baafoo.server.api;

import com.baafoo.core.api.ApiResponse;
import com.baafoo.core.api.PaginatedResult;
import com.baafoo.core.event.EventBus;
import com.baafoo.core.model.*;
import com.baafoo.core.util.OpenApiImporter;
import com.baafoo.core.util.StatefulCounterStore;
import com.baafoo.plugin.PluginEvent;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
                    String environment = ctx.queryParam("environment");
                    String host = ctx.queryParam("host");
                    String sortBy = ctx.queryParam("sortBy");
                    String sortOrder = ctx.queryParam("sortOrder");
                    PaginatedResult<Rule> result = ctx.storage.listRulesPaged(protocol, keyword, environment, host, sortBy, sortOrder, page, size);
                    return ApiResponse.ok(result);
                } else {
                    // Legacy mode: return all rules (backward compatible)
                    return ApiResponse.ok(ctx.storage.listRules());
                }
            }
            if ("POST".equals(method)) {
                ctx.requirePermission("rule", "create");
                Rule rule = ctx.mapper.readValue(body, Rule.class);
                String validationError = validateRuleConditions(rule);
                if (validationError != null) {
                    return ApiResponse.badRequest(validationError);
                }
                Rule created = ctx.storage.createRule(rule);
                fireRuleChanged(ctx, created.getId(), "created", null);
                return ApiResponse.created(created);
            }
        }

        // Stateful Mock counter reset (PRD §3 R-S2 AC-04)
        if (path.equals(API_PREFIX + "rules/reset-all-state") && "POST".equals(method)) {
            ctx.requirePermission("rule", "update");
            StatefulCounterStore.global().resetAll();
            return ApiResponse.ok("All rule counters reset", null);
        }

        // OpenAPI import (PRD §1 R-S10)
        if (path.equals(API_PREFIX + "rules/import-openapi") && "POST".equals(method)) {
            ctx.requirePermission("rule", "create");
            return handleOpenApiImport(body, ctx);
        }

        if (path.startsWith(API_PREFIX + "rules/") && path.endsWith("/reset-state") && "POST".equals(method)) {
            String id = ApiUtils.extractId(path, API_PREFIX + "rules/", "/reset-state");
            ctx.requirePermission("rule", "update");
            if (ctx.storage.getRule(id) == null) {
                return ApiResponse.notFound("Rule not found");
            }
            StatefulCounterStore.global().reset(id);
            return ApiResponse.ok("Rule counter reset", null);
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
                String validationError = validateRuleConditions(update);
                if (validationError != null) {
                    return ApiResponse.badRequest(validationError);
                }
                Rule existing = ctx.storage.getRule(id);
                if (existing == null) return ApiResponse.notFound("Rule not found");
                // Inherited-environment merging is now handled in
                // JdbcStorageService.updateRule so all update paths stay consistent.
                Rule updated = ctx.storage.updateRule(id, update);
                if (updated != null) {
                    fireRuleChanged(ctx, id, "updated", null);
                }
                return updated != null ? ApiResponse.ok(updated) : ApiResponse.notFound("Rule not found");
            }
            if ("DELETE".equals(method)) {
                ctx.requirePermission("rule", "delete");
                boolean deleted = ctx.storage.deleteRule(id);
                if (deleted) {
                    // Clean up the per-rule counter to prevent unbounded map growth (S4 fix).
                    StatefulCounterStore.global().reset(id);
                    fireRuleChanged(ctx, id, "deleted", null);
                }
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

        if (path.startsWith(API_PREFIX + "rulesets/") && "DELETE".equals(method)) {
            String id = ApiUtils.extractId(path, API_PREFIX + "rulesets/", null);
            ctx.requirePermission("rule", "delete");
            boolean deleted = ctx.storage.deleteRuleSet(id);
            return deleted ? ApiResponse.ok("Deleted", null) : ApiResponse.notFound("RuleSet not found");
        }

        return null;
    }

    /**
     * Handle OpenAPI spec import (PRD §1 R-S10).
     *
     * <p>Parses the OpenAPI 3.0 JSON spec, generates rule skeletons, and
     * optionally saves them. Returns a preview with statistics.</p>
     *
     * <p>Query parameters:
     * <ul>
     *   <li>{@code environment} — comma-separated environment IDs (default: empty)</li>
     *   <li>{@code save} — {@code true} to persist rules (default: {@code false}, preview only)</li>
     *   <li>{@code prefix} — rule ID prefix (default: {@code openapi-})</li>
     * </ul>
     * </p>
     */
    private Object handleOpenApiImport(String body, ApiContext ctx) throws Exception {
        // DoS protection: reject oversized payloads at the API layer (10 MB)
        if (body != null && body.length() > OpenApiImporter.MAX_INPUT_SIZE_BYTES) {
            return ApiResponse.badRequest("OpenAPI spec exceeds maximum allowed size of "
                    + (OpenApiImporter.MAX_INPUT_SIZE_BYTES / 1024 / 1024) + " MB");
        }

        // Parse query parameters
        String envParam = ctx.queryParam("environment");
        List<String> environments = new ArrayList<String>();
        if (envParam != null && !envParam.trim().isEmpty()) {
            for (String env : envParam.split(",")) {
                String trimmed = env.trim();
                if (!trimmed.isEmpty()) {
                    environments.add(trimmed);
                }
            }
        }

        String saveParam = ctx.queryParam("save");
        boolean save = "true".equalsIgnoreCase(saveParam);

        String prefix = ctx.queryParam("prefix");
        if (prefix == null || prefix.trim().isEmpty()) {
            prefix = "openapi-";
        }

        // Import the spec
        OpenApiImporter importer = new OpenApiImporter();
        OpenApiImporter.OpenApiImportResult importResult;
        try {
            importResult = importer.importSpec(body, prefix, environments);
        } catch (OpenApiImporter.OpenApiImportException e) {
            return ApiResponse.badRequest("OpenAPI import failed: " + e.getMessage());
        }

        // Build response with stats
        Map<String, Object> resultData = new HashMap<String, Object>();
        resultData.put("rules", importResult.getRules());
        resultData.put("generatedCount", importResult.getGeneratedCount());
        resultData.put("skippedCount", importResult.getSkippedCount());
        resultData.put("warnings", importResult.getWarnings());

        if (save) {
            // Persist rules, detecting conflicts by rule ID (AC-05: path+method is the
            // stable key, encoded in the rule ID)
            int savedCount = 0;
            int conflictCount = 0;
            List<String> savedIds = new ArrayList<String>();
            for (Rule rule : importResult.getRules()) {
                Rule existing = ctx.storage.getRule(rule.getId());
                if (existing != null) {
                    conflictCount++;
                    ctx.storage.updateRule(rule.getId(), rule);
                } else {
                    ctx.storage.createRule(rule);
                }
                savedCount++;
                savedIds.add(rule.getId());
            }
            resultData.put("savedCount", savedCount);
            resultData.put("conflictCount", conflictCount);
            resultData.put("savedIds", savedIds);
            resultData.put("summary", String.format(
                    "Imported %d rules (%d conflicts overwritten), skipped %d paths",
                    savedCount, conflictCount, importResult.getSkippedCount()));
        } else {
            resultData.put("conflictCount", 0);
            resultData.put("summary", importResult.getSummary()
                    + " (preview only — pass ?save=true to persist)");
        }

        return ApiResponse.ok(resultData);
    }

    /**
     * Condition types valid per protocol.
     * "topic"/"destination" are treated as aliases of "path" by MatchEngine
     * and are intentionally allowed for HTTP for backward compatibility.
     * "requestCount", "header", "body", "bodyContains" are protocol-agnostic.
     */
    private static final Map<String, Set<String>> VALID_CONDITION_TYPES;

    static {
        Set<String> http = new HashSet<>(Arrays.asList(
                "method", "path", "topic", "destination", "header", "query",
                "body", "bodyContains", "bodyJsonPath",
                "graphqlOperationName", "graphqlOperationType", "requestCount"));
        Set<String> tcp = new HashSet<>(Arrays.asList(
                "body", "bodyContains", "requestCount"));
        Set<String> grpc = new HashSet<>(Arrays.asList(
                "grpcService", "grpcMethod", "path", "header",
                "body", "bodyContains", "requestCount"));
        Set<String> kafka = new HashSet<>(Arrays.asList(
                "topic", "destination", "key", "header",
                "body", "bodyContains", "requestCount"));
        Set<String> pulsar = new HashSet<>(Arrays.asList(
                "topic", "destination", "header",
                "body", "bodyContains", "requestCount"));
        Set<String> jms = new HashSet<>(Arrays.asList(
                "destination", "header",
                "body", "bodyContains", "requestCount"));

        Map<String, Set<String>> map = new HashMap<>();
        map.put("http", http);
        map.put("tcp", tcp);
        map.put("grpc", grpc);
        map.put("kafka", kafka);
        map.put("pulsar", pulsar);
        map.put("jms", jms);
        VALID_CONDITION_TYPES = Collections.unmodifiableMap(map);
    }

    /**
     * Validates that all condition types in the rule are valid for the rule's protocol.
     * Returns an error message if any condition type is mismatched, or null if valid.
     */
    private String validateRuleConditions(Rule rule) {
        if (rule == null || rule.getProtocol() == null || rule.getConditions() == null) {
            return null;
        }
        Set<String> valid = VALID_CONDITION_TYPES.get(rule.getProtocol().toLowerCase());
        if (valid == null) {
            return null; // unknown protocol — don't block, let other logic handle it
        }
        for (MatchCondition cond : rule.getConditions()) {
            if (cond.getType() == null || cond.getType().isEmpty()) {
                continue; // empty type handled elsewhere
            }
            if (!valid.contains(cond.getType())) {
                return "Condition type '" + cond.getType()
                        + "' is not valid for protocol '" + rule.getProtocol()
                        + "'. Valid types: " + valid;
            }
        }
        return null;
    }

    /**
     * P2: Fire a RULE_CHANGED event if an EventBus is available in the context.
     */
    private void fireRuleChanged(ApiContext ctx, String ruleId, String action, String environmentId) {
        EventBus bus = ctx.getEventBus();
        if (bus != null) {
            PluginEvent event = PluginEvent.ruleChanged(ruleId, action, environmentId);
            bus.fire(event);
        }
    }
}
