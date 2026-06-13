package com.baafoo.agent.advice;

import com.baafoo.agent.GlobalRouteState;
import com.baafoo.core.model.EnvironmentMode;
import net.bytebuddy.asm.Advice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.Map;

/**
 * Byte Buddy Advice for {@code KafkaConsumer} constructors.
 *
 * <p>Intercepts KafkaConsumer construction to replace the
 * {@code bootstrap.servers} property with the Baafoo stub Kafka broker
 * address (port 9002 by default).</p>
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

            String stubHost = GlobalRouteState.SERVER_HOST;
            int stubPort = GlobalRouteState.KAFKA_PORT;
            String newBootstrapServers = stubHost + ":" + stubPort;

            // Find the config argument (Properties or Map) and replace bootstrap.servers
            if (args != null && args.length > 0) {
                Object firstArg = args[0];

                if (firstArg instanceof java.util.Properties) {
                    java.util.Properties props = (java.util.Properties) firstArg;
                    String originalServers = props.getProperty("bootstrap.servers", "unknown");
                    props.setProperty("bootstrap.servers", newBootstrapServers);
                    log.info("[Baafoo] KafkaConsumer bootstrap.servers replaced: {} -> {}", originalServers, newBootstrapServers);

                } else if (firstArg instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> configs = (Map<String, Object>) firstArg;
                    Object originalServers = configs.get("bootstrap.servers");
                    configs.put("bootstrap.servers", newBootstrapServers);
                    log.info("[Baafoo] KafkaConsumer bootstrap.servers replaced: {} -> {}", originalServers, newBootstrapServers);

                }
            }

            RoutingContext.set(routeResult);

        } catch (Exception e) {
            log.error("[Baafoo] KafkaConsumerAdvice error: {}", e.getMessage());
            // Fail-closed: let original constructor proceed with real servers
        }
    }
}
