package com.baafoo.core.config;

import java.util.Collections;
import java.util.List;

/**
 * Agent-side configuration.
 * Loaded from baafoo-agent.yml.
 */
public class AgentConfig {

    /** Agent unique identifier (auto-generated if not set) */
    private String agentId;

    /** Environment name this agent belongs to */
    private String environment;

    /** Baafoo Server base URL (e.g., http://localhost:8080) */
    private String serverUrl;

    /** Heartbeat interval in seconds */
    private int heartbeatIntervalSec;

    /** Rule polling interval in seconds */
    private int pollIntervalSec;

    /** Whether Consul service discovery interception is enabled */
    private boolean consulEnabled;

    /** Consul address (e.g., localhost:8500) */
    private String consulAddress;

    /** Protocols to intercept (empty = all) */
    private List<String> protocols;

    /** Maximum recording size in bytes */
    private long maxRecordingSize;

    /** Rule file path for hot-reload (WatchService) */
    private String rulesFilePath;

    /** Whether to enable hot-reload of rule files */
    private boolean hotReload;

    /** Retry count for server connection */
    private int connectionRetries;

    /** Retry backoff base interval in milliseconds */
    private long retryBackoffMs;

    public AgentConfig() {
        this.heartbeatIntervalSec = 30;
        this.pollIntervalSec = 10;
        this.consulEnabled = false;
        this.protocols = Collections.emptyList();
        this.maxRecordingSize = 10 * 1024 * 1024; // 10MB
        this.hotReload = true;
        this.connectionRetries = 3;
        this.retryBackoffMs = 1000;
    }

    // --- Getters / Setters ---

    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }

    public String getEnvironment() { return environment; }
    public void setEnvironment(String environment) { this.environment = environment; }

    public String getServerUrl() { return serverUrl; }
    public void setServerUrl(String serverUrl) { this.serverUrl = serverUrl; }

    public int getHeartbeatIntervalSec() { return heartbeatIntervalSec; }
    public void setHeartbeatIntervalSec(int heartbeatIntervalSec) { this.heartbeatIntervalSec = heartbeatIntervalSec; }

    public int getPollIntervalSec() { return pollIntervalSec; }
    public void setPollIntervalSec(int pollIntervalSec) { this.pollIntervalSec = pollIntervalSec; }

    public boolean isConsulEnabled() { return consulEnabled; }
    public void setConsulEnabled(boolean consulEnabled) { this.consulEnabled = consulEnabled; }

    public String getConsulAddress() { return consulAddress; }
    public void setConsulAddress(String consulAddress) { this.consulAddress = consulAddress; }

    public List<String> getProtocols() { return protocols; }
    public void setProtocols(List<String> protocols) { this.protocols = protocols; }

    public long getMaxRecordingSize() { return maxRecordingSize; }
    public void setMaxRecordingSize(long maxRecordingSize) { this.maxRecordingSize = maxRecordingSize; }

    public String getRulesFilePath() { return rulesFilePath; }
    public void setRulesFilePath(String rulesFilePath) { this.rulesFilePath = rulesFilePath; }

    public boolean isHotReload() { return hotReload; }
    public void setHotReload(boolean hotReload) { this.hotReload = hotReload; }

    public int getConnectionRetries() { return connectionRetries; }
    public void setConnectionRetries(int connectionRetries) { this.connectionRetries = connectionRetries; }

    public long getRetryBackoffMs() { return retryBackoffMs; }
    public void setRetryBackoffMs(long retryBackoffMs) { this.retryBackoffMs = retryBackoffMs; }

    @Override
    public String toString() {
        return "AgentConfig{" +
                "agentId='" + agentId + '\'' +
                ", environment='" + environment + '\'' +
                ", serverUrl='" + serverUrl + '\'' +
                '}';
    }
}
