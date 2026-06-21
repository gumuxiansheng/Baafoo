package com.baafoo.agent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

public final class GlobalRouteState {

    public static final class HostPort {
        public final String host;
        public final int port;

        public HostPort(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }

    public static volatile ConcurrentHashMap<String, HostPort> ROUTES = new ConcurrentHashMap<String, HostPort>();

    public static volatile int CURRENT_MODE = 0;

    public static final int MODE_STUB = 0;
    public static final int MODE_PASSTHROUGH = 1;
    public static final int MODE_RECORD = 2;
    public static final int MODE_RECORD_AND_STUB = 3;
    public static final int MODE_RECORD_ALL = 4;

    public static volatile String SERVER_HOST = "127.0.0.1";

    /** Resolved IP address of SERVER_HOST (e.g., Docker container IP).
     *  Set lazily when DNS resolution succeeds. Used by isInternal() to
     *  recognize connections to the server via its container IP. */
    public static volatile String SERVER_HOST_IP = null;

    public static volatile int SERVER_PORT = 8084;

    // ---- Protocol stub ports (set from AgentConfig, synced to Bootstrap CL) ----

    /** HTTP stub port (default 9000) */
    public static volatile int HTTP_PORT = 9000;

    /** TCP stub port (default 9001) */
    public static volatile int TCP_PORT = 9001;

    /** Kafka stub port (default 9002) */
    public static volatile int KAFKA_PORT = 9002;

    /** Pulsar stub port (default 9003) */
    public static volatile int PULSAR_PORT = 9003;

    /** JMS stub port (default 9004) */
    public static volatile int JMS_PORT = 9004;

    // ---- Logging bridge ----
    // Set by the App CL side (BaafooAgent) with SLF4J-backed implementations.
    // Advice code inlined into Bootstrap CL classes calls logInfo/logWarn/logError/logDebug,
    // which delegate to these handlers. Falls back to System.out when not set.

    /** @see #logInfo(String) */
    public static volatile Consumer<String> LOG_INFO_HANDLER;

    /** @see #logWarn(String) */
    public static volatile Consumer<String> LOG_WARN_HANDLER;

    /** @see #logError(String) */
    public static volatile Consumer<String> LOG_ERROR_HANDLER;

    /** @see #logDebug(String) */
    public static volatile Consumer<String> LOG_DEBUG_HANDLER;

    /**
     * DNS resolution cache: maps resolved IP addresses back to original domain names.
     * Populated when InetAddress.getByName is intercepted.
     * Used in SocketConnectAdvice/NioSocketConnectAdvice to look up routes by domain
     * with an IP address instead of the original hostname.
     *
     * Bounded at {@link #MAX_DNS_CACHE_SIZE} entries to prevent memory leak.
     * Eviction strategy: when full, removes all entries — this is a best-effort
     * cache for route-lookup fallback, so occasional full clears are acceptable.
     */
    public static final ConcurrentHashMap<String, String> DNS_CACHE = new ConcurrentHashMap<String, String>();

    /**
     * ThreadLocal for passing DNS redirect target from OnMethodEnter to OnMethodExit.
     * Set when a hostname matches a route, so that if DNS resolution fails,
     * we can provide a fake resolution pointing to the stub server.
     */
    public static final ThreadLocal<String> DNS_REDIRECT_TARGET = new ThreadLocal<String>();

    /** Maximum number of entries in {@link #DNS_CACHE} */
    private static final int MAX_DNS_CACHE_SIZE = 10000;

    private static final java.util.concurrent.atomic.AtomicBoolean DNS_EVICTION_IN_PROGRESS =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    // ---- Recording session tracking ----
    // Maps socket identity (System.identityHashCode) to session info:
    // String[] { sessionId, host, portString }
    // Populated by SocketConnectAdvice in RECORD/RECORD_AND_STUB mode.
    // Consumed by SocketGetStreamAdvice to wrap streams with recording.

