package com.baafoo.agent.advice;

import com.baafoo.core.config.AgentConfig;
import com.baafoo.agent.BaafooAgent;
import com.baafoo.core.model.EnvironmentMode;
import net.bytebuddy.asm.Advice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Map;

/**
 * Byte Buddy Advice for {@code KafkaProducer} constructors.
 *
 * <p>Intercepts KafkaProducer construction to replace the
 * {@code bootstrap.servers} property with the Baafoo stub Kafka broker
 * address (port 9002 by default).</p>
 *
 * <p>This approach is superior to intercepting send() because it redirects
 * ALL Kafka traffic (produce + metadata requests) to the stub broker,
 * not just individual messages.</p>
 *
 * <p>Supports multiple KafkaProducer constructor overloads:
 * <ul>
 *   <li>{@code KafkaProducer(Properties properties)}</li>
 *   <li>{@code KafkaProducer(Map<String, Object> configs)}</li>
 *   <li>{@code KafkaProducer(Properties properties, Callback callback)} (newer clients)</li>
 * </ul></p>
 *
 * <p>Kafka interception is Beta per PRD v1.5.</p>
 */
public class KafkaProducerAdvice {

    private static final Logger log = LoggerFactory.getLogger(KafkaProducerAdvice.class);

    /**
     * Intercept KafkaProducer constructor to replace bootstrap.servers.
     * The first argument is typically a Properties or Map containing the producer config.
     */
    @Advice.OnMethodEnter
    public static void onConstructor(@Advice.AllArguments Object[] args) {

        try {
            // Only intercept in stub/recording modes
            EnvironmentMode mode = RouteManager.getMode();
            if (mode == EnvironmentMode.PASSTHROUGH) {
                return;
            }

            // Check if Kafka is in the routing table
            RouteManager.RouteResult routeResult = RouteManager.route(
                    "kafka", "kafka-broker", 0, null,
                    null, null,
                    Collections.<String, String>emptyMap(),
                    Collections.<String, String>emptyMap(),
                    null);

            if (!routeResult.matched) {
                return;
            }

            String stubHost = "127.0.0.1";
            int stubPort = 9002;
            String newBootstrapServers = stubHost + ":" + stubPort;

            // Find the config argument (Properties or Map) and replace bootstrap.servers
            if (args != null && args.length > 0) {
                Object firstArg = args[0];

                if (firstArg instanceof java.util.Properties) {
                    java.util.Properties props = (java.util.Properties) firstArg;
                    String originalServers = props.getProperty("bootstrap.servers", "unknown");
                    props.setProperty("bootstrap.servers", newBootstrapServers);
                    log.info("Kafka bootstrap.servers replaced: {} → {} (rule: {})",
                            originalServers, newBootstrapServers, routeResult.rule.getName());

                } else if (firstArg instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> configs = (Map<String, Object>) firstArg;
                    Object originalServers = configs.get("bootstrap.servers");
                    configs.put("bootstrap.servers", newBootstrapServers);
                    log.info("Kafka bootstrap.servers replaced: {} → {} (rule: {})",
                            originalServers, newBootstrapServers, routeResult.rule.getName());

                } else {
                    log.debug("KafkaProducer constructor first argument is not Properties/Map: {}",
                            firstArg != null ? firstArg.getClass().getName() : "null");
                }
            }

            RoutingContext.set(routeResult);

        } catch (Exception e) {
            log.error("Error in KafkaProducerAdvice: {}", e.getMessage());
            // Fail-closed: let original constructor proceed with real servers
        }
    }
}
