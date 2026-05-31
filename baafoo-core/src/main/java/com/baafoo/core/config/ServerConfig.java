package com.baafoo.core.config;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Server-side configuration.
 * Loaded from baafoo-server.yml.
 */
public class ServerConfig {

    /** HTTP port for management API + Web console */
    private int httpPort;

    /** Protocol-specific listen ports */
    private Map<String, Integer> protocolPorts;

    /** Data storage directory */
    private String dataDir;

    /** Rule storage directory */
    private String rulesDir;

    /** Recording storage directory */
    private String recordingsDir;

    /** Maximum recording retention days */
    private int recordingRetentionDays;

    /** Maximum rules per page (pagination) */
    private int maxRulesPerPage;

    /** Whether to enable CORS for Web console */
    private boolean corsEnabled;

    /** CORS allowed origins */
    private List<String> corsOrigins;

    /** Web console static file path */
    private String webConsolePath;

    /** Whether to enable request logging */
    private boolean requestLogging;

    /** Agent heartbeat timeout in seconds */
    private int agentHeartbeatTimeoutSec;

    /** Maximum agents per environment */
    private int maxAgentsPerEnvironment;

    /** Unmatched request default behavior: passthrough or 404 */
    private String unmatchedDefault;

    public ServerConfig() {
        this.httpPort = 8080;
        this.protocolPorts = new java.util.HashMap<String, Integer>();
        this.protocolPorts.put("http", 9000);
        this.protocolPorts.put("tcp", 9001);
        this.protocolPorts.put("kafka", 9002);
        this.protocolPorts.put("pulsar", 9003);
        this.protocolPorts.put("jms", 9004);
        this.dataDir = "./data";
        this.rulesDir = "./data/rules";
        this.recordingsDir = "./data/recordings";
        this.recordingRetentionDays = 7;
        this.maxRulesPerPage = 100;
        this.corsEnabled = true;
        this.agentHeartbeatTimeoutSec = 60;
        this.maxAgentsPerEnvironment = 50;
        this.unmatchedDefault = "passthrough";
    }

    // --- Getters / Setters ---

    public int getHttpPort() { return httpPort; }
    public void setHttpPort(int httpPort) { this.httpPort = httpPort; }

    public Map<String, Integer> getProtocolPorts() { return protocolPorts; }
    public void setProtocolPorts(Map<String, Integer> protocolPorts) { this.protocolPorts = protocolPorts; }

    public int getPortForProtocol(String protocol) {
        Integer port = protocolPorts.get(protocol);
        return port != null ? port : 0;
    }

    public String getDataDir() { return dataDir; }
    public void setDataDir(String dataDir) { this.dataDir = dataDir; }

    public String getRulesDir() { return rulesDir; }
    public void setRulesDir(String rulesDir) { this.rulesDir = rulesDir; }

    public String getRecordingsDir() { return recordingsDir; }
    public void setRecordingsDir(String recordingsDir) { this.recordingsDir = recordingsDir; }

    public int getRecordingRetentionDays() { return recordingRetentionDays; }
    public void setRecordingRetentionDays(int recordingRetentionDays) { this.recordingRetentionDays = recordingRetentionDays; }

    public int getMaxRulesPerPage() { return maxRulesPerPage; }
    public void setMaxRulesPerPage(int maxRulesPerPage) { this.maxRulesPerPage = maxRulesPerPage; }

    public boolean isCorsEnabled() { return corsEnabled; }
    public void setCorsEnabled(boolean corsEnabled) { this.corsEnabled = corsEnabled; }

    public List<String> getCorsOrigins() { return corsOrigins; }
    public void setCorsOrigins(List<String> corsOrigins) { this.corsOrigins = corsOrigins; }

    public String getWebConsolePath() { return webConsolePath; }
    public void setWebConsolePath(String webConsolePath) { this.webConsolePath = webConsolePath; }

    public boolean isRequestLogging() { return requestLogging; }
    public void setRequestLogging(boolean requestLogging) { this.requestLogging = requestLogging; }

    public int getAgentHeartbeatTimeoutSec() { return agentHeartbeatTimeoutSec; }
    public void setAgentHeartbeatTimeoutSec(int agentHeartbeatTimeoutSec) { this.agentHeartbeatTimeoutSec = agentHeartbeatTimeoutSec; }

    public int getMaxAgentsPerEnvironment() { return maxAgentsPerEnvironment; }
    public void setMaxAgentsPerEnvironment(int maxAgentsPerEnvironment) { this.maxAgentsPerEnvironment = maxAgentsPerEnvironment; }

    public String getUnmatchedDefault() { return unmatchedDefault; }
    public void setUnmatchedDefault(String unmatchedDefault) { this.unmatchedDefault = unmatchedDefault; }

    @Override
    public String toString() {
        return "ServerConfig{" +
                "httpPort=" + httpPort +
                ", dataDir='" + dataDir + '\'' +
                '}';
    }
}
