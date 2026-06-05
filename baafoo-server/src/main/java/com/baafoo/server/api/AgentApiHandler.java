package com.baafoo.server.api;

import com.baafoo.core.api.ApiResponse;
import com.baafoo.core.model.Environment;
import com.baafoo.core.model.RecordingEntry;
import com.baafoo.core.model.Rule;
import com.baafoo.server.api.dto.AgentPollResponseDto;
import com.baafoo.server.api.dto.AgentRegisterResponseDto;
import com.baafoo.server.handler.AgentResolver;
import com.baafoo.server.storage.StorageService;

import java.util.ArrayList;
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
            String agentIp = (String) reqBody.getOrDefault("agentIp", ctx.remoteAddr);
            @SuppressWarnings("unchecked")
            List<String> protocols = (List<String>) reqBody.getOrDefault("protocols", new ArrayList<String>());

            StorageService.AgentRegistration reg = ctx.storage.registerAgent(agentId, env, hostname, version, protocols, agentIp);

            Environment environment = ctx.storage.getEnvironmentByName(env);
            String mode = environment != null ? environment.getMode().getValue() : "record-and-stub";

            AgentRegisterResponseDto result = new AgentRegisterResponseDto()
                    .agentId(reg.getAgentId())
                    .mode(mode)
                    .pollIntervalSec(10);
            return ApiResponse.ok(result);
        }

        if (path.equals(API_PREFIX + "agent/heartbeat") && "POST".equals(method)) {
            Map<String, Object> reqBody = ctx.mapper.readValue(body, Map.class);
            String agentId = (String) reqBody.get("agentId");
            String agentIp = (String) reqBody.get("agentIp");
            ctx.storage.agentHeartbeat(agentId, agentIp);
            return ApiResponse.ok("OK", null);
        }

        if (path.equals(API_PREFIX + "agent/poll") && "GET".equals(method)) {
            String agentId = ctx.queryParam("agentId");

            // Resolve agent's environment and mode
            String agentEnvironment = null;
            String mode = "record-and-stub";
            for (StorageService.AgentRegistration reg : ctx.storage.listAgents()) {
                if (reg.getAgentId() != null && reg.getAgentId().equals(agentId)) {
                    agentEnvironment = reg.environment;
                    Environment env = ctx.storage.getEnvironmentByName(reg.environment);
                    if (env != null) mode = env.getMode().getValue();
                    break;
                }
            }

            // Only return rules that belong to this agent's environment
            List<Rule> rules = new AgentResolver(ctx.storage)
                    .filterRulesByEnvironment(ctx.storage.listRules(), agentEnvironment);

            AgentPollResponseDto result = new AgentPollResponseDto()
                    .rules(rules)
                    .mode(mode)
                    .version(System.currentTimeMillis());
            return ApiResponse.ok(result);
        }

        if (path.equals(API_PREFIX + "agent/recordings") && "POST".equals(method)) {
            ctx.requirePermission("recording", "create");
            List<RecordingEntry> batch = ctx.mapper.readValue(body,
                    ctx.mapper.getTypeFactory().constructCollectionType(List.class, RecordingEntry.class));
            String agentId = ctx.queryParam("agentId");
            String agentIp = resolveAgentIp(ctx);
            for (RecordingEntry rec : batch) {
                if (rec.getAgentId() == null || rec.getAgentId().isEmpty()) {
                    rec.setAgentId(agentId);
                }
                if (rec.getAgentIp() == null || rec.getAgentIp().isEmpty()) {
                    rec.setAgentIp(agentIp);
                }
            }
            ctx.storage.addRecordings(batch);
            return ApiResponse.ok("Recorded " + batch.size(), null);
        }

        if (path.equals(API_PREFIX + "agents") && "GET".equals(method)) {
            return ApiResponse.ok(ctx.storage.listAgents());
        }

        return null;
    }

    private String resolveAgentIp(ApiContext ctx) {
        String agentId = ctx.queryParam("agentId");
        if (agentId != null && !agentId.isEmpty()) {
            for (StorageService.AgentRegistration agent : ctx.storage.listAgents()) {
                if (agentId.equals(agent.getAgentId()) && agent.agentIp != null && !agent.agentIp.isEmpty()) {
                    return agent.agentIp;
                }
            }
        }
        return ctx.remoteAddr;
    }
}
