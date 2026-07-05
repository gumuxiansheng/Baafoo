package com.baafoo.testspring.service;

import org.apache.pulsar.client.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class PulsarCallerService {

    private static final Logger log = LoggerFactory.getLogger(PulsarCallerService.class);

    /**
     * Create a PulsarClient for the given service URL.
     *
     * <p>Extracted as a seam so unit/integration tests can override this method
     * (e.g. via {@code @SpyBean} + {@code doReturn(mockClient).when(spy).createPulsarClient(...)})
     * to avoid real network I/O in CICD environments where no Pulsar broker is
     * available. The default implementation builds a real client with short
     * timeouts suitable for the production use case of this test app.</p>
     */
    public PulsarClient createPulsarClient(String serviceUrl) throws PulsarClientException {
        return PulsarClient.builder()
                .serviceUrl(serviceUrl)
                .connectionTimeout(2, TimeUnit.SECONDS)
                .operationTimeout(2, TimeUnit.SECONDS)
                .build();
    }

    public Map<String, Object> sendMessage(String serviceUrl, String topic, String message) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("serviceUrl", serviceUrl);
        result.put("topic", topic);

        PulsarClient client = null;
        try {
            client = createPulsarClient(serviceUrl);
            result.put("clientCreated", true);

            Producer<String> producer = client.newProducer(Schema.STRING)
                    .topic(topic)
                    .create();

            MessageId msgId = producer.send(message);
            result.put("success", true);
            result.put("messageId", msgId.toString());
            log.info("Pulsar message sent: topic={}, messageId={}", topic, msgId);

            producer.close();
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            log.warn("Pulsar send failed: {}", e.getMessage());
        } finally {
            if (client != null) try { client.close(); } catch (Exception ignored) {}
        }
        return result;
    }

    public Map<String, Object> consumeMessage(String serviceUrl, String topic) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("serviceUrl", serviceUrl);
        result.put("topic", topic);

        PulsarClient client = null;
        try {
            client = createPulsarClient(serviceUrl);

            Consumer<String> consumer = client.newConsumer(Schema.STRING)
                    .topic(topic)
                    .subscriptionName("baafoo-test-subscription")
                    .subscribe();

            Message<String> msg = consumer.receive(5, TimeUnit.SECONDS);
            if (msg == null) {
                result.put("success", true);
                result.put("count", 0);
                result.put("messages", java.util.Collections.emptyList());
            } else {
                Map<String, Object> msgMap = new LinkedHashMap<String, Object>();
                msgMap.put("key", msg.getKey());
                msgMap.put("value", msg.getValue());
                msgMap.put("messageId", msg.getMessageId().toString());
                consumer.acknowledge(msg);
                result.put("success", true);
                result.put("count", 1);
                result.put("messages", java.util.Collections.singletonList(msgMap));
            }
            consumer.close();
            log.info("Pulsar consumed: topic={}", topic);
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            log.warn("Pulsar consume failed: {}", e.getMessage());
        } finally {
            if (client != null) try { client.close(); } catch (Exception ignored) {}
        }
        return result;
    }
}
