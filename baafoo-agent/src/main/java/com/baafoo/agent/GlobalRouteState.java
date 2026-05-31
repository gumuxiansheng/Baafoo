package com.baafoo.agent;

import java.util.concurrent.ConcurrentHashMap;

public final class GlobalRouteState {

    public static final ConcurrentHashMap<String, String> ROUTES = new ConcurrentHashMap<String, String>();

    public static volatile int CURRENT_MODE = 0;

    public static final int MODE_STUB = 0;
    public static final int MODE_PASSTHROUGH = 1;
    public static final int MODE_RECORD = 2;
    public static final int MODE_RECORD_AND_STUB = 3;

    public static volatile String SERVER_HOST = "127.0.0.1";
    public static volatile int SERVER_PORT = 8080;

    private GlobalRouteState() {}

    public static String lookup(String host, int port) {
        if (host == null) return null;
        String key = host + ":" + port;
        String result = ROUTES.get(key);
        if (result != null) return result;
        return ROUTES.get(host);
    }

    public static String lookupService(String serviceName) {
        if (serviceName == null) return null;
        return ROUTES.get("svc:" + serviceName);
    }

    public static boolean isPassthrough() {
        return CURRENT_MODE == MODE_PASSTHROUGH;
    }

    public static boolean isRecording() {
        return CURRENT_MODE == MODE_RECORD || CURRENT_MODE == MODE_RECORD_AND_STUB;
    }

    public static String parseHost(String routeValue) {
        if (routeValue == null) return "127.0.0.1";
        int idx = routeValue.indexOf(':');
        if (idx < 0) return "127.0.0.1";
        return routeValue.substring(0, idx);
    }

    public static int parsePort(String routeValue) {
        if (routeValue == null) return 9001;
        int firstColon = routeValue.indexOf(':');
        if (firstColon < 0) return 9001;
        int secondColon = routeValue.indexOf(':', firstColon + 1);
        if (secondColon < 0) return 9001;
        try {
            return Integer.parseInt(routeValue.substring(firstColon + 1, secondColon));
        } catch (NumberFormatException e) {
            return 9001;
        }
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

    public static void putRoute(String host, int port, String stubHost, int stubPort, String protocol) {
        String key = host + ":" + port;
        String value = stubHost + ":" + stubPort + ":" + protocol;
        ROUTES.put(key, value);
    }

    public static void putService(String serviceName, String stubHost, int stubPort, String protocol) {
        String key = "svc:" + serviceName;
        String value = stubHost + ":" + stubPort + ":" + protocol;
        ROUTES.put(key, value);
    }

    public static void clearRoutes() {
        ROUTES.clear();
    }
}
