package com.baafoo.agent.advice;

import com.baafoo.agent.BaafooAgent;
import com.baafoo.agent.GlobalRouteState;
import com.baafoo.agent.plugin.PluginManager;
import com.baafoo.core.model.EnvironmentMode;
import com.baafoo.plugin.AgentPlugin;
import com.baafoo.plugin.InterceptResult;
import com.baafoo.plugin.InterceptTarget;
import com.baafoo.plugin.PluginContext;
import net.bytebuddy.asm.Advice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URISyntaxException;

/**
 * Byte Buddy Advice for {@code ClientBuilder.serviceUrl(String)}.
 *
 * <p>Intercepts the Pulsar client builder's {@code serviceUrl()} method
 * to replace the broker URL with the Baafoo stub Pulsar broker
 * (pulsar://localhost:9003 by default).</p>
 *
 * <p>Before rewriting, it consults the registered Pulsar plugin (if any) via the
 * {@link PluginManager} SPI. A plugin may return an {@link InterceptResult#redirect}
 * to override the default stub target — e.g. the TDMQ plugin redirects to port 9005
 * to use a dedicated TDMQ/Pulsar 2.7.4 broker. This is the agent's first production
 * SPI invocation site.</p>
 *
 * <p><b>CRITICAL</b>: This advice is inlined into Pulsar ClientBuilder by ByteBuddy.
 * Do NOT reference any private fields from this class in the advice
 * method — inlined code runs in the target class's context and cannot access
 * private fields of the advice class. The Logger field MUST be public.
 *
 * <p>Unlike the NIO socket advice (Bootstrap-CL constrained), this advice is
 * App-CL — it already references {@code org.slf4j.Logger} and {@code baafoo-core},
 * so it is safe to call {@link BaafooAgent#getPluginManager()} and the SPI types
 * directly.</p>
 */
public class PulsarClientAdvice {

    /** Must be public — inlined code in the target class cannot access private fields. */
    public static final Logger log = LoggerFactory.getLogger(PulsarClientAdvice.class);

    /**
     * Intercept ClientBuilder.serviceUrl(String) to replace the Pulsar broker URL.
     * The argument is the service URL string (e.g., "pulsar://localhost:6650").
     */
    @Advice.OnMethodEnter
    public static void onServiceUrl(
            @Advice.Argument(value = 0, readOnly = false) String serviceUrl) {

        try {
            // Only intercept in stub/recording modes
            EnvironmentMode mode = RouteManager.getMode();
            if (mode == EnvironmentMode.PASSTHROUGH) {
                return;
            }

            // Check if there are ANY Pulsar routes in the routing table
            // We intercept all Pulsar connections when Pulsar routes exist
            if (!RouteManager.hasProtocolRoutes("pulsar")) {
                return;
            }

            // Default redirect target: the built-in Pulsar mock broker.
            String targetHost = GlobalRouteState.SERVER_HOST;
            int targetPort = GlobalRouteState.PULSAR_PORT;

            // Consult the Pulsar plugin (e.g. TDMQ) — it may override the target.
            // Wrapped in its own try so any plugin failure fails closed (uses default).
            try {
                PluginManager pm = BaafooAgent.getPluginManager();
                if (pm != null) {
                    AgentPlugin plugin = pm.getPlugin(InterceptTarget.PULSAR);
                    if (plugin != null) {
                        PluginContext ctx = new PluginContext();
                        ctx.setProtocol("pulsar");
                        ctx.setHost(extractHost(serviceUrl));
                        ctx.setPort(extractPort(serviceUrl));
                        // P1: inject per-plugin config
                        ctx.setPluginConfig(pm.getPluginConfig(plugin.getName()));
                        // P2: extract tenant/namespace from serviceUrl path if present
                        // e.g. pulsar://broker:6650/my-tenant/my-namespace
                        String[] pathParts = extractPathSegments(serviceUrl);
                        if (pathParts.length > 0) ctx.setTenant(pathParts[0]);
                        if (pathParts.length > 1) ctx.setNamespace(pathParts[1]);
                        InterceptResult result = plugin.intercept(ctx);
                        if (result != null && result.isRedirect()) {
                            targetHost = result.getRedirectHost();
                            targetPort = result.getRedirectPort();
                            log.info("[Baafoo] Pulsar plugin redirected to {}:{}", targetHost, targetPort);
                        }
                    }
                }
            } catch (Throwable t) {
                // Plugin consult must never break the redirect — fall back to default.
                log.debug("[Baafoo] Pulsar plugin consult skipped: {}", t.getMessage());
            }

            String newServiceUrl = "pulsar://" + targetHost + ":" + targetPort;
            String originalUrl = serviceUrl;
            serviceUrl = newServiceUrl;

            log.info("[Baafoo] Pulsar serviceUrl replaced: {} -> {}", originalUrl, newServiceUrl);
        } catch (Exception e) {
            log.error("[Baafoo] PulsarClientAdvice error: {}", e.getMessage());
            // Fail-closed: let original serviceUrl proceed
        }
    }

