package com.baafoo.agent.advice;

import com.baafoo.agent.GlobalRouteState;
import net.bytebuddy.asm.Advice;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * Intercepts Socket.connect() to reroute connections to the stub server.
 *
 * <p><b>CRITICAL</b>: This advice is inlined into java.net.Socket by ByteBuddy.
 * Since Socket is loaded by the Bootstrap ClassLoader, the inlined code
 * runs in the Bootstrap CL context. Therefore, this class MUST ONLY reference
 * classes visible to the Bootstrap CL (GlobalRouteState is added via
 * appendToBootstrapClassLoaderSearch). Do NOT reference any AppClassLoader
 * class here — it will cause NoClassDefFoundError that is silently caught,
 * making the interception completely fail with no visible error.</p>
 *
 * <p>The route lookup logic (lookup → DNS fallback → redirect) is duplicated
 * in {@link NioSocketConnectAdvice} because ByteBuddy inlines advice code
 * into the target class and cannot delegate to a shared helper that lives
 * in the AppClassLoader. Any changes here MUST be mirrored there.</p>
 *
 * <p><b>Record mode</b>: When CURRENT_MODE is RECORD (2) or RECORD_AND_STUB (3),
 * the connection is allowed to proceed to the real target (no redirect).
 * The socket is registered in GlobalRouteState.RECORDING_SESSIONS so that
 * SocketGetStreamAdvice can wrap its streams with recording wrappers.</p>
 */
public final class SocketConnectAdvice {

    private SocketConnectAdvice() {}

    @Advice.OnMethodEnter
    public static void onConnect(@Advice.Argument(value = 0, readOnly = false) SocketAddress endpoint,
                                 @Advice.This Object socket) {
        try {
            if (!(endpoint instanceof InetSocketAddress)) {
                return;
            }

            InetSocketAddress addr = (InetSocketAddress) endpoint;
            String host = addr.getHostString();
            int port = addr.getPort();

            // Skip internal connections (Baafoo server & stub ports)
            if (GlobalRouteState.isInternal(host, port)) {
                return;
            }

            // Check passthrough mode (1=PASSTHROUGH)
            if (GlobalRouteState.CURRENT_MODE == 1) {
                return;
            }

            // Record mode (2=RECORD, 3=RECORD_AND_STUB): allow real connection,
            // register socket for stream recording
            if (GlobalRouteState.CURRENT_MODE == 2 || GlobalRouteState.CURRENT_MODE == 3) {
                String sessionId = java.util.UUID.randomUUID().toString();
                GlobalRouteState.startRecording(System.identityHashCode(socket), sessionId, host, port);
                GlobalRouteState.logInfo("[Baafoo] Socket recording: " + host + ":" + port + " (sessionId=" + sessionId + ")");

                // In RECORD_AND_STUB mode, also redirect to stub (connection is recorded
                // via the stub's passthrough proxy, not here)
                if (GlobalRouteState.CURRENT_MODE == 3) {
                    String[] routeValue = GlobalRouteState.lookup(host, port);
                    if (routeValue == null && !"127.0.0.1".equals(host) && !"localhost".equals(host)) {
                        String originalDomain = (String) GlobalRouteState.DNS_CACHE.get(host);
                        if (originalDomain != null) {
                            routeValue = GlobalRouteState.lookup(originalDomain, port);
                        }
                    }
                    if (routeValue != null) {
                        GlobalRouteState.logInfo("[Baafoo] Socket redirect (record-and-stub): " + host + ":" + port + " -> " + routeValue[0] + ":" + routeValue[1]);
                        endpoint = new InetSocketAddress(routeValue[0], Integer.parseInt(routeValue[1]));
                    }
                }
                // In pure RECORD mode, don't redirect — connection goes to real target
                return;
            }

            // STUB mode: redirect to stub server
            String[] routeValue = GlobalRouteState.lookup(host, port);

            // DNS cache fallback
            if (routeValue == null && !"127.0.0.1".equals(host) && !"localhost".equals(host)) {
                String originalDomain = (String) GlobalRouteState.DNS_CACHE.get(host);
                if (originalDomain != null) {
                    routeValue = GlobalRouteState.lookup(originalDomain, port);
                }
            }

            if (routeValue != null) {
                GlobalRouteState.logInfo("[Baafoo] Socket redirect: " + host + ":" + port + " -> " + routeValue[0] + ":" + routeValue[1]);
                endpoint = new InetSocketAddress(routeValue[0], Integer.parseInt(routeValue[1]));
            }
        } catch (Throwable t) {
            GlobalRouteState.logError("[Baafoo] SocketConnectAdvice error: " + t);
        }
    }
}
