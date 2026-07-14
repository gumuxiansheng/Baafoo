package com.baafoo.server.storage;

import java.util.List;
import java.util.Map;

/**
 * Agent 聚合根的存储接口。
 *
 * <p>包含 Agent 注册、心跳、列表查询、插件健康状态更新等操作。
 * {@link AgentRegistration} DTO 已提升为顶层类。</p>
 */
public interface AgentService {

    AgentRegistration registerAgent(String agentId, String environment, String hostname, String version,
                                    List<String> protocols, String agentIp);

    void agentHeartbeat(String agentId, String agentIp);

    /**
     * P3: Update plugin health statuses for an agent (in-memory, not persisted).
     * Called from heartbeat handler when agent reports plugin statuses.
     */
    void updateAgentPluginStatuses(String agentId, Map<String, Object> pluginStatuses);

    List<AgentRegistration> listAgents();

    List<AgentRegistration> getAgentsForEnvironment(String envName);
}