    /**
     * Active recording sessions keyed by socket identity hash.
     * Value is String[] { sessionId, host, portString }.
     */
    public static final ConcurrentHashMap<Integer, String[]> RECORDING_SESSIONS =
            new ConcurrentHashMap<Integer, String[]>();

    /** Maximum number of concurrent recording sessions (prevents memory leak) */
    private static final int MAX_RECORDING_SESSIONS = 10000;

    /**
     * Bridge function to wrap an InputStream with recording.
     * Set from the App CL (BaafooAgent) with a real implementation.
     * Arguments: (InputStream, String[] sessionInfo where sessionInfo = {sessionId, host, portString})
     * Returns: wrapped InputStream.
     * If null, no recording wrapping is applied.
     */
    public static volatile java.util.function.BiFunction<java.io.InputStream, String[], java.io.InputStream> INPUT_STREAM_WRAPPER;

    /**
     * Bridge function to wrap an OutputStream with recording.
     * Set from the App CL (BaafooAgent) with a real implementation.
     * Arguments: (OutputStream, String[] sessionInfo where sessionInfo = {sessionId, host, portString})
     * Returns: wrapped OutputStream.
     * If null, no recording wrapping is applied.
     */
    public static volatile java.util.function.BiFunction<java.io.OutputStream, String[], java.io.OutputStream> OUTPUT_STREAM_WRAPPER;

    /**
     * Bridge function for NIO SocketChannel recording.
     * Set from the App CL (BaafooAgent) with a real implementation.
     * Arguments: Object[] { String[] sessionInfo, String direction, String hexData }
     * where sessionInfo = {sessionId, host, portString}.
     * If null, NIO recording data is silently dropped.
     */
    public static volatile java.util.function.Consumer<Object[]> NIO_RECORDING_HANDLER;

    /**
     * Bridge function for plugin SPI consultation from Bootstrap CL advice.
     * Set from the App CL (BaafooAgent) with a real implementation that calls
     * PluginManager.getPlugin(InterceptTarget.SOCKET).intercept(...).
     * Arguments: Object[] { String host, Integer port }
     * Returns: Object[] { String targetHost, Integer targetPort } or null if no redirect.
     * If null, no plugin consultation is performed (default routing only).
     */
    public static volatile java.util.function.Function<Object[], Object[]> PLUGIN_CONSULT_FN;

    private GlobalRouteState() {}

    // ---- Logging methods for Bootstrap CL advice ----

    public static void logInfo(String msg) {
        Consumer<String> h = LOG_INFO_HANDLER;
        if (h != null) {
            try { h.accept(msg); } catch (Throwable t) { System.out.println(msg); }
        } else {
            System.out.println(msg);
        }
    }

    public static void logWarn(String msg) {
        Consumer<String> h = LOG_WARN_HANDLER;
        if (h != null) {
            try { h.accept(msg); } catch (Throwable t) { System.out.println(msg); }
        } else {
            System.out.println(msg);
        }
    }

    public static void logError(String msg) {
        Consumer<String> h = LOG_ERROR_HANDLER;
        if (h != null) {
            try { h.accept(msg); } catch (Throwable t) { System.out.println(msg); }
        } else {
            System.out.println(msg);
        }
    }

    public static void logDebug(String msg) {
        Consumer<String> h = LOG_DEBUG_HANDLER;
        if (h != null) {
            try { h.accept(msg); } catch (Throwable t) { System.out.println(msg); }
        } else {
            System.out.println(msg);
        }
    }

    /**
     * Record a DNS resolution for later route lookup.
     *
     * @param domain the original domain name (e.g., "api.example.com")
     * @param ip     the resolved IP address (e.g., "93.184.216.34")
     */
    public static void recordDns(String domain, String ip) {
        if (domain == null || domain.isEmpty() || ip == null || ip.isEmpty()) {
            return;
        }
        if (DNS_CACHE.size() >= MAX_DNS_CACHE_SIZE) {
            if (DNS_EVICTION_IN_PROGRESS.compareAndSet(false, true)) {
                try {
                    DNS_CACHE.clear();
                } finally {
                    DNS_EVICTION_IN_PROGRESS.set(false);
                }
            }
            return;
        }
        DNS_CACHE.putIfAbsent(ip, domain);
    }

