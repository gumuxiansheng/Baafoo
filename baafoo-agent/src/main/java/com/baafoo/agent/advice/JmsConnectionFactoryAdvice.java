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
 * Byte Buddy Advice for JMS {@code ActiveMQConnectionFactory}.
 *
 * <p>Intercepts the constructor to replace the broker URL with the Baafoo JMS Mock
 * Broker address (port 9004 by default). Uses {@code @Advice.OnMethodExit} because
 * constructor argument modification via {@code @Advice.OnMethodEnter} does not
 * reliably propagate in ByteBuddy.</p>
 *
 * <p>On exit, we call {@code setBrokerURL} on the constructed object to override
 * whatever URL was set during construction. Before rewriting, it consults the
 * registered JMS plugin (if any) via the {@link PluginManager} SPI. A plugin may
 * return an {@link InterceptResult#redirect} to override the default stub target.</p>
 *
 * <p><b>CRITICAL</b>: This advice is inlined into ActiveMQConnectionFactory by ByteBuddy.
 * Do NOT reference any private fields from this class in the advice
 * method — inlined code runs in the target class's context and cannot access
 * private fields of the advice class. The Logger field MUST be public.</p>
 */
public class JmsConnectionFactoryAdvice {

    /** Must be public — inlined code in the target class cannot access private fields. */
    public static final Logger log = LoggerFactory.getLogger(JmsConnectionFactoryAdvice.class);

    /**
     * After constructor completes, replace the brokerURL with the stub broker.
     */
    @Advice.OnMethodExit
    public static void onConstructorExit(@Advice.This Object self) {
        try {
            EnvironmentMode mode = RouteManager.getMode();
            if (mode == EnvironmentMode.PASSTHROUGH) {
                return;
            }

            if (!RouteManager.hasProtocolRoutes("jms")) {
                return;
            }

            String stubHost = GlobalRouteState.SERVER_HOST;
            int stubPort = GlobalRouteState.JMS_PORT;

            // Read the original broker URL for plugin context
            String originalUrl = null;
            try {
                originalUrl = (String) self.getClass().getMethod("getBrokerURL").invoke(self);
            } catch (Exception ignored) {
                // getBrokerURL not available — proceed with null
            }

            // Consult the JMS plugin — it may override the target.
            // Wrapped in its own try so any plugin failure fails closed (uses default).
            try {
                PluginManager pm = BaafooAgent.getPluginManager();
                if (pm != null) {
                    // P2: Use new ConnectAdvice API via connectWithMonitor
                    ConnectContext ctx = new ConnectContext(
                            "jms", extractHost(originalUrl), extractPort(originalUrl),
                            null, null, null, originalUrl,
                            null, extractDestination(originalUrl));
                    ConnectAdvice advice = pm.connectWithMonitor(InterceptTarget.JMS, ctx);
                    if (advice != null && advice.isRedirect()) {
                        stubHost = advice.getRedirectHost();
                        stubPort = advice.getRedirectPort();
                        log.info("[Baafoo] JMS plugin redirected to {}:{}", stubHost, stubPort);
                        pm.fireEvent(PluginEvent.connectionRedirected(
                                "jms", originalUrl, stubHost + ":" + stubPort));
                    } else if (advice != null && advice.isPassthrough()) {
                        pm.fireEvent(PluginEvent.connectionPassthrough("jms", originalUrl));
                    }
                }
            } catch (Throwable t) {
                log.debug("[Baafoo] JMS plugin consult skipped: {}", t.getMessage());
            }

            String newBrokerUrl = "tcp://" + stubHost + ":" + stubPort;

            // Use reflection to call setBrokerURL — avoids compile-time dependency
            // on ActiveMQConnectionFactory in the advice class
            try {
                java.lang.reflect.Method setBrokerURL = self.getClass().getMethod("setBrokerURL", String.class);
                setBrokerURL.invoke(self, newBrokerUrl);
                log.info("[Baafoo] JMS brokerURL replaced: {} -> {}", originalUrl, newBrokerUrl);
            } catch (NoSuchMethodException e) {
                log.warn("[Baafoo] ActiveMQConnectionFactory does not have setBrokerURL method");
            }

        } catch (Exception e) {
            log.error("[Baafoo] JmsConnectionFactoryAdvice error: {}", e.getMessage());
        }
    }

    /** Extract the host from a {@code tcp://host:port} broker URL. */
    static String extractHost(String brokerUrl) {
        if (brokerUrl == null) return null;
        String s = brokerUrl;
        int schemeEnd = s.indexOf("://");
        if (schemeEnd >= 0) s = s.substring(schemeEnd + 3);
        int colon = s.indexOf(':');
        int slash = s.indexOf('/');
        int end = colon >= 0 ? colon : (slash >= 0 ? slash : s.length());
        return s.substring(0, end);
    }

    /** Extract the port from a {@code tcp://host:port} broker URL; -1 if absent. */
    static int extractPort(String brokerUrl) {
        if (brokerUrl == null) return -1;
        String s = brokerUrl;
        int schemeEnd = s.indexOf("://");
        if (schemeEnd >= 0) s = s.substring(schemeEnd + 3);
        int colon = s.indexOf(':');
        if (colon < 0) return -1;
        String rest = s.substring(colon + 1);
        int slash = rest.indexOf('/');
        String portStr = slash >= 0 ? rest.substring(0, slash) : rest;
        try {
            return Integer.parseInt(portStr);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    /**
     * Extract destination name from broker URL path if present.
     * e.g. {@code tcp://broker:61616/queue.orders} returns "queue.orders".
     * Returns null if no path segment.
     */
    public static String extractDestination(String brokerUrl) {
        if (brokerUrl == null) return null;
        String s = brokerUrl;
        int schemeEnd = s.indexOf("://");
        if (schemeEnd >= 0) s = s.substring(schemeEnd + 3);
        int slash = s.indexOf('/');
        if (slash < 0) return null;
        String path = s.substring(slash + 1);
        // Strip query string
        int query = path.indexOf('?');
        if (query >= 0) path = path.substring(0, query);
        return path.isEmpty() ? null : path;
    }
}
