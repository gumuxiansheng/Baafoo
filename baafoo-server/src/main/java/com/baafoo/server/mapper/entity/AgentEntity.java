package com.baafoo.server.mapper.entity;

public class AgentEntity {
    private String agentId;
    private String environment;
    private String hostname;
    private String version;
    private String protocolsJson;
    private Long registeredAt;
    private Long lastHeartbeat;

    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }

    public String getEnvironment() { return environment; }
    public void setEnvironment(String environment) { this.environment = environment; }

    public String getHostname() { return hostname; }
    public void setHostname(String hostname) { this.hostname = hostname; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public String getProtocolsJson() { return protocolsJson; }
    public void setProtocolsJson(String protocolsJson) { this.protocolsJson = protocolsJson; }

    public Long getRegisteredAt() { return registeredAt; }
    public void setRegisteredAt(Long registeredAt) { this.registeredAt = registeredAt; }

    public Long getLastHeartbeat() { return lastHeartbeat; }
    public void setLastHeartbeat(Long lastHeartbeat) { this.lastHeartbeat = lastHeartbeat; }
}
