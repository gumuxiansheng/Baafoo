package com.baafoo.agent.advice;

import com.baafoo.core.model.EnvironmentMode;
import net.bytebuddy.asm.Advice;

import java.util.Collections;
import java.util.Map;

/**
 * Byte Buddy Advice for {@code KafkaProducer} constructors.
 *
 * <p>Intercepts KafkaProducer construction to replace the
 * {@code bootstrap.servers} property with the Baafoo stub Kafka broker
 * address (port 9002 by default).</p>
 *
 * <p><b>CRITICAL</b>: This advice is inlined into KafkaProducer by ByteBuddy.
 * Do NOT reference any fields from this class (including log) in the advice
 * method — inlined code runs in the target class's context and cannot access
 * private fields of the advice class. Use System.out for debug output.</p>
 */
public class KafkaProducerAdvice {

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
                    java.lang.System.out.println("[Baafoo] Kafka bootstrap.servers replaced: " + originalServers + " -> " + newBootstrapServers);

                } else if (firstArg instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> configs = (Map<String, Object>) firstArg;
                    Object originalServers = configs.get("bootstrap.servers");
                    configs.put("bootstrap.servers", newBootstrapServers);
                    java.lang.System.out.println("[Baafoo] Kafka bootstrap.servers replaced: " + originalServers + " -> " + newBootstrapServers);

                }
            }

            RoutingContext.set(routeResult);

        } catch (Exception e) {
            java.lang.System.out.println("[Baafoo] KafkaProducerAdvice error: " + e.getMessage());
            // Fail-closed: let original constructor proceed with real servers
        }
    }
}
