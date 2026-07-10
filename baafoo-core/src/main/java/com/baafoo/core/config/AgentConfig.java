package com.baafoo.core.config;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Agent-side configuration.
 * Loaded from baafoo-agent.yml.
 */
public class AgentConfig {

    /** Server connection configuration (host, ports) */
    private ServerConnection server = new ServerConnection();

    /** Agent unique identifier (auto-generated if not set) */
    private String agentId;

    /** Environment name this agent belongs to */
    private String environment;

    /** Baafoo Server base URL (e.g., http://localhost:8084) — legacy field, overridden by server.host if set */
    private String serverUrl;

    /** Heartbeat interval in seconds */
    private int heartbeatIntervalSec;

    /** Rule polling interval in seconds */
    private int pollIntervalSec;

    /** Protocols to intercept (empty = all) */
    private List<String> protocols;

    /** Maximum recording size in bytes */
    private long maxRecordingSize;

    /** Rule file path for hot-reload (WatchService) */
    private String rulesFilePath;

    /** Whether to enable hot-reload of rule files */
    private boolean hotReload;

    /**
     * Fail-open mode: when true, Agent initialization failures are silently ignored
     * and all requests pass through. When false (default), Agent logs ERROR and
     * all requests still pass through, but the error is visible.
     * Corresponds to {@code baafoo.agent.fail-open} config key.
     */
    private boolean failOpen;

    /** Retry count for server connection */
    private int connectionRetries;

    /** Retry backoff base interval in milliseconds */
    private long retryBackoffMs;

    /** Plugin system configuration */
    private PluginsConfig plugins = new PluginsConfig();

    public AgentConfig() {
        this.heartbeatIntervalSec = 30;
        this.pollIntervalSec = 10;
        this.protocols = Collections.emptyList();
        this.maxRecordingSize = 10 * 1024 * 1024; // 10MB
        this.hotReload = true;
        this.connectionRetries = 3;
        this.retryBackoffMs = 1000;
    }

    // --- Getters / Setters ---

    public ServerConnection getServer() { return server; }
    public void setServer(ServerConnection server) { this.server = server; }

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

    public List<String> getProtocols() { return protocols; }
    public void setProtocols(List<String> protocols) { this.protocols = protocols; }

    public long getMaxRecordingSize() { return maxRecordingSize; }
    public void setMaxRecordingSize(long maxRecordingSize) { this.maxRecordingSize = maxRecordingSize; }

    public String getRulesFilePath() { return rulesFilePath; }
    public void setRulesFilePath(String rulesFilePath) { this.rulesFilePath = rulesFilePath; }

    public boolean isHotReload() { return hotReload; }
    public void setHotReload(boolean hotReload) { this.hotReload = hotReload; }

    public boolean isFailOpen() { return failOpen; }
    public void setFailOpen(boolean failOpen) { this.failOpen = failOpen; }

    public int getConnectionRetries() { return connectionRetries; }
    public void setConnectionRetries(int connectionRetries) { this.connectionRetries = connectionRetries; }

    public long getRetryBackoffMs() { return retryBackoffMs; }
    public void setRetryBackoffMs(long retryBackoffMs) { this.retryBackoffMs = retryBackoffMs; }

    public PluginsConfig getPlugins() { return plugins; }
    public void setPlugins(PluginsConfig plugins) { this.plugins = plugins; }

    @Override
    public String toString() {
        return "AgentConfig{" +
                "server=" + server +
                ", agentId='" + agentId + '\'' +
                ", environment='" + environment + '\'' +
                ", serverUrl='" + serverUrl + '\'' +
                '}';
    }

    /**
     * Plugin system configuration.
     * Maps to the {@code plugins:} section in baafoo-agent.yml.
     */
    public static class PluginsConfig {
        /** Whether the plugin system is enabled */
        private boolean enabled = true;

        /** Plugin directory path (relative or absolute) */
        private String directory = "./plugins";

