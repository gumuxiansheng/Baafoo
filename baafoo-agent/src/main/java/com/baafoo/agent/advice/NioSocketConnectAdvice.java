package com.baafoo.agent.advice;

import com.baafoo.agent.GlobalRouteState;
import net.bytebuddy.asm.Advice;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * Intercepts SocketChannel.connect() to reroute NIO connections to the stub server.
 *
 * <p><b>CRITICAL</b>: This advice is inlined into java.nio.channels.SocketChannel
 * by ByteBuddy. Since SocketChannel is loaded by the Bootstrap ClassLoader, the
 * inlined code runs in the Bootstrap CL context. This class MUST ONLY reference
 * classes visible to the Bootstrap CL (GlobalRouteState is added via
 * appendToBootstrapClassLoaderSearch). Do NOT reference any AppClassLoader
 * class here — it will cause NoClassDefFoundError that is silently caught,
 * making the interception completely fail with no visible error.</p>
 *
 * <p>Do NOT call methods like isPassthrough() or isInternal() — they reference
 * fields (INTERNAL_PORTS, SERVER_PORT) that may not be resolvable from the
 * Bootstrap CL. Use direct field access instead.</p>
 */
public final class NioSocketConnectAdvice {

    private NioSocketConnectAdvice() {}

    @Advice.OnMethodEnter
    public static void onConnect(@Advice.Argument(value = 0, readOnly = false) SocketAddress remote) {
        try {
            if (!(remote instanceof InetSocketAddress)) {
                return;
            }

            InetSocketAddress addr = (InetSocketAddress) remote;
            String host = addr.getHostString();
            int port = addr.getPort();

            // Skip internal connections (Baafoo server & stub ports)
            if ("127.0.0.1".equals(host) || "localhost".equals(host)) {
                if (port == 8084 || port == 9000 || port == 9001 || port == 9002 || port == 9003 || port == 9004) {
                    return;
                }
            }

            // Check passthrough mode (1=PASSTHROUGH)
            if (GlobalRouteState.CURRENT_MODE == 1) {
                return;
            }

            String[] routeValue = GlobalRouteState.lookup(host, port);

            // DNS cache fallback
            if (routeValue == null && !"127.0.0.1".equals(host) && !"localhost".equals(host)) {
                String originalDomain = (String) GlobalRouteState.DNS_CACHE.get(host);
                if (originalDomain != null) {
                    routeValue = GlobalRouteState.lookup(originalDomain, port);
                }
            }

            if (routeValue != null) {
                java.lang.System.out.println("[Baafoo] NIO Socket redirect: " + host + ":" + port + " -> " + routeValue[0] + ":" + routeValue[1]);
                remote = new InetSocketAddress(routeValue[0], Integer.parseInt(routeValue[1]));
            }
        } catch (Throwable t) {
            java.lang.System.out.println("[Baafoo] NioSocketConnectAdvice error: " + t);
        }
    }
}
