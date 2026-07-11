package com.baafoo.testspring.service;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.charset.Charset;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

@Service
public class KafkaCallerService {

    private static final Logger log = LoggerFactory.getLogger(KafkaCallerService.class);

    public Map<String, Object> sendMessage(String bootstrapServers, String topic, String message) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000);
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10000);
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5000);

        result.put("bootstrapServers", bootstrapServers);
        result.put("topic", topic);

        KafkaProducer<String, String> producer = null;
        try {
            producer = new KafkaProducer<String, String>(props);
            ProducerRecord<String, String> record = new ProducerRecord<String, String>(topic, message);
            Future<RecordMetadata> future = producer.send(record);
            RecordMetadata metadata = future.get(5, TimeUnit.SECONDS);
            result.put("success", true);
            result.put("partition", metadata.partition());
            result.put("offset", metadata.offset());
            log.info("Kafka message sent: topic={}, partition={}, offset={}",
                    topic, metadata.partition(), metadata.offset());
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            log.warn("Kafka send failed: {}", e.getMessage());
        } finally {
            if (producer != null) producer.close();
        }
        return result;
    }

    public Map<String, Object> consumeMessage(String bootstrapServers, String topic) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "baafoo-test-consumer-group");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000);
        props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 10000);
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 10);

        result.put("bootstrapServers", bootstrapServers);
        result.put("topic", topic);

        KafkaConsumer<String, String> consumer = null;
        try {
            consumer = new KafkaConsumer<String, String>(props);
            consumer.subscribe(Collections.singletonList(topic));
            ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(5));
            List<Map<String, Object>> messages = new ArrayList<Map<String, Object>>();
            for (ConsumerRecord<String, String> record : records) {
                Map<String, Object> msg = new LinkedHashMap<String, Object>();
                msg.put("key", record.key());
                msg.put("value", record.value());
                msg.put("partition", record.partition());
                msg.put("offset", record.offset());
                messages.add(msg);
            }
            result.put("success", true);
            result.put("count", messages.size());
            result.put("messages", messages);
            log.info("Kafka consumed: topic={}, count={}", topic, messages.size());
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            log.warn("Kafka consume failed: {}", e.getMessage());
        } finally {
            if (consumer != null) consumer.close();
        }
        return result;
    }

    /**
     * Send a Kafka message with the request bytes encoded using the specified
     * charset (e.g. GBK). Used by the CH (multi-charset) full-chain test
     * cases to verify that:
     * <ol>
     *   <li>The server decodes the produce request bytes using
     *       {@code Rule.requestCharset} (so template variables like
     *       {@code {{request.body}}} render correctly);</li>
     *   <li>The stub response body is re-encoded using
     *       {@code ResponseEntry.charset} before being stored in the
     *       MockBroker's message store.</li>
     * </ol>
     *
     * <p>Uses {@link ByteArraySerializer} so the raw charset-encoded bytes
     * reach the wire without Kafka's {@code StringSerializer} re-encoding
     * them as UTF-8.</p>
     *
     * @param bootstrapServers Kafka bootstrap.servers (redirected by agent)
     * @param topic            target topic name
     * @param message          request text (will be encoded using {@code charset})
     * @param charset          character set name (e.g. "GBK", "GB2312", "Big5")
     */
    public Map<String, Object> sendMessageWithCharset(String bootstrapServers, String topic,
                                                       String message, String charset) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        // ByteArraySerializer keeps the raw charset-encoded bytes intact on
        // the wire — StringSerializer would force UTF-8 and defeat the test.
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000);
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10000);
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5000);

        result.put("bootstrapServers", bootstrapServers);
        result.put("topic", topic);
        result.put("charset", charset);

        KafkaProducer<byte[], byte[]> producer = null;
        try {
            Charset cs = Charset.forName(charset);
            byte[] valueBytes = message.getBytes(cs);
            producer = new KafkaProducer<byte[], byte[]>(props);
            ProducerRecord<byte[], byte[]> record = new ProducerRecord<byte[], byte[]>(topic, valueBytes);
            Future<RecordMetadata> future = producer.send(record);
            RecordMetadata metadata = future.get(5, TimeUnit.SECONDS);
            result.put("success", true);
            result.put("partition", metadata.partition());
            result.put("offset", metadata.offset());
            log.info("Kafka message sent with charset={}: topic={}, partition={}, offset={}",
                    charset, topic, metadata.partition(), metadata.offset());
        } catch (Exception e) {
            result.put("success", false);
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            log.warn("Kafka send with charset={} failed: {}", charset, e.getMessage());
        } finally {
            if (producer != null) producer.close();
        }
        return result;
    }
}
