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

    public static boolean isInternal(String host, int port) {
        if ("127.0.0.1".equals(host) || "localhost".equals(host)) {
            if (port == SERVER_PORT) return true;
            if (port == 8080 || port == 9000 || port == 9001 || port == 9002
                    || port == 9003 || port == 9004) return true;
        }
        return false;
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
