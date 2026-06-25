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
                GlobalRouteState.logInfo("[Baafoo] NIO Socket connect: non-InetSocketAddress remote=" + remote);
                return;
            }

            InetSocketAddress addr = (InetSocketAddress) remote;
            String host = addr.getHostString();
            int port = addr.getPort();

            // Skip internal connections (Baafoo server & stub ports).
            // MQ ports (Kafka/Pulsar/JMS) are recorded at the application layer
            // by the Server, so skip Socket-level recording to avoid duplicates.
            if (GlobalRouteState.isInternal(host, port)) {
                if ((GlobalRouteState.CURRENT_MODE == 2 || GlobalRouteState.CURRENT_MODE == 3
                        || GlobalRouteState.CURRENT_MODE == 4)
                        && port != GlobalRouteState.SERVER_PORT
                        && port != GlobalRouteState.HTTP_PORT
                        && port != GlobalRouteState.KAFKA_PORT
                        && port != GlobalRouteState.PULSAR_PORT
                        && port != GlobalRouteState.JMS_PORT) {
                    String sessionId = java.util.UUID.randomUUID().toString();
                    GlobalRouteState.startRecording(System.identityHashCode(channel), sessionId, host, port);
                    GlobalRouteState.logInfo("[Baafoo] NIO Socket recording (internal): " + host + ":" + port + " (sessionId=" + sessionId + ")");
                }
                return;
            }

            // Check passthrough mode (1=PASSTHROUGH)
            if (GlobalRouteState.CURRENT_MODE == 1) {
                return;
            }

            // Record mode (2=RECORD, 3=RECORD_AND_STUB): only record connections
            // that have a matching route. Skip Socket-level recording for HTTP
            // rules (HTTP has its own protocol-level recorder in HttpURLConnectionAdvice).
            // Kafka/Pulsar/JMS/TCP rely on Socket-level stream recording.
            if (GlobalRouteState.CURRENT_MODE == 2 || GlobalRouteState.CURRENT_MODE == 3) {
                // Look up route first — only record if there's a matching rule
                String[] routeValue = GlobalRouteState.lookup(host, port);
                if (routeValue == null && !"127.0.0.1".equals(host) && !"localhost".equals(host)) {
                    String originalDomain = (String) GlobalRouteState.DNS_CACHE.get(host);
                    if (originalDomain != null) {
                        routeValue = GlobalRouteState.lookup(originalDomain, port);
                    }
                }

                if (routeValue != null) {
                    int targetPort = Integer.parseInt(routeValue[1]);
                    // Skip Socket-level recording for HTTP and MQ — they have
                    // their own protocol-level recorders (HTTP: HttpURLConnectionAdvice,
                    // MQ: Server-side application-layer recording).
                    if (targetPort != GlobalRouteState.HTTP_PORT
                            && targetPort != GlobalRouteState.KAFKA_PORT
                            && targetPort != GlobalRouteState.PULSAR_PORT
                            && targetPort != GlobalRouteState.JMS_PORT) {
                        String sessionId = java.util.UUID.randomUUID().toString();
                        GlobalRouteState.startRecording(System.identityHashCode(channel), sessionId, host, port);
                        GlobalRouteState.logInfo("[Baafoo] NIO Socket recording: " + host + ":" + port + " (sessionId=" + sessionId + ")");
                    }

                    // In RECORD_AND_STUB mode, also redirect to stub
                    if (GlobalRouteState.CURRENT_MODE == 3) {
                        GlobalRouteState.logInfo("[Baafoo] NIO Socket redirect (record-and-stub): " + host + ":" + port + " -> " + routeValue[0] + ":" + routeValue[1]);
                        remote = new InetSocketAddress(routeValue[0], targetPort);
                    }
                }
                // In pure RECORD mode, don't redirect — connection goes to real target
                return;
            }

            // RECORD_ALL mode (4): redirect ALL traffic to stub ports for recording,
            // regardless of whether a matching rule exists.
            if (GlobalRouteState.CURRENT_MODE == 4) {
                String[] routeValue = GlobalRouteState.lookup(host, port);

                // DNS cache fallback
                if (routeValue == null && !"127.0.0.1".equals(host) && !"localhost".equals(host)) {
                    String originalDomain = (String) GlobalRouteState.DNS_CACHE.get(host);
                    if (originalDomain != null) {
                        routeValue = GlobalRouteState.lookup(originalDomain, port);
                    }
                }

                // Plugin SPI fallback (prefer EXT, fallback to legacy)
                if (routeValue == null) {
                    java.util.function.Function<Object[], Object[]> consultExtFn = GlobalRouteState.PLUGIN_CONSULT_FN_EXT;
                    if (consultExtFn != null) {
                        try {
                            Object[] extResult = consultExtFn.apply(new Object[]{host, Integer.valueOf(port), "nio"});
                            if (extResult != null && extResult.length >= 1) {
                                int action = ((Integer) extResult[0]).intValue();
                                if (action == 1 && extResult.length >= 3) {
                                    routeValue = new String[]{(String) extResult[1], String.valueOf(extResult[2])};
                                } else if (action == 2) {
                                    GlobalRouteState.logInfo("[Baafoo] NIO Socket blocked by plugin: " + (extResult.length > 3 ? extResult[3] : "blocked"));
                                    return;
                                }
                            }
                        } catch (Throwable t) {
                            GlobalRouteState.logDebug("[Baafoo] NIO Socket plugin EXT consult skipped: " + t.getMessage());
                        }
                    }
                    if (routeValue == null) {
                        java.util.function.Function<Object[], Object[]> consultFn = GlobalRouteState.PLUGIN_CONSULT_FN;
                        if (consultFn != null) {
                            try {
                                Object[] pluginResult = consultFn.apply(new Object[]{host, Integer.valueOf(port)});
                                if (pluginResult != null && pluginResult.length >= 2) {
                                    routeValue = new String[]{(String) pluginResult[0], String.valueOf(pluginResult[1])};
                                }
                            } catch (Throwable t) {
                                GlobalRouteState.logDebug("[Baafoo] NIO Socket plugin consult skipped: " + t.getMessage());
                            }
                        }
                    }
                }

                // Fallback: no route matched — use protocol inference to pick a stub port.
                // For HTTP/Kafka/Pulsar/JMS ports: redirect to the corresponding stub port
                // (Server-side handlers will passthrough+record).
                // For generic TCP ports: do NOT redirect — connect directly to the real
                // target and record at the stream level via SocketChannelRead/WriteAdvice.
                // This avoids the need for a TCP relay on the Server side.
                if (routeValue == null) {
                    int fallbackPort = GlobalRouteState.forceRedirectPort(port);
                    if (fallbackPort == GlobalRouteState.TCP_PORT) {
                        // Generic TCP: passthrough + stream-level recording (no redirect)
                        String sessionId = java.util.UUID.randomUUID().toString();
                        GlobalRouteState.startRecording(System.identityHashCode(channel), sessionId, host, port);
                        GlobalRouteState.logInfo("[Baafoo] NIO RECORD_ALL TCP passthrough+record: " + host + ":" + port + " (sessionId=" + sessionId + ")");
                        return; // Don't redirect — connection goes to real target
                    }
                    // HTTP/Kafka/Pulsar/JMS: redirect to stub port for Server-side handling
                    routeValue = new String[]{GlobalRouteState.SERVER_HOST, String.valueOf(fallbackPort)};
                    GlobalRouteState.logInfo("[Baafoo] NIO RECORD_ALL fallback: " + host + ":" + port + " -> " + routeValue[0] + ":" + routeValue[1]);
                }

                int targetPort = Integer.parseInt(routeValue[1]);
                // Register for stream-level recording (TCP, non-HTTP/MQ)
                if (targetPort != GlobalRouteState.HTTP_PORT
                        && targetPort != GlobalRouteState.KAFKA_PORT
                        && targetPort != GlobalRouteState.PULSAR_PORT
                        && targetPort != GlobalRouteState.JMS_PORT) {
                    String sessionId = java.util.UUID.randomUUID().toString();
                    GlobalRouteState.startRecording(System.identityHashCode(channel), sessionId, host, port);
                    GlobalRouteState.logInfo("[Baafoo] NIO Socket recording (record-all): " + host + ":" + port + " (sessionId=" + sessionId + ")");
                }
                GlobalRouteState.logInfo("[Baafoo] NIO Socket redirect (record-all): " + host + ":" + port + " -> " + routeValue[0] + ":" + targetPort);
                remote = new InetSocketAddress(routeValue[0], targetPort);
                return;
            }

            // STUB mode (0): redirect to stub server
            String[] routeValue = GlobalRouteState.lookup(host, port);

            // DNS cache fallback
            if (routeValue == null && !"127.0.0.1".equals(host) && !"localhost".equals(host)) {
                String originalDomain = (String) GlobalRouteState.DNS_CACHE.get(host);
                if (originalDomain != null) {
                    routeValue = GlobalRouteState.lookup(originalDomain, port);
                }
            }

            // Plugin SPI fallback: prefer EXT, fallback to legacy
            if (routeValue == null) {
                java.util.function.Function<Object[], Object[]> consultExtFn = GlobalRouteState.PLUGIN_CONSULT_FN_EXT;
                if (consultExtFn != null) {
                    try {
                        Object[] extResult = consultExtFn.apply(new Object[]{host, Integer.valueOf(port), "nio"});
                        if (extResult != null && extResult.length >= 1) {
                            int action = ((Integer) extResult[0]).intValue();
                            if (action == 1 && extResult.length >= 3) {
                                routeValue = new String[]{(String) extResult[1], String.valueOf(extResult[2])};
                                GlobalRouteState.logInfo("[Baafoo] NIO Socket plugin redirected (EXT): " + host + ":" + port + " -> " + routeValue[0] + ":" + routeValue[1]);
                            } else if (action == 2) {
                                GlobalRouteState.logInfo("[Baafoo] NIO Socket blocked by plugin: " + (extResult.length > 3 ? extResult[3] : "blocked"));
                                return;
                            }
                        }
                    } catch (Throwable t) {
                        GlobalRouteState.logDebug("[Baafoo] NIO Socket plugin EXT consult skipped: " + t.getMessage());
                    }
                }
                if (routeValue == null) {
                    java.util.function.Function<Object[], Object[]> consultFn = GlobalRouteState.PLUGIN_CONSULT_FN;
                    if (consultFn != null) {
                        try {
                            Object[] pluginResult = consultFn.apply(new Object[]{host, Integer.valueOf(port)});
                            if (pluginResult != null && pluginResult.length >= 2) {
                                routeValue = new String[]{(String) pluginResult[0], String.valueOf(pluginResult[1])};
                                GlobalRouteState.logInfo("[Baafoo] NIO Socket plugin redirected: " + host + ":" + port + " -> " + routeValue[0] + ":" + routeValue[1]);
                            }
                        } catch (Throwable t) {
                            GlobalRouteState.logDebug("[Baafoo] NIO Socket plugin consult skipped: " + t.getMessage());
                        }
                    }
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
