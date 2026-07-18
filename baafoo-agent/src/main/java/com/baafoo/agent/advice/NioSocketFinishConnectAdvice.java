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
            if (!(channel instanceof java.nio.channels.SocketChannel)) {
                return;
            }
            java.nio.channels.SocketChannel sc = (java.nio.channels.SocketChannel) channel;
            java.net.SocketAddress remote = sc.getRemoteAddress();
            if (!(remote instanceof InetSocketAddress)) {
                return;
            }

            InetSocketAddress addr = (InetSocketAddress) remote;
            String host = addr.getHostString();
            int port = addr.getPort();

            // Same logic as connect() — skip MQ ports (recorded at application layer by Server)
            if (GlobalRouteState.isInternal(host, port)) {
                if ((GlobalRouteState.CURRENT_MODE == 2 || GlobalRouteState.CURRENT_MODE == 3
                        || GlobalRouteState.CURRENT_MODE == 4)
                        && port != GlobalRouteState.SERVER_PORT
                        && GlobalRouteState.isStreamRecordingPort(port)) {
                    String sessionId = java.util.UUID.randomUUID().toString();
                    GlobalRouteState.startRecording(channelId, sessionId, host, port);
                    GlobalRouteState.logInfo("[Baafoo] NIO Socket recording (finishConnect, internal): " + host + ":" + port + " (sessionId=" + sessionId + ")");
                }
                return;
            }

            // Check route for external connections
            if (GlobalRouteState.CURRENT_MODE == 2 || GlobalRouteState.CURRENT_MODE == 3
                    || GlobalRouteState.CURRENT_MODE == 4) {
                String[] routeValue = GlobalRouteState.lookup(host, port);
                if (routeValue == null && !"127.0.0.1".equals(host) && !"localhost".equals(host)) {
                    String originalDomain = (String) GlobalRouteState.DNS_CACHE.get(host);
                    if (originalDomain != null) {
                        routeValue = GlobalRouteState.lookup(originalDomain, port);
                    }
                }
                // RECORD_ALL: if no route matched, register for stream-level recording.
                // The connect() advice already decided whether to redirect or passthrough.
                // For TCP ports: connect() did NOT redirect (passthrough to real target),
                // so we need to register the recording session here.
                // For HTTP/MQ ports: connect() redirected to stub port, so the session
                // was already registered there — skip here.
                if (GlobalRouteState.CURRENT_MODE == 4 && routeValue == null) {
                    int fallbackPort = GlobalRouteState.forceRedirectPort(port);
                    if (fallbackPort == GlobalRouteState.TCP_PORT) {
                        // Generic TCP passthrough: register for stream-level recording
                        String sessionId = java.util.UUID.randomUUID().toString();
                        GlobalRouteState.startRecording(channelId, sessionId, host, port);
                        GlobalRouteState.logInfo("[Baafoo] NIO Socket recording (finishConnect, record-all TCP passthrough): " + host + ":" + port + " (sessionId=" + sessionId + ")");
                    }
                    // For HTTP/MQ: the connect() advice already redirected and registered
                    // the session, so the early "Already registered" check above will skip.
                    // If somehow not registered (e.g., finishConnect on a different channel),
                    // the Server-side handler will handle recording.
                } else if (routeValue != null) {
                    int targetPort = Integer.parseInt(routeValue[1]);
                    // Skip Socket-level recording for HTTP and MQ — the server-side
                    // handler records at the application layer (forward + record).
                    if (GlobalRouteState.isStreamRecordingPort(targetPort)) {
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
