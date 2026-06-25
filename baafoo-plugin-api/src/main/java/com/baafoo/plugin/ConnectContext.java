package com.baafoo.plugin;

/**
 * Context for the connection-phase hook.
 *
 * <p>Carries information available at Socket.connect() / SocketChannel.connect()
 * time, before any application data has been exchanged.</p>
 *
 * <p>Only uses JDK types and plugin-api internal types — no domain model dependency,
 * keeping baafoo-plugin-api zero-dependency (Bootstrap CL loadable).</p>
 */
public class ConnectContext {

    /** Protocol inferred from target port (http, tcp, grpc, kafka, ...) */
    private final String protocol;

    /** Target host */
    private final String host;

    /** Target port */
    private final int port;

    /** Service name (from Consul, if available) */
    private final String serviceName;

    /** Agent environment ID */
    private final String environmentId;

    /** Agent ID */
    private final String agentId;

    /** Original target string (for gRPC: "dns:///host:port") */
    private final String rawTarget;

    /** Tenant name (e.g., Pulsar tenant from service URL path) */
    private final String tenant;

    /** Destination name (e.g., JMS queue/topic from broker URL path) */
    private final String destination;

    public ConnectContext(String protocol, String host, int port,
                          String serviceName, String environmentId,
                          String agentId, String rawTarget) {
        this(protocol, host, port, serviceName, environmentId, agentId, rawTarget, null, null);
    }

    public ConnectContext(String protocol, String host, int port,
                          String serviceName, String environmentId,
                          String agentId, String rawTarget,
                          String tenant, String destination) {
        this.protocol = protocol;
        this.host = host;
        this.port = port;
        this.serviceName = serviceName;
        this.environmentId = environmentId;
        this.agentId = agentId;
        this.rawTarget = rawTarget;
        this.tenant = tenant;
        this.destination = destination;
    }

    // --- Getters ---

    public String getProtocol() { return protocol; }
    public String getHost() { return host; }
    public int getPort() { return port; }
    public String getServiceName() { return serviceName; }
    public String getEnvironmentId() { return environmentId; }
    public String getAgentId() { return agentId; }
    public String getRawTarget() { return rawTarget; }
    public String getTenant() { return tenant; }
    public String getDestination() { return destination; }

    /**
     * Convert to legacy PluginContext for backward-compatible intercept() call.
     */
    public PluginContext toLegacyContext() {
        PluginContext ctx = new PluginContext();
        ctx.setProtocol(protocol);
        ctx.setHost(host);
        ctx.setPort(port);
        ctx.setServiceName(serviceName);
        ctx.setTenant(tenant);
        ctx.setDestination(destination);
        return ctx;
    }
}
