package com.baafoo.agent;

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

    public static volatile String SERVER_HOST = "127.0.0.1";
    public static volatile int SERVER_PORT = 8084;

    // ---- Logging bridge ----
    // Set by the App CL side (BaafooAgent) with SLF4J-backed implementations.
    // Advice code inlined into Bootstrap CL classes calls logInfo/logWarn/logError,
    // which delegate to these handlers. Falls back to System.out when not set.

    /** @see #logInfo(String) */
    public static volatile Consumer<String> LOG_INFO_HANDLER;

    /** @see #logWarn(String) */
    public static volatile Consumer<String> LOG_WARN_HANDLER;

    /** @see #logError(String) */
    public static volatile Consumer<String> LOG_ERROR_HANDLER;

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

    /** Maximum number of entries in {@link #DNS_CACHE} */
    private static final int MAX_DNS_CACHE_SIZE = 10000;

    private static final java.util.concurrent.atomic.AtomicBoolean DNS_EVICTION_IN_PROGRESS =
            new java.util.concurrent.atomic.AtomicBoolean(false);

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

    public static HostPort lookupService(String serviceName) {
        if (serviceName == null) return null;
        return ROUTES.get("svc:" + serviceName);
    }

    public static boolean isPassthrough() {
        return CURRENT_MODE == MODE_PASSTHROUGH;
    }

    public static boolean isRecording() {
        return CURRENT_MODE == MODE_RECORD || CURRENT_MODE == MODE_RECORD_AND_STUB;
    }

    public static boolean isInternal(String host, int port) {
        if (!"127.0.0.1".equals(host) && !"localhost".equals(host)) {
            return false;
        }
        if (port == SERVER_PORT) return true;
        return port == 8084 || port == 9000 || port == 9001 || port == 9002 || port == 9003 || port == 9004;
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
}
