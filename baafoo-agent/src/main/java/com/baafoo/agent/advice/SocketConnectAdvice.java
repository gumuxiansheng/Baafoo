package com.baafoo.agent.advice;

import com.baafoo.agent.AgentManifest;
import com.baafoo.agent.RouteTable;
import net.bytebuddy.asm.Advice;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * Byte Buddy Advice for java.net.Socket#connect(SocketAddress).
 *
 * <p>Intercepts ALL Socket.connect() calls and checks against the
 * Baafoo routing table. If a matching rule exists, redirects the
 * connection to the Baafoo stub server instead of the real downstream.</p>
 *
 * <p><b>Bootstrap ClassLoader safe</b>: This class is inlined into the
 * target class's ClassLoader. It MUST NOT reference any class that is
 * not loadable by the Bootstrap ClassLoader (no Logger, no RouteManager,
 * no Rule, no custom exceptions). Only AgentManifest + RouteTable + java.*
 * are safe.</p>
 *
 * <p>Address rewriting logic per concept-design:
 * <pre>
 *   1. Check AgentManifest.ROUTE_TABLE for host:port match
 *   2. If matched + stub mode → rewrite to baafoo-server:protocol-port
 *   3. If matched + record mode → record real call, then rewrite
 *   4. If unmatched + stub mode → throw java.io.IOException (no passthrough!)
 *   5. If passthrough mode → pass through to real downstream
 * </pre></p>
 */
public class SocketConnectAdvice {

    /**
     * Injected at the start of Socket.connect(SocketAddress).
     * Rewrites the endpoint to Baafoo stub server if a matching rule exists.
     */
    @Advice.OnMethodEnter
    public static void onConnect(@Advice.Argument(value = 0, readOnly = false) SocketAddress endpoint) {

        // Fast path: passthrough mode → skip all interception
        if (AgentManifest.isPassthrough()) {
            return;
        }

        try {
            if (!(endpoint instanceof InetSocketAddress)) {
                return;
            }

            InetSocketAddress addr = (InetSocketAddress) endpoint;
            String host = addr.getHostString();
            int port = addr.getPort();

            // Look up route in the atomic route table
            RouteTable table = AgentManifest.ROUTE_TABLE.get();
            String routeValue = table.lookup(host, port);

            if (routeValue != null) {
                // Route matched → rewrite address to Baafoo stub server
                String stubHost = RouteTable.parseHost(routeValue);
                int stubPort = RouteTable.parsePort(routeValue);
                InetSocketAddress stubAddr = new InetSocketAddress(stubHost, stubPort);
                endpoint = stubAddr;
            } else {
                // No route matched
                if (AgentManifest.isStubMode()) {
                    // Safety: unmatched in stub mode = forbid connection
                    // Use RuntimeException — Bootstrap CL safe, no checked exception issues
                    throw new RuntimeException(
                            "Baafoo: No rule matched for " + host + ":" + port
                                    + " (stub mode — connection blocked)");
                }
                // In record-only mode with no match: let original connection proceed
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            // Fail-closed: swallow error, let the original connection proceed
        }
    }
}
