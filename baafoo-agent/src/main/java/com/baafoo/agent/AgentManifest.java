package com.baafoo.agent;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Bootstrap-safe agent manifest — the ONLY bridge between inlined Advice code
 * and the rest of the agent.
 *
 * <p>All fields use types loadable by the Bootstrap ClassLoader:
 * AtomicReference, RouteTable (same package, loaded before transform),
 * String, boolean, int. No Logger, no RouteManager, no Rule, no Enum.</p>
 *
 * <p>Advice classes read from this class exclusively. Background threads
 * (RouteManager, ControlChannel) write to it via atomic swap.</p>
 */
public final class AgentManifest {

    // ---- Mode constants (int, NOT enum — enum would need Bootstrap CL) ----

    /** Stub mode: return pre-configured stub responses */
    public static final int MODE_STUB = 0;

    /** Passthrough mode: forward to real downstream */
    public static final int MODE_PASSTHROUGH = 1;

    /** Record mode: record real responses for later replay */
    public static final int MODE_RECORD = 2;

    /** Record-and-stub mode: record AND return stubs */
    public static final int MODE_RECORD_AND_STUB = 3;

    // ---- Atomic route table (swap for hot-reload) ----

    /** Current route table, atomically swappable */
    public static final AtomicReference<RouteTable> ROUTE_TABLE =
            new AtomicReference<RouteTable>(new RouteTable());

    // ---- Agent state ----

    /** Current agent mode (volatile for visibility) */
    public static volatile int currentMode = MODE_STUB;

    /** Whether the agent has been loaded successfully */
    public static volatile boolean agentLoaded = false;

    /** Baafoo server host (e.g., "127.0.0.1") */
    public static volatile String serverHost = "127.0.0.1";

    /** Baafoo server API port (e.g., 8084) */
    public static volatile int serverPort = 8084;

    /** HTTP stub port (default 9000) */
    public static volatile int httpPort = 9000;

    /** TCP stub port (default 9001) */
    public static volatile int tcpPort = 9001;

    /** Kafka stub port (default 9002) */
    public static volatile int kafkaPort = 9002;

    /** Pulsar stub port (default 9003) */
    public static volatile int pulsarPort = 9003;

    /** JMS stub port (default 9004) */
    public static volatile int jmsPort = 9004;

    /** gRPC stub port (default 9005) */
    public static volatile int grpcPort = 9005;

    /** gRPC streaming stub port (HTTP/2, default 10005) */
    public static volatile int grpcStreamingPort = 10005;

    /** Environment ID this agent belongs to */
    public static volatile String environmentId = "default";

    /** Agent ID assigned by the server */
    public static volatile String agentId = "";

    private AgentManifest() {}

    public static void setServerHost(String host) {
        serverHost = host;
        com.baafoo.agent.GlobalRouteState.SERVER_HOST = host;
        // Resolve hostname to IP for isInternal() checks (e.g., Docker container name → IP)
        resolveServerHostIp();
    }

    /**
     * Resolve SERVER_HOST to an IP address and cache it in GlobalRouteState.SERVER_HOST_IP.
     * This allows isInternal() to recognize connections made via the resolved IP
     * (e.g., when Docker DNS resolves "server" → "172.19.0.2").
     * Safe to call multiple times; resolution is only attempted once.
     */
    public static void resolveServerHostIp() {
        String host = serverHost;
        if (host == null || host.isEmpty() || "127.0.0.1".equals(host) || "localhost".equals(host)) {
            return; // No need to resolve localhost
        }
        if (com.baafoo.agent.GlobalRouteState.SERVER_HOST_IP != null) {
            return; // Already resolved
        }
        try {
            java.net.InetAddress addr = java.net.InetAddress.getByName(host);
            String ip = addr.getHostAddress();
            if (ip != null && !ip.equals(host)) {
                com.baafoo.agent.GlobalRouteState.SERVER_HOST_IP = ip;
                // Sync to Bootstrap CL copy of GlobalRouteState
                syncServerHostIpToBootstrapCL(ip);
            }
        } catch (Exception e) {
            // DNS not available yet (e.g., Docker network not ready); will retry later
        }
    }

    /**
     * Sync SERVER_HOST_IP to the Bootstrap CL copy of GlobalRouteState.
     * The Bootstrap CL has a separate class instance; its static fields
     * must be set via reflection.
     */
    private static void syncServerHostIpToBootstrapCL(String ip) {
        try {
            Class<?> bootGRS = com.baafoo.agent.BaafooAgent.getBootstrapGRSClass();
            if (bootGRS != null) {
                java.lang.reflect.Field field = bootGRS.getField("SERVER_HOST_IP");
                field.set(null, ip);
            }
        } catch (Exception e) {
            // Bootstrap CL not ready yet; will be synced on next syncGlobalRouteStateToBootstrapCL call
        }
    }

    public static void setServerPort(int port) {
        serverPort = port;
        com.baafoo.agent.GlobalRouteState.SERVER_PORT = port;
    }

    public static void setHttpPort(int port) {
        httpPort = port;
        com.baafoo.agent.GlobalRouteState.HTTP_PORT = port;
    }

    public static void setTcpPort(int port) {
        tcpPort = port;
        com.baafoo.agent.GlobalRouteState.TCP_PORT = port;
    }

    public static void setKafkaPort(int port) {
        kafkaPort = port;
        com.baafoo.agent.GlobalRouteState.KAFKA_PORT = port;
    }

    public static void setPulsarPort(int port) {
        pulsarPort = port;
        com.baafoo.agent.GlobalRouteState.PULSAR_PORT = port;
    }

    public static void setJmsPort(int port) {
        jmsPort = port;
        com.baafoo.agent.GlobalRouteState.JMS_PORT = port;
    }

    public static void setGrpcPort(int port) {
        grpcPort = port;
        com.baafoo.agent.GlobalRouteState.GRPC_PORT = port;
    }

    public static void setGrpcStreamingPort(int port) {
        grpcStreamingPort = port;
        com.baafoo.agent.GlobalRouteState.GRPC_STREAMING_PORT = port;
    }

    public static void setCurrentMode(int mode) {
        currentMode = mode;
        com.baafoo.agent.GlobalRouteState.CURRENT_MODE = mode;
    }

    /**
     * Check if current mode is passthrough (i.e., should we skip interception).
     *
     * @return true if in passthrough mode
     */
    public static boolean isPassthrough() {
        return currentMode == MODE_PASSTHROUGH;
    }

    /**
     * Check if current mode involves recording.
     *
     * @return true if recording is active
     */
    public static boolean isRecording() {
        return currentMode == MODE_RECORD || currentMode == MODE_RECORD_AND_STUB;
    }

    /**
     * Get mode name string for logging.
     *
     * @return mode name
     */
    public static String getModeName() {
        switch (currentMode) {
            case 0: return "stub";
            case 1: return "passthrough";
            case 2: return "record";
            case 3: return "record-and-stub";
            default: return "unknown";
        }
    }
}