    public static String[] lookup(String host, int port) {
        if (host == null) {
            return null;
        }
        // First try exact host:port match
        String key = host + ":" + port;
        HostPort target = ROUTES.get(key);
        if (target != null) {
            return new String[]{target.host, String.valueOf(target.port)};
        }
        // Fallback: try host-only match (for rules without specific port)
        HostPort hostOnly = ROUTES.get(host);
        if (hostOnly != null) {
            return new String[]{hostOnly.host, String.valueOf(hostOnly.port)};
        }
        return null;
    }

    public static HostPort lookupByHost(String host) {
        if (host == null) return null;
        // Check host-only entries first
        HostPort hostOnly = ROUTES.get(host);
        if (hostOnly != null) return hostOnly;
        // Check host:port entries
        String prefix = host + ":";
        for (Map.Entry<String, HostPort> entry : ROUTES.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                return entry.getValue();
            }
        }
        return null;
    }

    public static HostPort lookupService(String serviceName) {
        if (serviceName == null) return null;
        return ROUTES.get("svc:" + serviceName);
    }

    public static boolean isPassthrough() {
        return CURRENT_MODE == MODE_PASSTHROUGH;
    }

    public static boolean isRecording() {
        return CURRENT_MODE == MODE_RECORD || CURRENT_MODE == MODE_RECORD_AND_STUB
                || CURRENT_MODE == MODE_RECORD_ALL;
    }

    /**
     * Infer a fallback stub port from the destination port when no route matches.
     * Used in RECORD_ALL mode to redirect unmatched traffic to Baafoo for recording.
     *
     * @param port destination port in the original connection attempt
     * @return stub port number (never -1)
     */
    public static int forceRedirectPort(int port) {
        // HTTP ports → HTTP stub port
        if (port == 80 || port == 443 || port == 8080 || port == 8443) {
            return HTTP_PORT;
        }
        // Kafka ports
        if (port == 9092 || port == 9093 || port == 9094) {
            return KAFKA_PORT;
        }
        // Pulsar ports
        if (port == 6650 || port == 6651) {
            return PULSAR_PORT;
        }
        // JMS (ActiveMQ) port
        if (port == 61616) {
            return JMS_PORT;
        }
        // Everything else → TCP stub port
        return TCP_PORT;
    }

    public static boolean isInternal(String host, int port) {
        // Recognize connections to the Baafoo server itself (control API + stub ports).
        // In Docker, SERVER_HOST may be a container name like "server" that resolves
        // to a container IP (e.g., 172.19.0.2). We check both the hostname and the
        // resolved IP to cover all cases.
        boolean isServerHost = "127.0.0.1".equals(host) || "localhost".equals(host)
                || host.equals(SERVER_HOST)
                || (SERVER_HOST_IP != null && host.equals(SERVER_HOST_IP));
        if (!isServerHost) return false;
        if (port == SERVER_PORT) return true;
        if (port == HTTP_PORT || port == TCP_PORT || port == KAFKA_PORT
                || port == PULSAR_PORT || port == JMS_PORT) return true;
        return false;
    }

