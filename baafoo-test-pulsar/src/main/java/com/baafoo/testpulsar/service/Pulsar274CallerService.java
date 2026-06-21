package com.baafoo.testpulsar.service;

import org.apache.pulsar.client.api.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Pulsar 2.7.4 client test service.
 *
 * <p>Mirrors {@code baafoo.testspring.service.PulsarCallerService} but is wired to
 * pulsar-client <b>2.7.4</b>. Each method builds its own short-lived
 * {@link PulsarClient} (matching the test-spring pattern) and closes it in a
 * {@code finally} block so every REST call is self-contained.</p>
 *
 * <p>It additionally exercises two 2.7.4 features the original test-spring
 * service does not:</p>
 * <ul>
 *   <li>{@link Schema#JSON(Class)} — produces a JSON-schema framed payload (the mock
 *       broker stores raw bytes, so this still works against 9003).</li>
 *   <li>Batching — {@code enableBatching(true).batchingMaxMessages(n)} with
 *       async {@code sendAsync}, exercising the 2.7.4 batch frame.</li>
 * </ul>
 *
 * <p>The default {@code serviceUrl}/{@code topic} match {@code baafoo-test-spring}
 * so the Agent {@code PulsarClientAdvice} rewrites both to the same
 * {@code pulsar://&lt;host&gt;:9003} target.</p>
 */
@Service
public class Pulsar274CallerService {

    private static final Logger log = LoggerFactory.getLogger(Pulsar274CallerService.class);

    /** Subscription name reused by {@link #consumeMessage} (mirrors test-spring). */
    private static final String SUBSCRIPTION = "baafoo-test-subscription";

    public Map<String, Object> sendMessage(String serviceUrl, String topic, String message) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("serviceUrl", serviceUrl);
        result.put("topic", topic);

        PulsarClient client = null;
        try {
            client = newClient(serviceUrl);
            Producer<String> producer = client.newProducer(Schema.STRING)
                    .topic(topic)
                    .create();

            MessageId msgId = producer.send(message);
            result.put("success", true);
            result.put("messageId", msgId.toString());
            log.info("Pulsar 2.7.4 message sent: topic={}, messageId={}", topic, msgId);

            producer.close();
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            log.warn("Pulsar 2.7.4 send failed: {}", e.getMessage());
        } finally {
            closeQuietly(client);
        }
        return result;
    }

    public Map<String, Object> consumeMessage(String serviceUrl, String topic) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("serviceUrl", serviceUrl);
        result.put("topic", topic);

        PulsarClient client = null;
        try {
            client = newClient(serviceUrl);
            Consumer<String> consumer = client.newConsumer(Schema.STRING)
                    .topic(topic)
                    .subscriptionName(SUBSCRIPTION)
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
            log.info("Pulsar 2.7.4 consumed: topic={}", topic);
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            log.warn("Pulsar 2.7.4 consume failed: {}", e.getMessage());
        } finally {
            closeQuietly(client);
        }
        return result;
    }

    public Map<String, Object> sendJsonSchema(String serviceUrl, String topic, String name, int value) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("serviceUrl", serviceUrl);
        result.put("topic", topic);

        PulsarClient client = null;
        try {
            client = newClient(serviceUrl);
            Producer<SamplePojo> producer = client.newProducer(Schema.JSON(SamplePojo.class))
                    .topic(topic)
                    .create();

            SamplePojo payload = new SamplePojo(name, value);
            MessageId msgId = producer.send(payload);
            result.put("success", true);
            result.put("messageId", msgId.toString());
            result.put("schema", "JSON(SamplePojo)");
            log.info("Pulsar 2.7.4 JSON-schema message sent: topic={}, messageId={}", topic, msgId);

            producer.close();
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            log.warn("Pulsar 2.7.4 JSON send failed: {}", e.getMessage());
        } finally {
            closeQuietly(client);
        }
        return result;
    }

    public Map<String, Object> sendBatch(String serviceUrl, String topic, int count) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("serviceUrl", serviceUrl);
        result.put("topic", topic);
        result.put("batchSize", count);

        PulsarClient client = null;
        try {
            client = newClient(serviceUrl);
            Producer<String> producer = client.newProducer(Schema.STRING)
                    .topic(topic)
                    .enableBatching(true)
                    .batchingMaxMessages(count)
                    .batchingMaxPublishDelay(10, TimeUnit.MILLISECONDS)
                    .create();

            List<Future<MessageId>> futures = new ArrayList<Future<MessageId>>();
            for (int i = 0; i < count; i++) {
                futures.add(producer.sendAsync("batch-" + i));
            }
            producer.flush();

            List<String> ids = new ArrayList<String>();
            for (Future<MessageId> f : futures) {
                ids.add(f.get(5, TimeUnit.SECONDS).toString());
            }
            result.put("success", true);
            result.put("messageIds", ids);
            log.info("Pulsar 2.7.4 batch sent: topic={}, count={}", topic, ids.size());

            producer.close();
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            log.warn("Pulsar 2.7.4 batch send failed: {}", e.getMessage());
        } finally {
            closeQuietly(client);
        }
        return result;
    }

    /**
     * Report which pulsar-client version backs this service. Useful for sanity
     * checking that the test app really runs on 2.7.4 (vs the 2.10.4 in
     * {@code baafoo-test-spring}).
     */
    public Map<String, Object> info() {
        Map<String, Object> info = new LinkedHashMap<String, Object>();
        info.put("module", "baafoo-test-pulsar");
        info.put("pulsarClientVersion", readPulsarVersion());
        info.put("expectedVersion", "2.7.4");
        info.put("note", "TDMQ for Pulsar is based on Apache Pulsar 2.7.4; "
                + "this module reproduces that exact wire protocol.");
        Map<String, Object> redirect = new LinkedHashMap<String, Object>();
        redirect.put("default", "pulsar://${baafoo.server-host}:9003 (PulsarMockBroker)");
        redirect.put("tdmqPlugin", "pulsar://localhost:9005 (tdmq plugin)");
        info.put("redirectTargets", redirect);
        return info;
    }

    /** Build a short-timeout client so unreachable brokers fail fast in tests. */
    private static PulsarClient newClient(String serviceUrl) throws PulsarClientException {
        return PulsarClient.builder()
                .serviceUrl(serviceUrl)
                .connectionTimeout(2, TimeUnit.SECONDS)
                .operationTimeout(2, TimeUnit.SECONDS)
                .build();
    }

    private static void closeQuietly(PulsarClient client) {
        if (client != null) {
            try {
                client.close();
            } catch (Exception ignored) {
            }
        }
    }

    /** Read the pulsar-client version from its package metadata (defensive). */
    private static String readPulsarVersion() {
        Package pkg = PulsarClient.class.getPackage();
        if (pkg != null) {
            String v = pkg.getImplementationVersion();
            if (v != null && v.length() > 0) {
                return v;
            }
        }
        return "unknown";
    }

    /** Simple POJO for {@link Schema#JSON} round-trips. */
    public static class SamplePojo {
        private String name;
        private int value;

        public SamplePojo() {
        }

        public SamplePojo(String name, int value) {
            this.name = name;
            this.value = value;
        }

        public String getName() {
            return name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public int getValue() {
            return value;
        }

        public void setValue(int value) {
            this.value = value;
        }
    }
}
