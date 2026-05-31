package com.baafoo.agent;

import java.util.concurrent.ConcurrentHashMap;

public final class GlobalRouteState {

    public static final class HostPort {
        public final String host;
        public final int port;

        public HostPort(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }

    public static final ConcurrentHashMap<String, HostPort> ROUTES = new ConcurrentHashMap<String, HostPort>();

    public static volatile int CURRENT_MODE = 0;

    public static final int MODE_STUB = 0;
    public static final int MODE_PASSTHROUGH = 1;
    public static final int MODE_RECORD = 2;
    public static final int MODE_RECORD_AND_STUB = 3;

    public static volatile String SERVER_HOST = "127.0.0.1";
    public static volatile int SERVER_PORT = 8080;

    private GlobalRouteState() {}

    public static String[] lookup(String host, int port) {
        HostPort target = ROUTES.get(host + ":" + port);
        if (target != null) {
            return new String[]{target.host, String.valueOf(target.port)};
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

    private static final java.util.Set<Integer> INTERNAL_PORTS = java.util.Collections.unmodifiableSet(
            new java.util.HashSet<Integer>(java.util.Arrays.asList(8080, 9000, 9001, 9002, 9003, 9004)));

    public static boolean isInternal(String host, int port) {
        if (!"127.0.0.1".equals(host) && !"localhost".equals(host)) {
            return false;
        }
        if (port == SERVER_PORT) return true;
        return INTERNAL_PORTS.contains(port);
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
