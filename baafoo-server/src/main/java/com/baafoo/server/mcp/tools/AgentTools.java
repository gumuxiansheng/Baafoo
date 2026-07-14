package com.baafoo.server.mcp.tools;

import com.baafoo.server.mcp.*;
import com.baafoo.server.storage.AgentRegistration;
import com.baafoo.server.storage.StorageService;

import java.util.*;

/**
 * Agent management MCP tools.
 */
public class AgentTools {

    public static class ListAgentsTool implements McpTool {
        @Override public String getName() { return "list_agents"; }
        @Override public String getDescription() { return "列出所有已注册的 Agent 及其状态（在线/离线、环境、版本、插件状态）。"; }
        @Override public String getInputSchema() {
            return McpSchemaBuilder.create().buildJson();
        }
        @Override public McpSafetyLevel getSafetyLevel() { return McpSafetyLevel.READ_ONLY; }
        @Override public Object execute(Map<String, Object> args, McpToolContext ctx) {
            List<AgentRegistration> agents = ctx.getStorage().listAgents();
            long now = System.currentTimeMillis();
            long onlineThreshold = now - 60000;

            List<Map<String, Object>> result = new ArrayList<>();
            for (AgentRegistration agent : agents) {
                Map<String, Object> info = new LinkedHashMap<>();
                info.put("agentId", agent.getAgentId());
                info.put("environment", agent.environment);
                info.put("hostname", agent.hostname);
                info.put("version", agent.version);
                info.put("agentIp", agent.agentIp);
                info.put("online", agent.getLastHeartbeat() > onlineThreshold);
                info.put("lastHeartbeat", agent.getLastHeartbeat());
                info.put("protocols", agent.protocols);
                info.put("pluginStatuses", agent.pluginStatuses);
                result.add(info);
            }
            return result;
        }
    }

    public static class GetAgentTool implements McpTool {
        @Override public String getName() { return "get_agent"; }
        @Override public String getDescription() { return "获取指定 Agent 的详细信息。"; }
        @Override public String getInputSchema() {
            return McpSchemaBuilder.create()
                    .requiredString("agentId", "Agent ID")
                    .buildJson();
        }
        @Override public McpSafetyLevel getSafetyLevel() { return McpSafetyLevel.READ_ONLY; }
        @Override public Object execute(Map<String, Object> args, McpToolContext ctx) {
            String agentId = McpToolContext.requireString(args, "agentId");
            for (AgentRegistration agent : ctx.getStorage().listAgents()) {
                if (agentId.equals(agent.getAgentId())) {
                    Map<String, Object> info = new LinkedHashMap<>();
                    info.put("agentId", agent.getAgentId());
                    info.put("environment", agent.environment);
                    info.put("hostname", agent.hostname);
                    info.put("version", agent.version);
                    info.put("agentIp", agent.agentIp);
                    info.put("lastHeartbeat", agent.getLastHeartbeat());
                    info.put("protocols", agent.protocols);
                    info.put("pluginStatuses", agent.pluginStatuses);
                    long onlineThreshold = System.currentTimeMillis() - 60000;
                    info.put("online", agent.getLastHeartbeat() > onlineThreshold);
                    return info;
                }
            }
            throw new McpException(404, "Agent not found: " + agentId);
        }
    }
}
