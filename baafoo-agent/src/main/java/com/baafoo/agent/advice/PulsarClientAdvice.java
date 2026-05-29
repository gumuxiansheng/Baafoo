package com.baafoo.agent.advice;

import net.bytebuddy.asm.Advice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;

/**
 * Byte Buddy Advice for PulsarClientImpl#createProducer / subscribe.
 *
 * <p>Intercepts Pulsar producer/consumer creation to redirect to
 * Baafoo stub Pulsar broker (port 9003).</p>
 *
 * <p>Per plugin-arch-advice.md, the actual Pulsar protocol handling
 * is delegated to the Pulsar Plugin loaded by a separate ClassLoader
 * to avoid SDK dependency conflicts.</p>
 */
public class PulsarClientAdvice {

    private static final Logger log = LoggerFactory.getLogger(PulsarClientAdvice.class);

    @Advice.OnMethodEnter
    public static void onProducerCreate() {
        interceptPulsarCall("CREATE_PRODUCER");
    }

    @Advice.OnMethodEnter
    public static void onSubscribe() {
        interceptPulsarCall("SUBSCRIBE");
    }

    private static void interceptPulsarCall(String operation) {
        try {
            RouteManager.RouteResult result = RouteManager.route(
                    "pulsar", "pulsar-broker", 0, null,
                    operation, null,
                    Collections.<String, String>emptyMap(),
                    Collections.<String, String>emptyMap(),
                    null);

            if (result.matched) {
                log.debug("Pulsar {} intercepted: rule={}", operation, result.rule.getName());
                RoutingContext.set(result);
            }
        } catch (Exception e) {
            log.error("Error in PulsarClientAdvice: {}", e.getMessage());
        }
    }
}
