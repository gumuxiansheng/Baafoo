package com.baafoo.server.storage;

import java.util.List;
import java.util.Map;

/**
 * Agent registration info DTO.
 *
 * <p>从 {@link StorageService} 的内部类提取为顶层类，使 {@link AgentService}
 * 接口的调用方不再需要依赖整个 {@link StorageService} 组合接口即可引用此类型。</p>
 */
public class AgentRegistration {
    public String agentId;
    public String environment;
    public String hostname;
    public String version;
    public List<String> protocols;
    public String agentIp;
    public long registeredAt;
    public long lastHeartbeat;

    /** P3: Plugin health statuses (in-memory only, refreshed via heartbeat). */
    public Map<String, Object> pluginStatuses;

    public String getAgentId() { return agentId; }
    public long getLastHeartbeat() { return lastHeartbeat; }
    public Map<String, Object> getPluginStatuses() { return pluginStatuses; }
}
