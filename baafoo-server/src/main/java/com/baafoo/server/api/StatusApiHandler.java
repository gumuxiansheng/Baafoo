package com.baafoo.server.api;

import com.baafoo.core.api.ApiResponse;
import com.baafoo.server.api.dto.SystemStatusResponse;
import com.baafoo.server.storage.StorageService;

import java.util.List;

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

            SystemStatusResponse status = new SystemStatusResponse()
                    .version("1.0.0-SNAPSHOT")
                    .rules(ctx.storage.listRules().size())
                    .environments(ctx.storage.listEnvironments().size())
                    .agents(allAgents.size())
                    .onlineAgents(onlineCount)
                    .scenes(ctx.storage.listScenes().size())
                    .uptime(System.currentTimeMillis())
                    .authEnabled(ctx.authService.isAuthEnabled());
            return ApiResponse.ok(status);
        }
        return null;
    }
}
