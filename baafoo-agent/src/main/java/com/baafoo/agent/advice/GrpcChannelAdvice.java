package com.baafoo.agent.advice;

import com.baafoo.agent.BaafooAgent;
import com.baafoo.agent.GlobalRouteState;
import com.baafoo.agent.plugin.PluginManager;
import com.baafoo.core.model.EnvironmentMode;
import com.baafoo.plugin.ConnectAdvice;
import com.baafoo.plugin.ConnectContext;
import com.baafoo.plugin.InterceptTarget;
import com.baafoo.plugin.PluginEvent;
import net.bytebuddy.asm.Advice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Byte Buddy Advice for {@code io.grpc.ManagedChannelBuilder.forTarget(String)}.
 *
 * <p>Intercepts gRPC channel construction to redirect targets to the Baafoo
 * stub server (port {@link GlobalRouteState#GRPC_PORT} by default).</p>
 *
 * <p><b>ClassLoader</b>: This advice runs in the <b>App ClassLoader</b>
 * (not Bootstrap CL), because {@code io.grpc.*} classes are loaded by the
 * App CL. It therefore references {@link PluginManager}, the SLF4J logger,
 * and the SPI types directly — consistent with {@link KafkaProducerAdvice},
 * {@link JmsConnectionFactoryAdvice}, and {@link PulsarClientAdvice}.</p>
 *
 * <p>Target parsing:
 * <ul>
 *   <li>{@code dns:///host:port} -> host:port</li>
 *   <li>{@code static:///host:port} -> host:port</li>
 *   <li>{@code host:port} -> host:port</li>
 *   <li>{@code host} (no port) -> host:GRPC_PORT (from GlobalRouteState, default 9005)</li>
 * </ul>
 * </p>
 *
 * <p>If the target host:port matches a route in {@link GlobalRouteState#ROUTES},
 * the target is replaced with the stub server address. The registered gRPC
 * plugin (if any) is consulted via {@link PluginManager#connectWithMonitor}
 * and may override the redirect target. Otherwise the original target is
 * passed through unchanged.</p>
 */
public class GrpcChannelAdvice {

    /** Must be public — inlined code in the target class cannot access private fields. */
    public static final Logger log = LoggerFactory.getLogger(GrpcChannelAdvice.class);

    @Advice.OnMethodEnter
    public static void onForTarget(@Advice.Argument(value = 0, readOnly = false) String target) {
        try {
            log.info("[DIAG-grpc] onForTarget ENTER target={}", target);
            if (target == null || target.isEmpty()) return;

            // Skip interception in PASSTHROUGH mode (consistent with other App-CL advices)
            EnvironmentMode mode = RouteManager.getMode();
            log.info("[DIAG-grpc] mode={}", mode);
            if (mode == EnvironmentMode.PASSTHROUGH) {
                return;
            }

            String[] parts = parseTarget(target);
            if (parts == null) return;

            String host = parts[0];
            // Use GlobalRouteState.GRPC_PORT as default, not hardcoded 9090
            int defaultGrpcPort = GlobalRouteState.GRPC_PORT;
            int port = parts.length > 1 ? Integer.parseInt(parts[1]) : defaultGrpcPort;

            // 1. Check route table for redirect (core path — no optional SPI dependency)
            String[] route = GlobalRouteState.lookup(host, port);
            log.info("[DIAG-grpc] lookup({}:{}) => {}", host, port, route);

            if (route != null) {
                String originalTarget = host + ":" + port;
                String newTarget = route[0] + ":" + route[1];
                log.info("[DIAG-grpc] REWRITE target={} -> {}", originalTarget, newTarget);
                try {
                    GlobalRouteState.firePluginEvent(
                            PluginEvent.connectionRedirected("grpc", originalTarget, newTarget));
                } catch (Throwable ignore) {
                    // event is best-effort; must not break the redirect
                }
                target = newTarget;
            }
        } catch (Throwable t) {
            log.error("[Baafoo] GrpcChannelAdvice error: {}", t.getMessage());
        }
    }

    /**
     * Parse a gRPC target string into [host, port].
     *
     * <p>Supports formats:
     * <ul>
     *   <li>{@code dns:///host:port} -> ["host", "port"]</li>
     *   <li>{@code static:///host:port} -> ["host", "port"]</li>
     *   <li>{@code host:port} -> ["host", "port"]</li>
     *   <li>{@code host} -> ["host", "GRPC_PORT"]</li>
     * </ul>
     * </p>
     *
     * @return [host, port] array, or null if the target cannot be parsed
     */
    public static String[] parseTarget(String target) {
        if (target == null || target.isEmpty()) return null;

        String cleaned = target;

        // Strip scheme prefixes
        if (cleaned.startsWith("dns:///")) {
            cleaned = cleaned.substring(7);
        } else if (cleaned.startsWith("static:///")) {
            cleaned = cleaned.substring(10);
        } else if (cleaned.startsWith("dns:")) {
            // dns:host:port (without ///)
            cleaned = cleaned.substring(4);
        }

        if (cleaned.isEmpty()) return null;

        // Split host:port
        int colonIdx = cleaned.lastIndexOf(':');
        if (colonIdx < 0) {
            // No port specified — use default gRPC port from GlobalRouteState
            int defaultPort = GlobalRouteState.GRPC_PORT;
            return new String[]{cleaned, String.valueOf(defaultPort)};
        }

        String hostPart = cleaned.substring(0, colonIdx);
        String portPart = cleaned.substring(colonIdx + 1);

        // Validate port is numeric
        try {
            Integer.parseInt(portPart);
        } catch (NumberFormatException e) {
            // Not a valid port — treat entire string as host with default port
            int defaultPort = GlobalRouteState.GRPC_PORT;
            return new String[]{cleaned, String.valueOf(defaultPort)};
        }

        if (hostPart.isEmpty()) return null;

        return new String[]{hostPart, portPart};
    }
}
