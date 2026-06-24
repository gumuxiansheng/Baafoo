package com.baafoo.agent.advice;

import net.bytebuddy.asm.Advice;

/**
 * Intercepts {@code io.grpc.ManagedChannelBuilder.forTarget(String)} to redirect
 * gRPC channel targets to the Baafoo stub server.
 *
 * <p>This advice runs in the App ClassLoader (not Bootstrap CL), because
 * {@code io.grpc.*} classes are loaded by the App CL. It references
 * {@link com.baafoo.agent.GlobalRouteState} which is available on both CLs
 * (synced by {@code BaafooAgent.setupBootstrapClassPath}).</p>
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
 * the target is replaced with the stub server address. Otherwise the original
 * target is passed through unchanged.</p>
 */
public class GrpcChannelAdvice {

    @Advice.OnMethodEnter
    public static void onForTarget(@Advice.Argument(value = 0, readOnly = false) String target) {
        try {
            if (target == null || target.isEmpty()) return;

            String[] parts = parseTarget(target);
            if (parts == null) return;

            String host = parts[0];
            // Use GlobalRouteState.GRPC_PORT as default, not hardcoded 9090
            int defaultGrpcPort = com.baafoo.agent.GlobalRouteState.GRPC_PORT;
            int port = parts.length > 1 ? Integer.parseInt(parts[1]) : defaultGrpcPort;

            // Check route table for redirect
            String[] route = com.baafoo.agent.GlobalRouteState.lookup(host, port);
            if (route != null) {
                String newTarget = route[0] + ":" + route[1];
                com.baafoo.agent.GlobalRouteState.logInfo(
                        "[Baafoo] gRPC channel redirect: " + host + ":" + port + " -> " + newTarget);
                target = newTarget;
            }
        } catch (Throwable t) {
            com.baafoo.agent.GlobalRouteState.logError(
                    "[Baafoo] GrpcChannelAdvice error: " + t.getMessage());
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
    static String[] parseTarget(String target) {
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
            int defaultPort = com.baafoo.agent.GlobalRouteState.GRPC_PORT;
            return new String[]{cleaned, String.valueOf(defaultPort)};
        }

        String hostPart = cleaned.substring(0, colonIdx);
        String portPart = cleaned.substring(colonIdx + 1);

        // Validate port is numeric
        try {
            Integer.parseInt(portPart);
        } catch (NumberFormatException e) {
            // Not a valid port — treat entire string as host with default port
            int defaultPort = com.baafoo.agent.GlobalRouteState.GRPC_PORT;
            return new String[]{cleaned, String.valueOf(defaultPort)};
        }

        if (hostPart.isEmpty()) return null;

        return new String[]{hostPart, portPart};
    }
}
