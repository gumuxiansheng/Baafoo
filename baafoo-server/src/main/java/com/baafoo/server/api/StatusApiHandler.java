package com.baafoo.server.api;

import com.baafoo.core.api.ApiResponse;
import com.baafoo.server.api.dto.SystemStatusResponse;
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
                    .requestTrend(dailyCounts);
            return ApiResponse.ok(status);
        }
        return null;
    }
}