    /** Extract the host from a {@code pulsar://host:port[/...]} service URL. */
    static String extractHost(String serviceUrl) {
        if (serviceUrl == null) return null;
        try {
            // URI needs a scheme but Pulsar uses "pulsar://" which URI accepts.
            URI uri = new URI(serviceUrl);
            String host = uri.getHost();
            // A schemeless string like "host:port" parses as a URI whose
            // "host:port" prefix is mistaken for a scheme, leaving host null.
            // Fall through to the manual parser whenever URI yields no host.
            if (host != null) return host;
        } catch (URISyntaxException e) {
            // Fall through to the manual split on "://" and ":".
        }
        return manualSplit(serviceUrl)[0];
    }

    /** Extract the port from a {@code pulsar://host:port[/...]} service URL; -1 if absent. */
    static int extractPort(String serviceUrl) {
        if (serviceUrl == null) return -1;
        try {
            URI uri = new URI(serviceUrl);
            // Trust URI's port only when it also parsed a host; otherwise the
            // port was mis-parsed (see extractHost) and we must split manually.
            if (uri.getHost() != null) return uri.getPort();
        } catch (URISyntaxException e) {
            // Fall through to the manual split.
        }
        String portStr = manualSplit(serviceUrl)[1];
        if (portStr == null) return -1;
        try {
            return Integer.parseInt(portStr);
        } catch (NumberFormatException nfe) {
            return -1;
        }
    }

    /**
     * Extract path segments from a Pulsar service URL.
     * e.g. {@code pulsar://broker:6650/my-tenant/my-namespace} returns {"my-tenant", "my-namespace"}.
     * Returns empty array if no path or fewer than 2 segments.
     */
    static String[] extractPathSegments(String serviceUrl) {
        if (serviceUrl == null) return new String[0];
        try {
            URI uri = new URI(serviceUrl);
            String path = uri.getPath();
            if (path != null && path.startsWith("/")) path = path.substring(1);
            if (path == null || path.isEmpty()) return new String[0];
            return path.split("/");
        } catch (URISyntaxException e) {
            // Fall back to manual parsing
            int schemeEnd = serviceUrl.indexOf("://");
            String rest = schemeEnd >= 0 ? serviceUrl.substring(schemeEnd + 3) : serviceUrl;
            int slash = rest.indexOf('/');
            if (slash < 0) return new String[0];
            String path = rest.substring(slash + 1);
            if (path.isEmpty()) return new String[0];
            return path.split("/");
        }
    }

    /** Manual fallback parser: returns {host, portStr} from {@code pulsar://host:port...}. */
    private static String[] manualSplit(String serviceUrl) {
        String[] out = new String[]{null, null};
        int schemeEnd = serviceUrl.indexOf("://");
        String rest = schemeEnd >= 0 ? serviceUrl.substring(schemeEnd + 3) : serviceUrl;
        int slash = rest.indexOf('/');
        if (slash >= 0) rest = rest.substring(0, slash);
        int colon = rest.indexOf(':');
        if (colon >= 0) {
            out[0] = rest.substring(0, colon);
            out[1] = rest.substring(colon + 1);
        } else {
            out[0] = rest;
        }
        return out;
    }
}
