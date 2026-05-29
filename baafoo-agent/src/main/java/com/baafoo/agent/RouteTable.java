package com.baafoo.agent;

import java.util.concurrent.ConcurrentHashMap;

/**
 * Bootstrap-safe route table for Advice inlining.
 *
 * <p>This class contains ONLY types available to the Bootstrap ClassLoader
 * (String, ConcurrentHashMap, primitives). Byte Buddy Advice code is inlined
 * into target classes; any reference to non-Bootstrap classes (e.g., RouteManager,
 * Logger, Rule) causes ClassNotFoundException at runtime.</p>
 *
 * <p>Route key format: "{host}:{port}" or "{serviceName}" (for Consul DNS lookup).
 * Route value format: "{stubHost}:{stubPort}:{protocol}"</p>
 */
public final class RouteTable {

    /** Route map: key = "host:port" or "serviceName", value = "stubHost:stubPort:protocol" */
    private final ConcurrentHashMap<String, String> routes;

    /** Service name → host:port mapping for Consul DNS interception */
    private final ConcurrentHashMap<String, String> serviceNames;

    /** Version counter for change detection */
    private volatile long version;

    public RouteTable() {
        this.routes = new ConcurrentHashMap<String, String>();
        this.serviceNames = new ConcurrentHashMap<String, String>();
        this.version = 0L;
    }

    /**
     * Look up a route by host:port key.
     *
     * @param host target host
     * @param port target port
     * @return route value string "stubHost:stubPort:protocol", or null if not found
     */
    public String lookup(String host, int port) {
        if (host == null) {
            return null;
        }
        String key = host + ":" + port;
        return routes.get(key);
    }

    /**
     * Look up a route by service name (for Consul DNS).
     *
     * @param serviceName Consul service name
     * @return route value string, or null if not found
     */
    public String lookupService(String serviceName) {
        if (serviceName == null) {
            return null;
        }
        return serviceNames.get(serviceName);
    }

    /**
     * Parse stub host from route value string.
     *
     * @param routeValue "stubHost:stubPort:protocol"
     * @return stub host, or "127.0.0.1" as fallback
     */
    public static String parseHost(String routeValue) {
        if (routeValue == null) {
            return "127.0.0.1";
        }
        int idx = routeValue.indexOf(':');
        if (idx < 0) {
            return "127.0.0.1";
        }
        return routeValue.substring(0, idx);
    }

    /**
     * Parse stub port from route value string.
     *
     * @param routeValue "stubHost:stubPort:protocol"
     * @return stub port, or 9001 as fallback
     */
    public static int parsePort(String routeValue) {
        if (routeValue == null) {
            return 9001;
        }
        int firstColon = routeValue.indexOf(':');
        if (firstColon < 0) {
            return 9001;
        }
        int secondColon = routeValue.indexOf(':', firstColon + 1);
        if (secondColon < 0) {
            return 9001;
        }
        try {
            return Integer.parseInt(routeValue.substring(firstColon + 1, secondColon));
        } catch (NumberFormatException e) {
            return 9001;
        }
    }

    /**
     * Parse protocol from route value string.
     *
     * @param routeValue "stubHost:stubPort:protocol"
     * @return protocol, or "tcp" as fallback
     */
    public static String parseProtocol(String routeValue) {
        if (routeValue == null) {
            return "tcp";
        }
        int firstColon = routeValue.indexOf(':');
        if (firstColon < 0) {
            return "tcp";
        }
        int secondColon = routeValue.indexOf(':', firstColon + 1);
        if (secondColon < 0) {
            return "tcp";
        }
        return routeValue.substring(secondColon + 1);
    }

    /**
     * Put a route entry.
     *
     * @param host       target host
     * @param port       target port
     * @param stubHost   stub server host
     * @param stubPort   stub server port
     * @param protocol   protocol (http, tcp, kafka, pulsar, jms)
     */
    public void put(String host, int port, String stubHost, int stubPort, String protocol) {
        String key = host + ":" + port;
        String value = stubHost + ":" + stubPort + ":" + protocol;
        routes.put(key, value);
    }

    /**
     * Put a service name route entry (for Consul DNS).
     *
     * @param serviceName Consul service name
     * @param stubHost    stub server host
     * @param stubPort    stub server port
     * @param protocol    protocol
     */
    public void putService(String serviceName, String stubHost, int stubPort, String protocol) {
        String value = stubHost + ":" + stubPort + ":" + protocol;
        serviceNames.put(serviceName, value);
    }

    /**
     * Remove a route by host:port.
     *
     * @param host target host
     * @param port target port
     */
    public void remove(String host, int port) {
        if (host != null) {
            routes.remove(host + ":" + port);
        }
    }

    /**
     * Remove a service name route.
     *
     * @param serviceName Consul service name
     */
    public void removeService(String serviceName) {
        if (serviceName != null) {
            serviceNames.remove(serviceName);
        }
    }

    /**
     * Clear all routes.
     */
    public void clear() {
        routes.clear();
        serviceNames.clear();
    }

    /**
     * Get total route count.
     *
     * @return total number of routes (host:port + service name)
     */
    public int size() {
        return routes.size() + serviceNames.size();
    }

    /**
     * Get the routes map (for RouteManager to iterate).
     *
     * @return routes map
     */
    public ConcurrentHashMap<String, String> getRoutes() {
        return routes;
    }

    /**
     * Get the service names map (for RouteManager to iterate).
     *
     * @return service names map
     */
    public ConcurrentHashMap<String, String> getServiceNames() {
        return serviceNames;
    }

    /**
     * Get version counter.
     *
     * @return version
     */
    public long getVersion() {
        return version;
    }

    /**
     * Increment version counter.
     */
    public void incrementVersion() {
        this.version++;
    }
}
