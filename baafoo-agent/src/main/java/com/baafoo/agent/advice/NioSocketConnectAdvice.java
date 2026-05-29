package com.baafoo.agent.advice;

import com.baafoo.agent.AgentManifest;
import com.baafoo.agent.RouteTable;
import net.bytebuddy.asm.Advice;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * Byte Buddy Advice for sun.nio.ch.SocketChannelImpl#connect(SocketAddress).
 *
 * <p>Same logic as SocketConnectAdvice but for NIO channels.
 * The NIO SocketChannelImpl is an internal JDK class that may not be
 * accessible in newer JDK versions, but for Java 8 it works.</p>
 *
 * <p><b>Bootstrap ClassLoader safe</b>: Same constraints as SocketConnectAdvice.
 * Only AgentManifest, RouteTable, and java.* types are referenced.</p>
 */
public class NioSocketConnectAdvice {

    @Advice.OnMethodEnter
    public static void onConnect(@Advice.Argument(value = 0, readOnly = false) SocketAddress remote) {

        // Fast path: passthrough mode → skip all interception
        if (AgentManifest.isPassthrough()) {
            return;
        }

        try {
            if (!(remote instanceof InetSocketAddress)) {
                return;
            }

            InetSocketAddress addr = (InetSocketAddress) remote;
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
                remote = stubAddr;
            } else {
                // No route matched
                if (AgentManifest.isStubMode()) {
                    // Safety: unmatched in stub mode = forbid connection
                    throw new RuntimeException(
                            "Baafoo: No rule matched for " + host + ":" + port
                                    + " (stub mode — NIO connection blocked)");
                }
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            // Fail-closed: swallow error, let the original connection proceed
        }
    }
}