    /**
     * Infer the high-level protocol name from a connection's target host:port.
     *
     * <p>Socket-level recording only sees raw TCP bytes, but a connection can be
     * mapped back to a protocol via the stub port it was redirected to:
     * <ul>
     *   <li>9000 → http</li>
     *   <li>9001 → tcp</li>
     *   <li>9002 → kafka</li>
     *   <li>9003 → pulsar</li>
     *   <li>9004 → jms</li>
     * </ul>
     * For connections to an internal stub port the port itself identifies the
     * protocol; for external connections the route table is consulted to find
     * the redirect target port (with a DNS-cache fallback for Docker/IP cases).
     * Returns {@code "tcp"} when no mapping is found.</p>
     */
    public static String inferProtocol(String host, int port) {
        // Internal connection to a stub port — the port identifies the protocol.
        if (isInternal(host, port)) {
            if (port == HTTP_PORT) return "http";
            if (port == TCP_PORT) return "tcp";
            if (port == KAFKA_PORT) return "kafka";
            if (port == PULSAR_PORT) return "pulsar";
            if (port == JMS_PORT) return "jms";
        }
        // External connection — look up the route to find the target stub port.
        String[] route = lookup(host, port);
        String protocol = protocolForRoute(route);
        if (protocol != null) return protocol;
        // DNS cache fallback: the host may be a resolved IP whose original domain
        // is the route key (common in Docker where the app connects to a container IP).
        if (route == null && !"127.0.0.1".equals(host) && !"localhost".equals(host)) {
            String originalDomain = DNS_CACHE.get(host);
            if (originalDomain != null) {
                protocol = protocolForRoute(lookup(originalDomain, port));
                if (protocol != null) return protocol;
            }
        }
        return "tcp";
    }

    /** Map a {@code lookup(...)} result's target port to a protocol name, or null. */
    private static String protocolForRoute(String[] route) {
        if (route == null) return null;
        int targetPort = Integer.parseInt(route[1]);
        if (targetPort == HTTP_PORT) return "http";
        if (targetPort == TCP_PORT) return "tcp";
        if (targetPort == KAFKA_PORT) return "kafka";
        if (targetPort == PULSAR_PORT) return "pulsar";
        if (targetPort == JMS_PORT) return "jms";
        return null;
    }

    public static void addRoute(String originalHost, int originalPort, String targetHost, int targetPort) {
        ROUTES.put(originalHost + ":" + originalPort, new HostPort(targetHost, targetPort));
    }

    public static void addService(String serviceName, String targetHost, int targetPort) {
        ROUTES.put("svc:" + serviceName, new HostPort(targetHost, targetPort));
    }

    public static void clearRoutes() {
        ROUTES.clear();
    }

    /**
     * Register a socket for recording. Called from SocketConnectAdvice in RECORD mode.
     * @param socketIdentity System.identityHashCode of the socket
     * @param sessionId unique session ID for this recording
     * @param host original target host
     * @param port original target port
     */
    public static void startRecording(int socketIdentity, String sessionId, String host, int port) {
        if (RECORDING_SESSIONS.size() >= MAX_RECORDING_SESSIONS) {
            RECORDING_SESSIONS.clear();
        }
        RECORDING_SESSIONS.put(socketIdentity, new String[]{sessionId, host, String.valueOf(port)});
    }

    /**
     * Remove a socket from recording tracking.
     * @param socketIdentity System.identityHashCode of the socket
     */
    public static void stopRecording(int socketIdentity) {
        RECORDING_SESSIONS.remove(socketIdentity);
    }

    /**
     * Check if a socket is being recorded.
     * @param socketIdentity System.identityHashCode of the socket
     * @return session info array or null
     */
    public static String[] getRecordingSession(int socketIdentity) {
        return RECORDING_SESSIONS.get(socketIdentity);
    }

    /**
     * Add NIO recording data (called from SocketChannelReadAdvice/SocketChannelWriteAdvice).
     * Delegates to the NIO_RECORDING_HANDLER bridge function set by the App CL.
     * @param sessionInfo {sessionId, host, portString}
     * @param direction "request" or "response"
     * @param hexData hex string of recorded bytes
     */
    public static void addNioRecording(String[] sessionInfo, String direction, String hexData) {
        java.util.function.Consumer<Object[]> handler = NIO_RECORDING_HANDLER;
        if (handler != null) {
            handler.accept(new Object[]{sessionInfo, direction, hexData});
        }
    }
}