        /** Per-plugin configuration, keyed by plugin name */
        private Map<String, Map<String, Object>> configs = Collections.emptyMap();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public String getDirectory() { return directory; }
        public void setDirectory(String directory) { this.directory = directory; }

        public Map<String, Map<String, Object>> getConfigs() { return configs; }
        public void setConfigs(Map<String, Map<String, Object>> configs) { this.configs = configs; }

        /**
         * Get configuration for a specific plugin.
         * @param pluginName the plugin name (from {@code AgentPlugin.getName()})
         * @return plugin config map, or empty map if not configured
         */
        public Map<String, Object> getConfig(String pluginName) {
            if (configs == null || pluginName == null) return Collections.emptyMap();
            Map<String, Object> c = configs.get(pluginName);
            return c != null ? c : Collections.emptyMap();
        }

        @Override
        public String toString() {
            return "PluginsConfig{enabled=" + enabled +
                    ", directory='" + directory + '\'' +
                    ", configs=" + configs + '}';
        }
    }

    /**
     * Agent-to-server connection configuration.
     * Maps to the {@code server:} section in baafoo-agent.yml.
     *
     * <p>Not to be confused with {@link com.baafoo.core.config.ServerConfig},
     * which is the server-side configuration loaded from baafoo-server.yml.</p>
     */
    public static class ServerConnection {
        /** Server host (e.g., "127.0.0.1") */
        private String host = "127.0.0.1";

        /** Whether to use HTTPS for the control channel */
        private boolean useSsl = false;

        /** Server API port (control channel, default 8084) */
        private int apiPort = 8084;

        /** HTTP stub port (default 9000) */
        private int httpPort = 9000;

        /** TCP stub port (default 9001) */
        private int tcpPort = 9001;

        /** Kafka stub port (default 9002) */
        private int kafkaPort = 9002;

        /** Pulsar stub port (default 9003) */
        private int pulsarPort = 9003;

        /** JMS stub port (default 9004) */
        private int jmsPort = 9004;

        /** gRPC stub port (default 9005) */
        private int grpcPort = 9005;

        /** API key for server authentication */
        private String apiKey;

        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }

        public boolean isUseSsl() { return useSsl; }
        public void setUseSsl(boolean useSsl) { this.useSsl = useSsl; }

        public int getApiPort() { return apiPort; }
        public void setApiPort(int apiPort) { this.apiPort = apiPort; }

        public int getHttpPort() { return httpPort; }
        public void setHttpPort(int httpPort) { this.httpPort = httpPort; }

        public int getTcpPort() { return tcpPort; }
        public void setTcpPort(int tcpPort) { this.tcpPort = tcpPort; }

        public int getKafkaPort() { return kafkaPort; }
        public void setKafkaPort(int kafkaPort) { this.kafkaPort = kafkaPort; }

        public int getPulsarPort() { return pulsarPort; }
        public void setPulsarPort(int pulsarPort) { this.pulsarPort = pulsarPort; }

        public int getJmsPort() { return jmsPort; }
        public void setJmsPort(int jmsPort) { this.jmsPort = jmsPort; }

        public int getGrpcPort() { return grpcPort; }
        public void setGrpcPort(int grpcPort) { this.grpcPort = grpcPort; }

        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }

        /**
         * Get the stub port for a given protocol name.
         */
        public int getPortForProtocol(String protocol) {
            if (protocol == null) return tcpPort;
            switch (protocol.toLowerCase()) {
                case "http": return httpPort;
                case "tcp": return tcpPort;
                case "kafka": return kafkaPort;
                case "pulsar": return pulsarPort;
                case "jms": return jmsPort;
                case "grpc": return grpcPort;
                default: return tcpPort;
            }
        }

        @Override
        public String toString() {
            return "ServerConnection{host='" + host + "', useSsl=" + useSsl +
                    ", apiPort=" + apiPort +
                    ", http=" + httpPort + ", tcp=" + tcpPort +
                    ", kafka=" + kafkaPort + ", pulsar=" + pulsarPort +
                    ", jms=" + jmsPort +
                    ", grpc=" + grpcPort + '}';
        }
    }
}
