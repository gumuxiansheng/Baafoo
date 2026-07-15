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
 * the connection is redirected to the stub server (same as STUB mode). The
 * server-side handler forwards to the real backend and records the response.
 * Stream-level recording is skipped for HTTP/MQ (server handles recording).</p>
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
                    GlobalRouteState.startRecording(System.identityHashCode(socket), sessionId, host, port);
                    GlobalRouteState.logInfo("[Baafoo] Socket recording (internal): " + host + ":" + port + " (sessionId=" + sessionId + ")");
                }
                return;
            }

            // Check passthrough mode (1=PASSTHROUGH)
            if (GlobalRouteState.CURRENT_MODE == 1) {
                return;
            }

            // Record mode (2=RECORD, 3=RECORD_AND_STUB): redirect matching connections
            // to the stub server. The server-side handler forwards to the real backend
            // and records the response. Skip Socket-level stream recording for HTTP/MQ
            // (server handles recording at the application layer).
            if (GlobalRouteState.CURRENT_MODE == 2 || GlobalRouteState.CURRENT_MODE == 3) {
                // Look up route first — only redirect if there's a matching rule
                String[] routeValue = GlobalRouteState.lookup(host, port);
                if (routeValue == null && !"127.0.0.1".equals(host) && !"localhost".equals(host)) {
                    String originalDomain = (String) GlobalRouteState.DNS_CACHE.get(host);
                    if (originalDomain != null) {
                        routeValue = GlobalRouteState.lookup(originalDomain, port);
                    }
                }

                if (routeValue != null) {
                    int targetPort = Integer.parseInt(routeValue[1]);
                    // Skip Socket-level recording for HTTP and MQ — the server-side
                    // handler records at the application layer (forward + record).
                    if (targetPort != GlobalRouteState.HTTP_PORT
                            && targetPort != GlobalRouteState.KAFKA_PORT
                            && targetPort != GlobalRouteState.PULSAR_PORT
                            && targetPort != GlobalRouteState.JMS_PORT) {
                        String sessionId = java.util.UUID.randomUUID().toString();
                        GlobalRouteState.startRecording(System.identityHashCode(socket), sessionId, host, port);
                        GlobalRouteState.logInfo("[Baafoo] Socket recording: " + host + ":" + port + " (sessionId=" + sessionId + ")");
                    }

                    // Redirect to stub server in both RECORD and RECORD_AND_STUB modes.
                    // The server-side handler differentiates: RECORD forwards to real
                    // backend + records; RECORD_AND_STUB returns stub + records.
                    GlobalRouteState.logInfo("[Baafoo] Socket redirect (record): " + host + ":" + port + " -> " + routeValue[0] + ":" + routeValue[1]);
                    endpoint = new InetSocketAddress(routeValue[0], targetPort);
                }
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

                // Plugin SPI fallback
                if (routeValue == null) {
                    java.util.function.Function<Object[], Object[]> consultExtFn = GlobalRouteState.PLUGIN_CONSULT_FN_EXT;
                    if (consultExtFn != null) {
                        try {
                            Object[] extResult = consultExtFn.apply(new Object[]{host, Integer.valueOf(port), "tcp"});
                            if (extResult != null && extResult.length >= 1) {
                                int action = ((Integer) extResult[0]).intValue();
                                if (action == 1 && extResult.length >= 3) {
                                    // REDIRECT
                                    routeValue = new String[]{(String) extResult[1], String.valueOf(extResult[2])};
                                    GlobalRouteState.logInfo("[Baafoo] Socket plugin redirected (EXT): " + host + ":" + port + " -> " + routeValue[0] + ":" + routeValue[1]);
                                } else if (action == 2) {
                                    // BLOCK — redirect to an unroutable address so that
                                    // Socket.connect() throws ConnectException.
                                    // Previously this just returned, which let the original
                                    // connect() proceed to the real target.
                                    GlobalRouteState.logInfo("[Baafoo] Socket blocked by plugin: " + (extResult.length > 3 ? extResult[3] : "blocked"));
                                    endpoint = new InetSocketAddress("0.0.0.0", 1);
                                    return;
                                }
                                // action == 0 (PASSTHROUGH): do nothing, continue
                            }
                        } catch (Throwable t) {
                            GlobalRouteState.logDebug("[Baafoo] Socket plugin EXT consult skipped: " + t.getMessage());
                        }
                    }
                }

                // Fallback: no route matched — use protocol inference to pick a stub port.
                // For HTTP/Kafka/Pulsar/JMS ports: redirect to the corresponding stub port
                // (Server-side handlers will passthrough+record).
                // For generic TCP ports: do NOT redirect — connect directly to the real
                // target and record at the stream level via RecordingInputStream/RecordingOutputStream.
                // This avoids the need for a TCP relay on the Server side.
                if (routeValue == null) {
                    int fallbackPort = GlobalRouteState.forceRedirectPort(port);
                    if (fallbackPort == GlobalRouteState.TCP_PORT) {
                        // Generic TCP: passthrough + stream-level recording (no redirect)
                        String sessionId = java.util.UUID.randomUUID().toString();
                        GlobalRouteState.startRecording(System.identityHashCode(socket), sessionId, host, port);
                        GlobalRouteState.logInfo("[Baafoo] RECORD_ALL TCP passthrough+record: " + host + ":" + port + " (sessionId=" + sessionId + ")");
                        return; // Don't redirect — connection goes to real target
                    }
                    // HTTP/Kafka/Pulsar/JMS: redirect to stub port for Server-side handling
                    routeValue = new String[]{GlobalRouteState.SERVER_HOST, String.valueOf(fallbackPort)};
                    GlobalRouteState.logInfo("[Baafoo] RECORD_ALL fallback: " + host + ":" + port + " -> " + routeValue[0] + ":" + routeValue[1]);
                }

                int targetPort = Integer.parseInt(routeValue[1]);
                // Register for stream-level recording (TCP, non-HTTP/MQ)
                if (targetPort != GlobalRouteState.HTTP_PORT
                        && targetPort != GlobalRouteState.KAFKA_PORT
                        && targetPort != GlobalRouteState.PULSAR_PORT
                        && targetPort != GlobalRouteState.JMS_PORT) {
                    String sessionId = java.util.UUID.randomUUID().toString();
                    GlobalRouteState.startRecording(System.identityHashCode(socket), sessionId, host, port);
                    GlobalRouteState.logInfo("[Baafoo] Socket recording (record-all): " + host + ":" + port + " (sessionId=" + sessionId + ")");
                }
                GlobalRouteState.logInfo("[Baafoo] Socket redirect (record-all): " + host + ":" + port + " -> " + routeValue[0] + ":" + targetPort);
                endpoint = new InetSocketAddress(routeValue[0], targetPort);
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

            // Plugin SPI fallback
            if (routeValue == null) {
                java.util.function.Function<Object[], Object[]> consultExtFn = GlobalRouteState.PLUGIN_CONSULT_FN_EXT;
                if (consultExtFn != null) {
                    try {
                        Object[] extResult = consultExtFn.apply(new Object[]{host, Integer.valueOf(port), "tcp"});
                        if (extResult != null && extResult.length >= 1) {
                            int action = ((Integer) extResult[0]).intValue();
                            if (action == 1 && extResult.length >= 3) {
                                routeValue = new String[]{(String) extResult[1], String.valueOf(extResult[2])};
                                GlobalRouteState.logInfo("[Baafoo] Socket plugin redirected (EXT): " + host + ":" + port + " -> " + routeValue[0] + ":" + routeValue[1]);
                            } else if (action == 2) {
                                GlobalRouteState.logInfo("[Baafoo] Socket blocked by plugin: " + (extResult.length > 3 ? extResult[3] : "blocked"));
                                return;
                            }
                        }
                    } catch (Throwable t) {
                        GlobalRouteState.logDebug("[Baafoo] Socket plugin EXT consult skipped: " + t.getMessage());
                    }
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
