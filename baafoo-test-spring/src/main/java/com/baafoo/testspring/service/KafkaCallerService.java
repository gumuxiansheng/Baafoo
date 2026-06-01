package com.baafoo.testspring.service;

import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Properties;
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
}
