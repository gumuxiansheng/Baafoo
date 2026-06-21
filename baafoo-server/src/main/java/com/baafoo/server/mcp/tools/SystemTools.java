package com.baafoo.server.mcp.tools;

import com.baafoo.server.mcp.*;

import java.util.*;

/**
 * System status and chaos engineering MCP tools.
 */
public class SystemTools {

    public static class GetSystemStatusTool implements McpTool {
        @Override public String getName() { return "get_system_status"; }
        @Override public String getDescription() { return "获取系统状态：规则数、环境数、Agent数、在线Agent数、场景集数、录制趋势、插件健康状态。"; }
        @Override public String getInputSchema() {
            return McpSchemaBuilder.create().buildJson();
        }
        @Override public McpSafetyLevel getSafetyLevel() { return McpSafetyLevel.READ_ONLY; }
        @Override public Object execute(Map<String, Object> args, McpToolContext ctx) {
            List<com.baafoo.server.storage.StorageService.AgentRegistration> allAgents = ctx.getStorage().listAgents();
            long onlineThreshold = System.currentTimeMillis() - 60000;
            long onlineCount = 0;
            for (com.baafoo.server.storage.StorageService.AgentRegistration agent : allAgents) {
                if (agent.getLastHeartbeat() > onlineThreshold) onlineCount++;
            }

            long sevenDaysAgo = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000;

            Map<String, Object> status = new LinkedHashMap<>();
            status.put("version", "1.0.0-SNAPSHOT");
            status.put("rules", ctx.getStorage().listRules().size());
            status.put("environments", ctx.getStorage().listEnvironments().size());
            status.put("agents", allAgents.size());
            status.put("onlineAgents", onlineCount);
            status.put("scenes", ctx.getStorage().listScenes().size());
            status.put("requestTrend", ctx.getStorage().getRecordingCountsByDay(sevenDaysAgo));
            status.put("authEnabled", ctx.getAuthService().isAuthEnabled());
            return status;
        }
    }

    public static class ExportOpenApiTool implements McpTool {
        @Override public String getName() { return "export_openapi"; }
        @Override public String getDescription() { return "导出所有 Mock 规则为 OpenAPI 3.0 格式，便于分享和文档化。"; }
        @Override public String getInputSchema() {
            return McpSchemaBuilder.create()
                    .stringProperty("environment", "环境过滤（可选）")
                    .buildJson();
        }
        @Override public McpSafetyLevel getSafetyLevel() { return McpSafetyLevel.READ_ONLY; }
        @Override public Object execute(Map<String, Object> args, McpToolContext ctx) {
            List<com.baafoo.core.model.Rule> rules = ctx.getStorage().listRules();
            String environment = McpToolContext.getString(args, "environment");

            Map<String, Object> openapi = new LinkedHashMap<>();
            openapi.put("openapi", "3.0.3");
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("title", "Baafoo Mock API");
            info.put("version", "1.0.0");
            openapi.put("info", info);

            Map<String, Object> paths = new LinkedHashMap<>();
            int exportedCount = 0;
            for (com.baafoo.core.model.Rule rule : rules) {
                if (environment != null && !rule.getEnvironments().contains(environment)) continue;
                if (!"http".equals(rule.getProtocol())) continue;

                // Extract path and method from conditions
                String path = "/" + rule.getId();
                String httpMethod = "get";
                if (rule.getConditions() != null) {
                    for (com.baafoo.core.model.MatchCondition cond : rule.getConditions()) {
                        if ("path".equals(cond.getType())) {
                            path = cond.getValue() != null ? cond.getValue() : path;
                        }
                        if ("method".equals(cond.getType())) {
                            httpMethod = cond.getValue() != null ? cond.getValue().toLowerCase() : httpMethod;
                        }
                    }
                }

                // Extract status code from first response
                int statusCode = 200;
                if (rule.getResponses() != null && !rule.getResponses().isEmpty()) {
                    statusCode = rule.getResponses().get(0).getStatusCode();
                    if (statusCode == 0) statusCode = 200;
                }

                @SuppressWarnings("unchecked")
                Map<String, Object> pathItem = (Map<String, Object>) paths.get(path);
                if (pathItem == null) {
                    pathItem = new LinkedHashMap<>();
                    paths.put(path, pathItem);
                }

                Map<String, Object> operation = new LinkedHashMap<>();
                operation.put("summary", rule.getName());
                operation.put("operationId", rule.getId());
                Map<String, Object> responses = new LinkedHashMap<>();
                Map<String, Object> response = new LinkedHashMap<>();
                response.put("description", "Mock response");
                responses.put(String.valueOf(statusCode), response);
                operation.put("responses", responses);
                pathItem.put(httpMethod, operation);
                exportedCount++;
            }
            openapi.put("paths", paths);

            Map<String, Object> result = new HashMap<>();
            result.put("openapi", openapi);
            result.put("exportedRules", exportedCount);
            return result;
        }
    }
}
