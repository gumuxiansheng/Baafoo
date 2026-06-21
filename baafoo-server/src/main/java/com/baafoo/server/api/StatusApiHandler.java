package com.baafoo.server.api;

import com.baafoo.core.api.ApiResponse;
import com.baafoo.server.api.dto.SystemStatusResponse;
import com.baafoo.server.storage.StorageService;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

class StatusApiHandler implements ResourceHandler {
    @Override
    public Object handle(String method, String path, String body, ApiContext ctx) throws Exception {
        if (path.equals("/__baafoo__/api/status") && "GET".equals(method)) {
            List<StorageService.AgentRegistration> allAgents = ctx.storage.listAgents();
            long onlineThreshold = System.currentTimeMillis() - 60000;
            long onlineCount = 0;
            for (StorageService.AgentRegistration agent : allAgents) {
                if (agent.getLastHeartbeat() > onlineThreshold) onlineCount++;
            }

            // Get recording trend data for the last 7 days
            long sevenDaysAgo = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000;
            List<Map<String, Object>> dailyCounts = ctx.storage.getRecordingCountsByDay(sevenDaysAgo);

            SystemStatusResponse status = new SystemStatusResponse()
                    .version("1.0.0-SNAPSHOT")
                    .rules(ctx.storage.listRules().size())
                    .environments(ctx.storage.listEnvironments().size())
                    .agents(allAgents.size())
                    .onlineAgents(onlineCount)
                    .scenes(ctx.storage.listScenes().size())
                    .uptime(System.currentTimeMillis())
                    .authEnabled(ctx.authService.isAuthEnabled())
                    .requestTrend(dailyCounts)
                    .plugins(buildPluginSummary(allAgents, onlineThreshold));
            return ApiResponse.ok(status);
        }
        return null;
    }

    /**
     * Build a plugin health summary from online agents' plugin statuses.
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> buildPluginSummary(List<StorageService.AgentRegistration> agents, long onlineThreshold) {
        Map<String, Object> summary = new LinkedHashMap<String, Object>();
        int totalPlugins = 0;
        int agentsWithPlugins = 0;
        Map<String, Integer> healthCounts = new LinkedHashMap<String, Integer>();
        healthCounts.put("HEALTHY", 0);
        healthCounts.put("DEGRADED", 0);
        healthCounts.put("UNHEALTHY", 0);
        healthCounts.put("DISABLED", 0);
        healthCounts.put("UNKNOWN", 0);

        for (StorageService.AgentRegistration agent : agents) {
            if (agent.getLastHeartbeat() <= onlineThreshold) continue;
            Map<String, Object> statuses = agent.pluginStatuses;
            if (statuses == null || statuses.isEmpty()) continue;
            agentsWithPlugins++;
            for (Object value : statuses.values()) {
                totalPlugins++;
                if (value instanceof Map) {
                    String health = (String) ((Map<String, Object>) value).get("health");
                    if (health != null && healthCounts.containsKey(health)) {
                        healthCounts.put(health, healthCounts.get(health) + 1);
                    }
                }
            }
        }

        summary.put("totalPlugins", totalPlugins);
        summary.put("agentsWithPlugins", agentsWithPlugins);
        summary.put("healthCounts", healthCounts);
        return summary;
    }
}
