package com.baafoo.testspring.service;

import org.apache.pulsar.client.api.MessageId;
import org.apache.pulsar.client.api.Producer;
import org.apache.pulsar.client.api.PulsarClient;
import org.apache.pulsar.client.api.Schema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
public class PulsarCallerService {

    private static final Logger log = LoggerFactory.getLogger(PulsarCallerService.class);

    public Map<String, Object> sendMessage(String serviceUrl, String topic, String message) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("serviceUrl", serviceUrl);
        result.put("topic", topic);

        PulsarClient client = null;
        try {
            client = PulsarClient.builder()
                    .serviceUrl(serviceUrl)
                    .connectionTimeout(2, TimeUnit.SECONDS)
                    .operationTimeout(2, TimeUnit.SECONDS)
                    .build();
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
}
