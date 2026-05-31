package com.baafoo.server.api;

import com.baafoo.core.api.ApiResponse;
import com.baafoo.server.storage.StorageService;

import java.util.HashMap;
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

            Map<String, Object> status = new HashMap<String, Object>();
            status.put("version", "1.0.0-SNAPSHOT");
            status.put("rules", ctx.storage.listRules().size());
            status.put("environments", ctx.storage.listEnvironments().size());
            status.put("agents", allAgents.size());
            status.put("onlineAgents", onlineCount);
            status.put("scenes", ctx.storage.listScenes().size());
            status.put("uptime", System.currentTimeMillis());
            status.put("authEnabled", ctx.authService.isAuthEnabled());
            return ApiResponse.ok(status);
        }
        return null;
    }
}
