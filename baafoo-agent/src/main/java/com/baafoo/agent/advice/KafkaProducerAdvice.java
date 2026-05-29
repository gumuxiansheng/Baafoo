package com.baafoo.agent.advice;

import net.bytebuddy.asm.Advice;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;

/**
 * Byte Buddy Advice for KafkaProducer#send(ProducerRecord, Callback).
 *
 * <p>Intercepts Kafka message production and records or redirects
 * to the Baafoo stub Kafka broker (port 9002).</p>
 *
 * <p>Kafka interception is Beta per PRD v1.5.</p>
 */
public class KafkaProducerAdvice {

    private static final Logger log = LoggerFactory.getLogger(KafkaProducerAdvice.class);

    @Advice.OnMethodEnter
    public static void onSend(
            @Advice.Argument(0) Object record,
            @Advice.Argument(1) Object callback) {

        try {
            if (record == null) return;

            // Extract topic from ProducerRecord
            String topic = extractTopic(record);

            RouteManager.RouteResult result = RouteManager.route(
                    "kafka", "kafka-broker", 0, null,
                    "SEND", topic,
                    Collections.<String, String>emptyMap(),
                    Collections.<String, String>emptyMap(),
                    null);

            if (result.matched) {
                log.debug("Kafka send intercepted: topic={} rule={}", topic, result.rule.getName());
                RoutingContext.set(result);
            }
        } catch (Exception e) {
            log.error("Error in KafkaProducerAdvice: {}", e.getMessage());
        }
    }

    private static String extractTopic(Object record) {
        try {
            return (String) record.getClass().getMethod("topic").invoke(record);
        } catch (Exception e) {
            return "unknown";
        }
    }
}
