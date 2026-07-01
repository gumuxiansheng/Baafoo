package com.baafoo.agent.state;

import com.baafoo.agent.GlobalRouteState;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * P1-2: Route table manager.
 *
 * <p>Encapsulates the route-lookup and route-mutation logic previously inlined
 * in {@link GlobalRouteState}. The actual {@code ROUTES} map field stays on
 * {@code GlobalRouteState} for Bootstrap-CL compatibility (ByteBuddy-inlined
 * advice references it by name); this class provides typed accessors and
 * lookup helpers that operate on that map.</p>
 *
 * <p>The {@code HostPort} inner class stays in {@code GlobalRouteState} (it is
 * packaged into the Bootstrap JAR). This class references it via
 * {@code GlobalRouteState.HostPort}.</p>
 */
public final class RouteTable {

    /**
     * Look up a route by host and port.
     *
     * @return {@code {targetHost, targetPortString}} or {@code null} if no match.
     */
    public String[] lookup(String host, int port) {
        if (host == null) {
            return null;
        }
        ConcurrentHashMap<String, GlobalRouteState.HostPort> routes = GlobalRouteState.ROUTES;
        // First try exact host:port match
        String key = host + ":" + port;
        GlobalRouteState.HostPort target = routes.get(key);
        if (target != null) {
            return new String[]{target.host, String.valueOf(target.port)};
        }
        // Fallback: try host-only match (for rules without specific port)
        GlobalRouteState.HostPort hostOnly = routes.get(host);
        if (hostOnly != null) {
            return new String[]{hostOnly.host, String.valueOf(hostOnly.port)};
        }
        return null;
    }

    /** Look up the first route whose key matches the given host (with or without port). */
    public GlobalRouteState.HostPort lookupByHost(String host) {
        if (host == null) return null;
        ConcurrentHashMap<String, GlobalRouteState.HostPort> routes = GlobalRouteState.ROUTES;
        // Check host-only entries first
        GlobalRouteState.HostPort hostOnly = routes.get(host);
        if (hostOnly != null) return hostOnly;
        // Check host:port entries
        String prefix = host + ":";
        for (Map.Entry<String, GlobalRouteState.HostPort> entry : routes.entrySet()) {
            if (entry.getKey().startsWith(prefix)) {
                return entry.getValue();
            }
        }
        return null;
    }

    /** Look up a service route by its registered service name. */
    public GlobalRouteState.HostPort lookupService(String serviceName) {
        if (serviceName == null) return null;
        return GlobalRouteState.ROUTES.get("svc:" + serviceName);
    }

    /** Register a route from {@code originalHost:originalPort} to {@code targetHost:targetPort}. */
    public void addRoute(String originalHost, int originalPort, String targetHost, int targetPort) {
        GlobalRouteState.ROUTES.put(originalHost + ":" + originalPort,
                new GlobalRouteState.HostPort(targetHost, targetPort));
    }

    /** Register a service route keyed by {@code "svc:" + serviceName}. */
    public void addService(String serviceName, String targetHost, int targetPort) {
        GlobalRouteState.ROUTES.put("svc:" + serviceName,
                new GlobalRouteState.HostPort(targetHost, targetPort));
    }

    /** @return the live ROUTES map (mutations are visible to all readers). */
    public ConcurrentHashMap<String, GlobalRouteState.HostPort> getRoutes() {
        return GlobalRouteState.ROUTES;
    }

    /** Atomically swap the ROUTES map reference (used by RouteManager.rebuildRouteTable). */
    public void setRoutes(ConcurrentHashMap<String, GlobalRouteState.HostPort> newRoutes) {
        GlobalRouteState.ROUTES = newRoutes;
    }

    /** Remove all entries from the ROUTES map. */
    public void clear() {
        GlobalRouteState.ROUTES.clear();
    }
}
