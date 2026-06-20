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

import java.util.Map;

/**
 * Byte Buddy Advice for {@code KafkaConsumer} constructors.
 *
 * <p>Intercepts KafkaConsumer construction to replace the
 * {@code bootstrap.servers} property with the Baafoo stub Kafka broker
 * address (port 9002 by default).</p>
 *
 * <p>Before rewriting, it consults the registered Kafka plugin (if any) via the
 * {@link PluginManager} SPI. A plugin may return an {@link InterceptResult#redirect}
 * to override the default stub target.</p>
 *
 * <p><b>CRITICAL</b>: This advice is inlined into KafkaConsumer by ByteBuddy.
 * Do NOT reference any private fields from this class in the advice
 * method — inlined code runs in the target class's context and cannot access
 * private fields of the advice class. The Logger field MUST be public.</p>
 */
public class KafkaConsumerAdvice {

    /** Must be public — inlined code in the target class cannot access private fields. */
    public static final Logger log = LoggerFactory.getLogger(KafkaConsumerAdvice.class);

    /**
     * Intercept KafkaConsumer constructor to replace bootstrap.servers.
     * The first argument is typically a Properties or Map containing the consumer config.
     */
    @Advice.OnMethodEnter
    public static void onConstructor(@Advice.AllArguments Object[] args) {

        try {
            // Only intercept in stub/recording modes
            EnvironmentMode mode = RouteManager.getMode();
            if (mode == EnvironmentMode.PASSTHROUGH) {
                return;
            }

            // Check if there are any Kafka routes in the routing table
            if (!RouteManager.hasProtocolRoutes("kafka")) {
                return;
            }

            String stubHost = GlobalRouteState.SERVER_HOST;
            int stubPort = GlobalRouteState.KAFKA_PORT;

            // Consult the Kafka plugin — it may override the target.
            // Wrapped in its own try so any plugin failure fails closed (uses default).
            String originalServers = null;
            if (args != null && args.length > 0 && args[0] instanceof java.util.Properties) {
                originalServers = ((java.util.Properties) args[0]).getProperty("bootstrap.servers", "unknown");
            } else if (args != null && args.length > 0 && args[0] instanceof Map) {
                Object val = ((Map) args[0]).get("bootstrap.servers");
                originalServers = val != null ? val.toString() : "unknown";
            }

            try {
                PluginManager pm = BaafooAgent.getPluginManager();
                if (pm != null) {
                    AgentPlugin plugin = pm.getPlugin(InterceptTarget.KAFKA);
                    if (plugin != null) {
                        PluginContext ctx = new PluginContext();
                        ctx.setProtocol("kafka");
                        ctx.setHost(extractHost(originalServers));
                        ctx.setPort(extractPort(originalServers));
                        InterceptResult result = plugin.intercept(ctx);
                        if (result != null && result.isRedirect()) {
                            stubHost = result.getRedirectHost();
                            stubPort = result.getRedirectPort();
                            log.info("[Baafoo] KafkaConsumer plugin redirected to {}:{}", stubHost, stubPort);
                        }
                    }
                }
            } catch (Throwable t) {
                log.debug("[Baafoo] KafkaConsumer plugin consult skipped: {}", t.getMessage());
            }

            String newBootstrapServers = stubHost + ":" + stubPort;

            // Find the config argument (Properties or Map) and replace bootstrap.servers
            if (args != null && args.length > 0) {
                Object firstArg = args[0];

                if (firstArg instanceof java.util.Properties) {
                    java.util.Properties props = (java.util.Properties) firstArg;
                    props.setProperty("bootstrap.servers", newBootstrapServers);
                    log.info("[Baafoo] KafkaConsumer bootstrap.servers replaced: {} -> {}", originalServers, newBootstrapServers);

                } else if (firstArg instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> configs = (Map<String, Object>) firstArg;
                    configs.put("bootstrap.servers", newBootstrapServers);
                    log.info("[Baafoo] KafkaConsumer bootstrap.servers replaced: {} -> {}", originalServers, newBootstrapServers);

                }
            }

        } catch (Exception e) {
            log.error("[Baafoo] KafkaConsumerAdvice error: {}", e.getMessage());
            // Fail-closed: let original constructor proceed with real servers
        }
    }

    /** Extract the first host from a comma-separated bootstrap.servers string. */
    static String extractHost(String bootstrapServers) {
        if (bootstrapServers == null || "unknown".equals(bootstrapServers)) return null;
        String first = bootstrapServers.split(",")[0].trim();
        int colon = first.indexOf(':');
        return colon >= 0 ? first.substring(0, colon) : first;
    }

    /** Extract the first port from a comma-separated bootstrap.servers string; -1 if absent. */
    static int extractPort(String bootstrapServers) {
        if (bootstrapServers == null || "unknown".equals(bootstrapServers)) return -1;
        String first = bootstrapServers.split(",")[0].trim();
        int colon = first.indexOf(':');
        if (colon < 0) return -1;
        try {
            return Integer.parseInt(first.substring(colon + 1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
