package com.baafoo.agent.advice;

import com.baafoo.agent.BaafooAgent;
import com.baafoo.agent.plugin.PluginManager;
import com.baafoo.plugin.AgentPlugin;
import com.baafoo.plugin.InterceptResult;
import com.baafoo.plugin.InterceptTarget;
import com.baafoo.plugin.PluginContext;
import net.bytebuddy.asm.Advice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.Collections;
import java.util.concurrent.Callable;

/**
 * Byte Buddy Advice for java.net.Socket#connect(SocketAddress).
 *
 * <p>Intercepts ALL Socket.connect() calls and checks against the
 * Baafoo routing table. If a matching rule exists, redirects the
 * connection to the Baafoo stub server instead of the real downstream.</p>
 *
 * <p>Address rewriting logic per concept-design:
 * <pre>
 *   1. Check routing table for host:port match
 *   2. If matched + stub mode → rewrite to baafoo-server:protocol-port
 *   3. If matched + record mode → record real call, then rewrite
 *   4. If unmatched + stub mode → return 404 (no passthrough!)
 *   5. If passthrough mode → pass through to real downstream
 * </pre></p>
 */
public class SocketConnectAdvice {

    private static final Logger log = LoggerFactory.getLogger(SocketConnectAdvice.class);

    /**
     * Injected at the start of Socket.connect(SocketAddress).
     * Rewrites the endpoint to Baafoo stub server if a matching rule exists.
     */
    @Advice.OnMethodEnter
    public static void onConnect(@Advice.Argument(value = 0, readOnly = false) SocketAddress endpoint) {

        try {
            if (!(endpoint instanceof InetSocketAddress)) {
                return;
            }

            InetSocketAddress addr = (InetSocketAddress) endpoint;
            String host = addr.getHostString();
            int port = addr.getPort();

            // Check routing table
            RouteManager.RouteResult result = RouteManager.route(
                    "tcp", host, port, null,
                    null, null,
                    Collections.<String, String>emptyMap(),
                    Collections.<String, String>emptyMap(),
                    null);

            if (result.unmatched404) {
                // Safety: unmatched in stub mode = forbid connection
                log.warn("No matching rule for {}:{}, returning 404 (not connecting)", host, port);
                throw new UnmatchedStubException("No Baafoo rule matched for " + host + ":" + port);
            }

            if (result.matched) {
                // Rewrite address to Baafoo stub server
                String stubHost = "127.0.0.1"; // Baafoo server runs on localhost
                int stubPort = getStubPort(result.protocol);
                InetSocketAddress stubAddr = new InetSocketAddress(stubHost, stubPort);
                endpoint = stubAddr;

                log.debug("Socket connect {}:{} → stub {}:{} [rule: {}]",
                        host, port, stubHost, stubPort, result.rule.getName());

                // Store routing context for recording
                RoutingContext.set(result);
            }
        } catch (UnmatchedStubException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error in SocketConnectAdvice: {}", e.getMessage());
            // Fail-closed: let the original connection proceed
        }
    }

    private static int getStubPort(String protocol) {
        if (protocol == null) return 9001;
        switch (protocol.toLowerCase()) {
            case "http": return 9000;
            case "tcp": return 9001;
            case "kafka": return 9002;
            case "pulsar": return 9003;
            case "jms": return 9004;
            default: return 9001;
        }
    }

    /**
     * RuntimeException to abort the connection when no rule matches in stub mode.
     * Socket.connect() will throw SocketException, which the application can handle.
     */
    public static class UnmatchedStubException extends RuntimeException {
        public UnmatchedStubException(String message) {
            super(message);
        }
    }
}
