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
 * <p>The route lookup logic is intentionally duplicated from
 * {@link SocketConnectAdvice} because ByteBuddy inlines advice code and
 * cannot delegate to a shared helper in the AppClassLoader.
 * Any changes here MUST be mirrored in SocketConnectAdvice.</p>
 */
public final class NioSocketConnectAdvice {

    private NioSocketConnectAdvice() {}

    @Advice.OnMethodEnter
    public static void onConnect(@Advice.Argument(value = 0, readOnly = false) SocketAddress remote) {
        try {
            if (GlobalRouteState.isPassthrough()) {
                return;
            }

            if (!(remote instanceof InetSocketAddress)) {
                return;
            }

            InetSocketAddress addr = (InetSocketAddress) remote;
            String host = addr.getHostString();
            int port = addr.getPort();

            if (GlobalRouteState.isInternal(host, port)) {
                return;
            }

            String[] routeValue = GlobalRouteState.lookup(host, port);

            // DNS cache fallback: if the socket connects using a resolved IP
            // but the rule was configured with a domain name
            if (routeValue == null && !"127.0.0.1".equals(host) && !"localhost".equals(host)) {
                String originalDomain = (String) GlobalRouteState.DNS_CACHE.get(host);
                if (originalDomain != null) {
                    routeValue = GlobalRouteState.lookup(originalDomain, port);
                }
            }

            if (routeValue != null) {
                remote = new InetSocketAddress(routeValue[0], Integer.parseInt(routeValue[1]));
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
            // Fail-open: let the original connection proceed
        }
    }
}
