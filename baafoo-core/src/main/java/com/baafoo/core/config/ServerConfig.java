package com.baafoo.core.config;

import com.fasterxml.jackson.annotation.JsonIgnore;

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

    /** Maximum total recording storage in MB */
    private int recordingMaxSizeMb;

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

    /** Disable SSL certificate verification for passthrough proxy (test environments only) */
    private boolean passthroughSslVerifyDisabled;

    /** Advertised host for Kafka Metadata and Pulsar LOOKUP responses.
     *  Set this to a host reachable by EXTERNAL clients — clients that cannot
     *  reach the server's interface/container IP directly. This covers Docker
     *  port-mapping, NAT, and bare-metal/VM public access (e.g. "localhost"
     *  or a public hostname). When unset, auto-detection (gateway heuristic)
     *  is used. */
    private String messagingAdvertisedHost;

    /** Default mode when agent environment cannot be determined (no IP match).
     *  Values: "passthrough" (safe, requests go to real services) or "stub"
     *  (aggressive, unmatched requests get 404). Default: "passthrough". */
    private String unknownEnvironmentDefault;

    /** Database configuration */
    private DatabaseConfig database;

    /** Authentication configuration */
    private AuthConfig auth;

    public ServerConfig() {
        this.httpPort = 8084;
        this.protocolPorts = new java.util.HashMap<String, Integer>();
        this.protocolPorts.put("http", 9000);
        this.protocolPorts.put("tcp", 9001);
        this.protocolPorts.put("kafka", 9002);
        this.protocolPorts.put("pulsar", 9003);
        this.protocolPorts.put("jms", 9004);
        this.protocolPorts.put("grpc", 9005);
        this.dataDir = "./data";
        this.rulesDir = "./data/rules";
        this.recordingsDir = "./data/recordings";
        this.recordingRetentionDays = 7;
        this.recordingMaxSizeMb = 500;
        this.maxRulesPerPage = 100;
        this.corsEnabled = true;
        this.agentHeartbeatTimeoutSec = 60;
        this.maxAgentsPerEnvironment = 50;
        this.unmatchedDefault = "passthrough";
        this.database = new DatabaseConfig();
        this.auth = new AuthConfig();
    }

    // --- Getters / Setters ---

    public int getHttpPort() { return httpPort; }
    public void setHttpPort(int httpPort) { this.httpPort = httpPort; }

    public Map<String, Integer> getProtocolPorts() { return protocolPorts; }
    /**
     * Merge YAML-provided ports over the constructor defaults instead of replacing
     * the whole map. This guarantees protocols omitted from a config file (e.g. grpc,
     * which was added later) keep their default port and are not silently dropped —
     * a missing entry previously caused {@code getPortForProtocol("grpc")} to return 0
     * and the gRPC stub server to never start.
     */
    public void setProtocolPorts(Map<String, Integer> protocolPorts) {
        if (this.protocolPorts == null) {
            this.protocolPorts = new java.util.HashMap<String, Integer>();
        }
        this.protocolPorts.putAll(protocolPorts);
    }

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

    public int getRecordingMaxSizeMb() { return recordingMaxSizeMb; }
    public void setRecordingMaxSizeMb(int recordingMaxSizeMb) { this.recordingMaxSizeMb = recordingMaxSizeMb; }

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

    public boolean isPassthroughSslVerifyDisabled() { return passthroughSslVerifyDisabled; }
    public void setPassthroughSslVerifyDisabled(boolean passthroughSslVerifyDisabled) { this.passthroughSslVerifyDisabled = passthroughSslVerifyDisabled; }

    public String getMessagingAdvertisedHost() { return messagingAdvertisedHost; }
    public void setMessagingAdvertisedHost(String messagingAdvertisedHost) { this.messagingAdvertisedHost = messagingAdvertisedHost; }

    public String getUnknownEnvironmentDefault() { return unknownEnvironmentDefault; }
    public void setUnknownEnvironmentDefault(String unknownEnvironmentDefault) { this.unknownEnvironmentDefault = unknownEnvironmentDefault; }

    public DatabaseConfig getDatabase() { return database; }
    public void setDatabase(DatabaseConfig database) { this.database = database; }

    public AuthConfig getAuth() { return auth; }
    public void setAuth(AuthConfig auth) { this.auth = auth; }

    @Override
    public String toString() {
        return "ServerConfig{" +
                "httpPort=" + httpPort +
                ", dataDir='" + dataDir + '\'' +
                ", database=" + database +
                '}';
    }

    /**
     * Database configuration.
     * <p>Use type "h2" (default) for embedded H2, or "postgresql" for external PostgreSQL.</p>
     */
    public static class DatabaseConfig {
        private String type = "h2";
        private String url;
        private String username = "sa";
        private String password = "";

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }

        public String getUsername() { return username; }
        public void setUsername(String username) { this.username = username; }

        public String getPassword() { return password; }
        public void setPassword(String password) { this.password = password; }

        @Override
        public String toString() {
            return "DatabaseConfig{type='" + type + "', url='" + url + "'}";
        }
    }

    public static class AuthConfig {
        private boolean enabled = true;
        private boolean localBypass = false;
        private String jwtSecret;
        private long tokenExpiryHours = 24;
        private Map<String, String> apiKeys;
        /** Trusted proxy IPs that are allowed to set X-Forwarded-For (empty = trust none) */
        private java.util.Set<String> trustedProxies = new java.util.HashSet<>();
        /** SSO configuration */
        private SsoConfig sso = new SsoConfig();

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }

        public boolean isLocalBypass() { return localBypass; }
        public void setLocalBypass(boolean localBypass) { this.localBypass = localBypass; }

        public String getJwtSecret() { return jwtSecret; }
        public void setJwtSecret(String jwtSecret) { this.jwtSecret = jwtSecret; }

        public long getTokenExpiryHours() { return tokenExpiryHours; }
        public void setTokenExpiryHours(long tokenExpiryHours) { this.tokenExpiryHours = tokenExpiryHours; }

        public Map<String, String> getApiKeys() { return apiKeys; }
        public void setApiKeys(Map<String, String> apiKeys) { this.apiKeys = apiKeys; }

        public java.util.Set<String> getTrustedProxies() { return trustedProxies; }
        public void setTrustedProxies(java.util.Set<String> trustedProxies) { this.trustedProxies = trustedProxies; }

        public SsoConfig getSso() { return sso; }
        public void setSso(SsoConfig sso) { this.sso = sso; }

        @Override
        public String toString() {
            // M11: include all fields (jwtSecret and apiKeys are intentionally
            // omitted from the toString to avoid leaking credentials into logs).
            // tokenExpiryHours, trustedProxies, and enabled/localBypass are
            // safe to log and previously were missing — making it hard to
            // diagnose auth misconfigurations from a single "Loaded server
            // config" log line.
            return "AuthConfig{enabled=" + enabled
                    + ", localBypass=" + localBypass
                    + ", tokenExpiryHours=" + tokenExpiryHours
                    + ", apiKeyCount=" + (apiKeys != null ? apiKeys.size() : 0)
                    + ", trustedProxies=" + trustedProxies
                    + ", sso=" + sso
                    + '}';
        }
    }

    public static class SsoConfig {
        private String baseUrl = "http://localhost:8085";
        private String callbackUrl = "http://localhost:8084/sso/callback";
        private String projectCode = "BAAFOO";
        private boolean secure = false;
        /** When true, user profile/password changes are redirected to Ehre. */
        private boolean useEhreProfile = false;

        public String getBaseUrl() { return baseUrl; }
        public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }

        public String getCallbackUrl() { return callbackUrl; }
        public void setCallbackUrl(String callbackUrl) { this.callbackUrl = callbackUrl; }

        public String getProjectCode() { return projectCode; }
        public void setProjectCode(String projectCode) { this.projectCode = projectCode; }

        public boolean isSecure() { return secure; }
        public void setSecure(boolean secure) { this.secure = secure; }

        public boolean isUseEhreProfile() { return useEhreProfile; }
        public void setUseEhreProfile(boolean useEhreProfile) { this.useEhreProfile = useEhreProfile; }

        @JsonIgnore
        public String getLoginUrl() {
            try {
                return baseUrl + "/api/sso/login?project="
                        + java.net.URLEncoder.encode(projectCode, "UTF-8")
                        + "&callback="
                        + java.net.URLEncoder.encode(callbackUrl, "UTF-8");
            } catch (java.io.UnsupportedEncodingException e) {
                // UTF-8 is guaranteed by the Java spec; this should never happen
                return baseUrl + "/api/sso/login?project=" + projectCode + "&callback=" + callbackUrl;
            }
        }

        @Override
        public String toString() {
            return "SsoConfig{baseUrl='" + baseUrl + "', callbackUrl='" + callbackUrl + "', projectCode='" + projectCode + "', secure=" + secure + ", useEhreProfile=" + useEhreProfile + '}';
        }
    }
}
