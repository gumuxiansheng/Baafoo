package com.baafoo.agent.advice;

import com.baafoo.agent.GlobalRouteState;
import net.bytebuddy.asm.Advice;

/**
 * Intercepts sun.net.www.http.HttpClient.openServer to redirect HTTP traffic
 * targeting a service-name (or hostname) that matches a Baafoo rule.
 *
 * <p>Always mounted by {@code BaafooAgent.installTransforms} (no static config
 * needed). Behavior is controlled by the runtime route table.</p>
 *
 * <p>Strategy: only modify the <b>port</b>, keep the original <b>server</b>
 * (hostname). This preserves the original Host HTTP header so the Baafoo
 * Server's MatchEngine can match the rule using the host field. The DNS
 * resolution of the original hostname is handled separately by
 * {@link DnsResolveAdvice} which overrides the resolved InetAddress
 * to point at the Baafoo Server when the hostname matches a rule.</p>
 *
 * <p>Lookup order: try host:port exact match first (via {@code lookup}),
 * then fall back to serviceName lookup (via {@code lookupService}). This
 * supports both host-based and serviceName-based rules.</p>
 */
public class HttpOpenServerAdvice {

    @Advice.OnMethodEnter
    public static void onOpenServer(
            @Advice.Argument(value = 0, readOnly = false) String server,
            @Advice.Argument(value = 1, readOnly = false) int port) {

        if (GlobalRouteState.isPassthrough()) {
            return;
        }

        try {
            if (server == null || server.isEmpty()) {
                return;
            }

            // Try host:port exact match first (supports host-based rules).
            // GlobalRouteState.lookup returns String[]{targetHost, targetPortStr}
            // or null if no match.
            String[] route = GlobalRouteState.lookup(server, port);
            String targetHost;
            int targetPort;
            if (route != null) {
                targetHost = route[0];
                targetPort = Integer.parseInt(route[1]);
            } else {
                // Fall back to serviceName lookup (supports serviceName-based rules).
                // GlobalRouteState.lookupService returns HostPort or null.
                GlobalRouteState.HostPort svcTarget = GlobalRouteState.lookupService(server);
                if (svcTarget == null) {
                    return;
                }
                targetHost = svcTarget.host;
                targetPort = svcTarget.port;
            }

            // IMPORTANT: only modify port, keep original server (hostname).
            // This preserves the Host HTTP header for server-side rule matching.
            // The actual IP redirect happens in DnsResolveAdvice.
            GlobalRouteState.logInfo("[Baafoo] HttpOpenServerAdvice redirect: " + server + ":" + port + " -> " + server + ":" + targetPort + " (DNS will resolve to " + targetHost + ")");
            port = targetPort;
        } catch (Throwable t) {
            GlobalRouteState.logInfo("[Baafoo] HttpOpenServerAdvice error: " + t.getMessage());
        }
    }
}
