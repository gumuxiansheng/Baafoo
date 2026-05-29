package com.baafoo.agent.advice;

import com.baafoo.agent.AgentManifest;
import com.baafoo.agent.RouteTable;
import net.bytebuddy.asm.Advice;

import java.net.InetAddress;

/**
 * Byte Buddy Advice for {@code java.net.InetAddress#getByName(String)}
 * and {@code java.net.InetAddress#getAllByName(String)}.
 *
 * <p>Intercepts DNS resolution to redirect Consul-registered service names
 * to Baafoo stub server IPs. This is the Consul DNS interception mode.</p>
 *
 * <p><b>Bootstrap ClassLoader safe</b>: Only references AgentManifest,
 * RouteTable, and java.* types. No Logger, no RouteManager, no Rule.</p>
 *
 * <p>Logic:
 * <pre>
 *   String host = getByName / getAllByName argument
 *   if AgentManifest.isPassthrough(): return (skip)
 *   routeValue = AgentManifest.ROUTE_TABLE.get().lookupService(host)
 *   if routeValue != null: replace result with stub host InetAddress
 * </pre></p>
 */
public class ConsulDnsAdvice {

    /**
     * Intercept {@code InetAddress.getByName(String)}.
     * If the host matches a Baafoo service name, replace the returned InetAddress.
     */
    @Advice.OnMethodExit
    public static void onGetByName(
            @Advice.Argument(0) String host,
            @Advice.Return(readOnly = false) InetAddress result) {

        // Fast path: passthrough mode → skip all interception
        if (AgentManifest.isPassthrough()) {
            return;
        }

        try {
            if (host == null || host.isEmpty()) {
                return;
            }

            // Check if host matches a service name in the Bootstrap-safe routing table
            RouteTable table = AgentManifest.ROUTE_TABLE.get();
            String routeValue = table.lookupService(host);

            if (routeValue != null) {
                // Service matched → replace DNS result with stub host
                String stubHost = RouteTable.parseHost(routeValue);
                result = InetAddress.getByName(stubHost);
            }
            // No match: let original DNS resolution stand (fail-closed)
        } catch (Throwable t) {
            // Fail-closed: let original resolution stand
        }
    }

    /**
     * Intercept {@code InetAddress.getAllByName(String)}.
     * If the host matches a Baafoo service name, replace the returned InetAddress array.
     */
    @Advice.OnMethodExit
    public static void onGetAllByName(
            @Advice.Argument(0) String host,
            @Advice.Return(readOnly = false) InetAddress[] result) {

        // Fast path: passthrough mode → skip all interception
        if (AgentManifest.isPassthrough()) {
            return;
        }

        try {
            if (host == null || host.isEmpty()) {
                return;
            }

            // Check if host matches a service name in the Bootstrap-safe routing table
            RouteTable table = AgentManifest.ROUTE_TABLE.get();
            String routeValue = table.lookupService(host);

            if (routeValue != null) {
                // Service matched → replace DNS result with stub host
                String stubHost = RouteTable.parseHost(routeValue);
                InetAddress stubAddr = InetAddress.getByName(stubHost);
                result = new InetAddress[]{stubAddr};
            }
            // No match: let original DNS resolution stand (fail-closed)
        } catch (Throwable t) {
            // Fail-closed: let original resolution stand
        }
    }
}
