package com.baafoo.server.api;

import com.baafoo.core.api.ApiResponse;
import com.baafoo.server.storage.AgentRegistration;
import com.baafoo.server.storage.StorageService;

import java.util.*;

/**
 * P3: Plugin management REST API.
 *
 * <p>Endpoints:</p>
 * <ul>
 *   <li>GET /__baafoo__/api/plugins — List all plugins across all online agents</li>
 *   <li>GET /__baafoo__/api/plugins?agentId=xxx — List plugins for a specific agent</li>
 * </ul>
 *
 * <p>Plugin data is sourced from agent heartbeat reports. An agent must have
 * sent a heartbeat within the last 60 seconds for its plugin data to be included.</p>
 */
class PluginApiHandler implements ResourceHandler {

    private static final long ONLINE_THRESHOLD_MS = 60000;

    @Override
    public Object handle(String method, String path, String body, ApiContext ctx) throws Exception {
        if (!"/__baafoo__/api/plugins".equals(path) || !"GET".equals(method)) {
            return null;
        }

        String agentIdFilter = ctx.queryParam("agentId");
        List<AgentRegistration> agents = ctx.storage.listAgents();
        long now = System.currentTimeMillis();

        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();

        for (AgentRegistration agent : agents) {
            // Only include online agents
            if (agent.getLastHeartbeat() <= now - ONLINE_THRESHOLD_MS) continue;
            // Filter by agentId if specified
            if (agentIdFilter != null && !agentIdFilter.equals(agent.agentId)) continue;

            Map<String, Object> agentEntry = new LinkedHashMap<String, Object>();
            agentEntry.put("agentId", agent.agentId);
            agentEntry.put("agentIp", agent.agentIp);
            agentEntry.put("environment", agent.environment);
            agentEntry.put("lastHeartbeat", agent.lastHeartbeat);

            Map<String, Object> pluginStatuses = agent.pluginStatuses;
            if (pluginStatuses != null && !pluginStatuses.isEmpty()) {
                agentEntry.put("plugins", pluginStatuses);
                agentEntry.put("pluginCount", pluginStatuses.size());
            } else {
                agentEntry.put("plugins", Collections.emptyMap());
                agentEntry.put("pluginCount", 0);
            }

            result.add(agentEntry);
        }

        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("agents", result);
        response.put("totalAgents", result.size());

        // Aggregate plugin health summary across all agents
        Map<String, Integer> healthSummary = new LinkedHashMap<String, Integer>();
        healthSummary.put("total", 0);
        healthSummary.put("healthy", 0);
        healthSummary.put("degraded", 0);
        healthSummary.put("unhealthy", 0);
        healthSummary.put("disabled", 0);
        healthSummary.put("unknown", 0);

        for (Map<String, Object> agentEntry : result) {
            @SuppressWarnings("unchecked")
            Map<String, Object> plugins = (Map<String, Object>) agentEntry.get("plugins");
            if (plugins == null) continue;
            for (Map.Entry<String, Object> entry : plugins.entrySet()) {
                healthSummary.put("total", healthSummary.get("total") + 1);
                if (entry.getValue() instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> status = (Map<String, Object>) entry.getValue();
                    String health = (String) status.get("health");
                    if (health != null) {
                        String key = health.toLowerCase();
                        if (healthSummary.containsKey(key)) {
                            healthSummary.put(key, healthSummary.get(key) + 1);
                        }
                    }
                }
            }
        }
        response.put("healthSummary", healthSummary);

        return ApiResponse.ok(response);
    }
}
