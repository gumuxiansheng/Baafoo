package com.baafoo.agent;

import java.util.concurrent.ConcurrentHashMap;

/**
 * @deprecated Informational only, not used by advice. ByteBuddy-inlined advice
 * reads {@link GlobalRouteState#ROUTES} directly (the Bootstrap-CL copy); this
 * App-CL-only {@code RouteTable} is kept as a snapshot for monitoring/testing
 * and is updated atomically by {@code RouteManager.rebuildRouteTable} alongside
 * the GlobalRouteState swap. Will be removed in a future version once
 * monitoring is migrated to read GlobalRouteState.ROUTES directly.
 */
@Deprecated
public final class RouteTable {

    private final ConcurrentHashMap<String, GlobalRouteState.HostPort> routes;

    private final ConcurrentHashMap<String, GlobalRouteState.HostPort> serviceNames;

    private volatile long version;

    public RouteTable() {
        this.routes = new ConcurrentHashMap<String, GlobalRouteState.HostPort>();
        this.serviceNames = new ConcurrentHashMap<String, GlobalRouteState.HostPort>();
        this.version = 0L;
    }

    public GlobalRouteState.HostPort lookup(String host, int port) {
        if (host == null) {
            return null;
        }
        String key = host + ":" + port;
        GlobalRouteState.HostPort result = routes.get(key);
        if (result != null) return result;
        return routes.get(host);
    }

    public GlobalRouteState.HostPort lookupService(String serviceName) {
        if (serviceName == null) {
            return null;
        }
        return serviceNames.get(serviceName);
    }

    public void put(String host, int port, String stubHost, int stubPort) {
        String key = host + ":" + port;
        routes.put(key, new GlobalRouteState.HostPort(stubHost, stubPort));
    }

    public void putService(String serviceName, String stubHost, int stubPort) {
        serviceNames.put(serviceName, new GlobalRouteState.HostPort(stubHost, stubPort));
    }

    public void remove(String host, int port) {
        if (host != null) {
            routes.remove(host + ":" + port);
        }
    }

    public void removeService(String serviceName) {
        if (serviceName != null) {
            serviceNames.remove(serviceName);
        }
    }

    public void clear() {
        routes.clear();
        serviceNames.clear();
    }

    public int size() {
        return routes.size() + serviceNames.size();
    }

    public ConcurrentHashMap<String, GlobalRouteState.HostPort> getRoutes() {
        return routes;
    }

    public ConcurrentHashMap<String, GlobalRouteState.HostPort> getServiceNames() {
        return serviceNames;
    }

    public long getVersion() {
        return version;
    }

    public void incrementVersion() {
        this.version++;
    }
}
