package com.baafoo.agent.advice;

import com.baafoo.agent.GlobalRouteState;
import net.bytebuddy.asm.Advice;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

/**
 * Intercepts SocketChannel.finishConnect() for non-blocking NIO connections.
 *
 * <p>Some clients (e.g., Pulsar/Netty) use non-blocking connect() followed by
 * finishConnect(). The connect() advice may not capture the remote address
 * correctly in non-blocking mode, so we also check here and register recording
 * if needed.</p>
 *
 * <p><b>CRITICAL</b>: Same Bootstrap CL rules apply as other NIO advice classes.</p>
 */
public final class NioSocketFinishConnectAdvice {

    private NioSocketFinishConnectAdvice() {}

    @Advice.OnMethodExit
    public static void onFinishConnect(@Advice.This Object channel,
                                       @Advice.Return boolean connected) {
        if (!connected) {
            return;
        }
        try {
            int channelId = System.identityHashCode(channel);
            // Already registered by connect() — skip
            if (GlobalRouteState.getRecordingSession(channelId) != null) {
                return;
            }

            // Try to get the remote address from the channel
            java.nio.channels.SocketChannel sc = (java.nio.channels.SocketChannel) channel;
            java.net.SocketAddress remote = sc.getRemoteAddress();
            if (!(remote instanceof InetSocketAddress)) {
                return;
            }

            InetSocketAddress addr = (InetSocketAddress) remote;
            String host = addr.getHostString();
            int port = addr.getPort();

            // Same logic as connect() — register recording for internal stub ports
            if (GlobalRouteState.isInternal(host, port)) {
                if ((GlobalRouteState.CURRENT_MODE == 2 || GlobalRouteState.CURRENT_MODE == 3)
                        && port != GlobalRouteState.SERVER_PORT
                        && port != GlobalRouteState.HTTP_PORT) {
                    String sessionId = java.util.UUID.randomUUID().toString();
                    GlobalRouteState.startRecording(channelId, sessionId, host, port);
                    GlobalRouteState.logInfo("[Baafoo] NIO Socket recording (finishConnect, internal): " + host + ":" + port + " (sessionId=" + sessionId + ")");
                }
                return;
            }

            // Check route for external connections
            if (GlobalRouteState.CURRENT_MODE == 2 || GlobalRouteState.CURRENT_MODE == 3) {
                String[] routeValue = GlobalRouteState.lookup(host, port);
                if (routeValue == null && !"127.0.0.1".equals(host) && !"localhost".equals(host)) {
                    String originalDomain = (String) GlobalRouteState.DNS_CACHE.get(host);
                    if (originalDomain != null) {
                        routeValue = GlobalRouteState.lookup(originalDomain, port);
                    }
                }
                if (routeValue != null) {
                    int targetPort = Integer.parseInt(routeValue[1]);
                    if (targetPort != GlobalRouteState.HTTP_PORT) {
                        String sessionId = java.util.UUID.randomUUID().toString();
                        GlobalRouteState.startRecording(channelId, sessionId, host, port);
                        GlobalRouteState.logInfo("[Baafoo] NIO Socket recording (finishConnect): " + host + ":" + port + " (sessionId=" + sessionId + ")");
                    }
                }
            }
        } catch (Throwable t) {
            // finishConnect might throw if not yet connected — that's fine
            // Use GlobalRouteState bridge to avoid Bootstrap CL SLF4J issues
            GlobalRouteState.logDebug("[Baafoo] NioSocketFinishConnectAdvice: " + t.getMessage());
        }
    }
}
