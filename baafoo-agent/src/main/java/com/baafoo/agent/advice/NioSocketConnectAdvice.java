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
 * <p>Do NOT call methods from App CL classes — only GlobalRouteState (which is
 * on the Bootstrap CL) is accessible. Use GlobalRouteState.isInternal() for
 * internal port checks instead of hardcoded port lists.</p>
 *
 * <p><b>Record mode</b>: When CURRENT_MODE is RECORD (2), the connection is
 * allowed to proceed to the real target (no redirect). The channel is registered
 * in GlobalRouteState.RECORDING_SESSIONS for tracking. Note: NIO byte recording
 * requires intercepting SocketChannel.read()/write() which is not yet implemented
 * in v1 — the session tracking is registered for future use.</p>
 */
public final class NioSocketConnectAdvice {

    private NioSocketConnectAdvice() {}

    @Advice.OnMethodEnter
    public static void onConnect(@Advice.Argument(value = 0, readOnly = false) SocketAddress remote,
                                 @Advice.This Object channel) {
        try {
            if (!(remote instanceof InetSocketAddress)) {
                return;
            }

            InetSocketAddress addr = (InetSocketAddress) remote;
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

            // Record mode (2=RECORD): allow real connection, register for tracking
            if (GlobalRouteState.CURRENT_MODE == 2) {
                String sessionId = java.util.UUID.randomUUID().toString();
                GlobalRouteState.startRecording(System.identityHashCode(channel), sessionId, host, port);
                GlobalRouteState.logInfo("[Baafoo] NIO Socket recording: " + host + ":" + port + " (sessionId=" + sessionId + ")");
                // Don't redirect — connection goes to real target
                return;
            }

            // RECORD_AND_STUB mode (3): redirect to stub (recording happens via
            // the stub's passthrough proxy on the server side)
            // STUB mode (0): redirect to stub server

            String[] routeValue = GlobalRouteState.lookup(host, port);

            // DNS cache fallback
            if (routeValue == null && !"127.0.0.1".equals(host) && !"localhost".equals(host)) {
                String originalDomain = (String) GlobalRouteState.DNS_CACHE.get(host);
                if (originalDomain != null) {
                    routeValue = GlobalRouteState.lookup(originalDomain, port);
                }
            }

            if (routeValue != null) {
                GlobalRouteState.logInfo("[Baafoo] NIO Socket redirect: " + host + ":" + port + " -> " + routeValue[0] + ":" + routeValue[1]);
                remote = new InetSocketAddress(routeValue[0], Integer.parseInt(routeValue[1]));
            }
        } catch (Throwable t) {
            GlobalRouteState.logError("[Baafoo] NioSocketConnectAdvice error: " + t);
        }
    }
}
