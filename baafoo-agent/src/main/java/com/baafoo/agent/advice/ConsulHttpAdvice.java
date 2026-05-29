package com.baafoo.agent.advice;

import com.baafoo.agent.AgentManifest;
import com.baafoo.agent.RouteTable;
import net.bytebuddy.asm.Advice;

/**
 * Byte Buddy Advice for sun.net.www.http.HttpClient#openServer(String, int).
 *
 * <p>Intercepts HTTP connections to Consul API server and rewrites responses
 * when the target matches a service name in the routing table. This complements
 * ConsulDnsAdvice by also intercepting the HTTP API path (typically
 * GET /v1/health/service/:name or GET /v1/kv/:key).</p>
 *
 * <p><b>Bootstrap ClassLoader safe</b>: Only references AgentManifest,
 * RouteTable, and java.* types.</p>
 *
 * <p>Logic:
 * <pre>
 *   if AgentManifest.isPassthrough(): return (skip)
 *   routeValue = AgentManifest.ROUTE_TABLE.get().lookupService(host)
 *   if routeValue != null: rewrite host:port to stub server
 * </pre></p>
 */
public class ConsulHttpAdvice {

    @Advice.OnMethodEnter
    public static void onOpenServer(
            @Advice.Argument(value = 0, readOnly = false) String server,
            @Advice.Argument(value = 1, readOnly = false) int port) {

        // Fast path: passthrough mode → skip interception
        if (AgentManifest.isPassthrough()) {
            return;
        }

        try {
            if (server == null || server.isEmpty()) {
                return;
            }

            // Check if this server matches a Consul service name in the routing table
            RouteTable table = AgentManifest.ROUTE_TABLE.get();
            String routeValue = table.lookupService(server);

            if (routeValue != null) {
                // Service matched → rewrite to Baafoo stub server
                String stubHost = RouteTable.parseHost(routeValue);
                int stubPort = RouteTable.parsePort(routeValue);
                server = stubHost;
                port = stubPort;
            }
        } catch (Throwable t) {
            // Fail-closed: let the original connection proceed
        }
    }
}
