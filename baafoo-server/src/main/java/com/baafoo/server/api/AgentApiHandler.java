package com.baafoo.server.api;

import com.baafoo.core.api.ApiResponse;
import com.baafoo.core.model.Environment;
import com.baafoo.core.model.RecordingEntry;
import com.baafoo.core.model.Rule;
import com.baafoo.server.storage.StorageService;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

class AgentApiHandler implements ResourceHandler {
    @Override
    public Object handle(String method, String path, String body, ApiContext ctx) throws Exception {
        String API_PREFIX = "/__baafoo__/api/";

        if (path.equals(API_PREFIX + "agent/register") && "POST".equals(method)) {
            Map<String, Object> reqBody = ctx.mapper.readValue(body, Map.class);
            String agentId = (String) reqBody.getOrDefault("agentId", "");
            String env = (String) reqBody.getOrDefault("environment", "default");
            String hostname = (String) reqBody.getOrDefault("hostname", "unknown");
            String version = (String) reqBody.getOrDefault("version", "1.0.0");
            @SuppressWarnings("unchecked")
            List<String> protocols = (List<String>) reqBody.getOrDefault("protocols", new ArrayList<String>());

            StorageService.AgentRegistration reg = ctx.storage.registerAgent(agentId, env, hostname, version, protocols);

            Environment environment = ctx.storage.getEnvironmentByName(env);
            String mode = environment != null ? environment.getMode().getValue() : "record-and-stub";

            Map<String, Object> result = new HashMap<String, Object>();
            result.put("agentId", reg.getAgentId());
            result.put("mode", mode);
            result.put("pollIntervalSec", 10);
            return ApiResponse.ok(result);
        }

        if (path.equals(API_PREFIX + "agent/heartbeat") && "POST".equals(method)) {
            Map<String, Object> reqBody = ctx.mapper.readValue(body, Map.class);
            String agentId = (String) reqBody.get("agentId");
            ctx.storage.agentHeartbeat(agentId);
            return ApiResponse.ok("OK", null);
        }

        if (path.equals(API_PREFIX + "agent/poll") && "GET".equals(method)) {
            String agentId = ctx.queryParam("agentId");
            List<Rule> rules = ctx.storage.listRules();

            String mode = "record-and-stub";
            for (StorageService.AgentRegistration reg : ctx.storage.listAgents()) {
                if (reg.getAgentId() != null && reg.getAgentId().equals(agentId)) {
                    Environment env = ctx.storage.getEnvironmentByName(reg.environment);
                    if (env != null) mode = env.getMode().getValue();
                    break;
                }
            }

            Map<String, Object> result = new HashMap<String, Object>();
            result.put("rules", rules);
            result.put("mode", mode);
            result.put("version", System.currentTimeMillis());
            return ApiResponse.ok(result);
        }

        if (path.equals(API_PREFIX + "agent/recordings") && "POST".equals(method)) {
            ctx.requirePermission("recording", "create");
            List<RecordingEntry> batch = ctx.mapper.readValue(body,
                    ctx.mapper.getTypeFactory().constructCollectionType(List.class, RecordingEntry.class));
            ctx.storage.addRecordings(batch);
            return ApiResponse.ok("Recorded " + batch.size(), null);
        }

        if (path.equals(API_PREFIX + "agents") && "GET".equals(method)) {
            return ApiResponse.ok(ctx.storage.listAgents());
        }

        return null;
    }
}
