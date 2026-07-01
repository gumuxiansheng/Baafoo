package com.baafoo.agent.state;

import com.baafoo.agent.GlobalRouteState;

/**
 * P1-2: Protocol and mode mapper.
 *
 * <p>Encapsulates the protocol/mode logic previously inlined in
 * {@link GlobalRouteState}: {@code isInternal}, {@code inferProtocol},
 * {@code forceRedirectPort}, plus the P1-1 mode-dispatch helpers
 * ({@code shouldIntercept}, {@code shouldRecordStream},
 * {@code isRecordAndStub}, {@code shouldRedirectUnmatched},
 * {@code isPassthrough}, {@code isRecording}). The underlying
 * {@code CURRENT_MODE} and {@code *_PORT} fields stay on
 * {@code GlobalRouteState} for Bootstrap-CL compatibility; this class
 * provides typed dispatch for App-CL callers.</p>
 */
public final class ProtocolMapper {

    /**
     * Recognize connections to the Baafoo server itself (control API + stub ports).
     * In Docker, {@code SERVER_HOST} may resolve to a container IP; both the
     * hostname and the resolved IP are checked.
     */
    public boolean isInternal(String host, int port) {
        boolean isServerHost = "127.0.0.1".equals(host) || "localhost".equals(host)
                || host.equals(GlobalRouteState.SERVER_HOST)
                || (GlobalRouteState.SERVER_HOST_IP != null
                        && host.equals(GlobalRouteState.SERVER_HOST_IP));
        if (!isServerHost) return false;
        if (port == GlobalRouteState.SERVER_PORT) return true;
        if (port == GlobalRouteState.HTTP_PORT || port == GlobalRouteState.TCP_PORT
                || port == GlobalRouteState.KAFKA_PORT
                || port == GlobalRouteState.PULSAR_PORT
                || port == GlobalRouteState.JMS_PORT
                || port == GlobalRouteState.GRPC_PORT
                || port == GlobalRouteState.GRPC_STREAMING_PORT) return true;
        return false;
    }

    /**
     * Infer a fallback stub port from the destination port when no route
     * matches. Used in RECORD_ALL mode to redirect unmatched traffic to
     * Baafoo for recording.
     *
     * @param port destination port in the original connection attempt
     * @return stub port number (never -1)
     */
    public int forceRedirectPort(int port) {
        if (port == 80 || port == 443 || port == 8080 || port == 8443) {
            return GlobalRouteState.HTTP_PORT;
        }
        if (port == 50051 || port == 50052 || port == 9090) {
            return GlobalRouteState.GRPC_PORT;
        }
        if (port == 9092 || port == 9093 || port == 9094) {
            return GlobalRouteState.KAFKA_PORT;
        }
        if (port == 6650 || port == 6651) {
            return GlobalRouteState.PULSAR_PORT;
        }
        if (port == 61616) {
            return GlobalRouteState.JMS_PORT;
        }
        return GlobalRouteState.TCP_PORT;
    }

    /** True unless the active mode is PASSTHROUGH (i.e., the agent should intercept). */
    public boolean shouldIntercept(int mode) {
        return mode != GlobalRouteState.MODE_PASSTHROUGH;
    }

    /** True for modes that record at the stream level (RECORD, RECORD_AND_STUB, RECORD_ALL). */
    public boolean shouldRecordStream(int mode) {
        return mode == GlobalRouteState.MODE_RECORD
                || mode == GlobalRouteState.MODE_RECORD_AND_STUB
                || mode == GlobalRouteState.MODE_RECORD_ALL;
    }

    /** True for RECORD_AND_STUB mode (matched connection must be redirected + recorded). */
    public boolean isRecordAndStub(int mode) {
        return mode == GlobalRouteState.MODE_RECORD_AND_STUB;
    }

    /** True for RECORD_ALL mode (unmatched traffic also redirected / recorded). */
    public boolean shouldRedirectUnmatched(int mode) {
        return mode == GlobalRouteState.MODE_RECORD_ALL;
    }

    public boolean isPassthrough() {
        return GlobalRouteState.CURRENT_MODE == GlobalRouteState.MODE_PASSTHROUGH;
    }

    public boolean isRecording() {
        int mode = GlobalRouteState.CURRENT_MODE;
        return mode == GlobalRouteState.MODE_RECORD
                || mode == GlobalRouteState.MODE_RECORD_AND_STUB
                || mode == GlobalRouteState.MODE_RECORD_ALL;
    }

    /**
     * Infer the high-level protocol name from a connection's target host:port.
     * Internal stub ports identify the protocol directly; external connections
     * are mapped via the route table (with a DNS-cache fallback for Docker/IP
     * cases). Returns {@code "tcp"} when no mapping is found.
     */
    public String inferProtocol(String host, int port) {
        if (isInternal(host, port)) {
            if (port == GlobalRouteState.HTTP_PORT) return "http";
            if (port == GlobalRouteState.TCP_PORT) return "tcp";
            if (port == GlobalRouteState.KAFKA_PORT) return "kafka";
            if (port == GlobalRouteState.PULSAR_PORT) return "pulsar";
            if (port == GlobalRouteState.JMS_PORT) return "jms";
            if (port == GlobalRouteState.GRPC_PORT) return "grpc";
        }
        String[] route = GlobalRouteState.lookup(host, port);
        String protocol = protocolForRoute(route);
        if (protocol != null) return protocol;
        if (route == null && !"127.0.0.1".equals(host) && !"localhost".equals(host)) {
            String originalDomain = GlobalRouteState.DNS_CACHE.get(host);
            if (originalDomain != null) {
                protocol = protocolForRoute(GlobalRouteState.lookup(originalDomain, port));
                if (protocol != null) return protocol;
            }
        }
        return "tcp";
    }

    /** Map a {@code lookup(...)} result's target port to a protocol name, or null. */
    private String protocolForRoute(String[] route) {
        if (route == null) return null;
        int targetPort = Integer.parseInt(route[1]);
        if (targetPort == GlobalRouteState.HTTP_PORT) return "http";
        if (targetPort == GlobalRouteState.TCP_PORT) return "tcp";
        if (targetPort == GlobalRouteState.KAFKA_PORT) return "kafka";
        if (targetPort == GlobalRouteState.PULSAR_PORT) return "pulsar";
        if (targetPort == GlobalRouteState.JMS_PORT) return "jms";
        if (targetPort == GlobalRouteState.GRPC_PORT) return "grpc";
        return null;
    }
}
