package com.baafoo.testapp.caller;

import com.baafoo.testapp.BaafooTestApp;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.time.Duration;
import java.util.Collections;
import java.util.Properties;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public class KafkaCaller implements BaafooTestApp.Caller {

    private static final String BOOTSTRAP_SERVERS = "kafka-broker:9092";
    private static final String TOPIC = "baafoo-test-topic";

    @Override
    public String name() {
        return "Kafka 外调测试 (目标: " + BOOTSTRAP_SERVERS + ")";
    }

    @Override
    public void run() throws Exception {
        testProduce();
        testProduceWithKey();
        testProduceWithHeaders();
        testConsume();
    }

    private Properties createProducerConfig() {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000);
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10000);
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5000);
        return props;
    }

    private Properties createConsumerConfig() {
        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, BOOTSTRAP_SERVERS);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "baafoo-test-consumer-group");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000);
        props.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 10000);
        props.put(ConsumerConfig.MAX_POLL_RECORDS_CONFIG, 10);
        return props;
    }

    private void testProduce() throws Exception {
        System.out.println("  [发送消息] topic=" + TOPIC);
        KafkaProducer<String, String> producer = null;
        try {
            producer = new KafkaProducer<String, String>(createProducerConfig());
            System.out.println("    Producer 创建成功");

            String bootstrapServers = (String) createProducerConfig().get(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG);
            System.out.println("    bootstrap.servers: " + bootstrapServers);

            boolean redirected = bootstrapServers.contains("9002") || bootstrapServers.contains("127.0.0.1");
            System.out.println("    挡板拦截: " + (redirected ? "✓ 是 (bootstrap.servers 已被替换)" : "✗ 否"));

            ProducerRecord<String, String> record = new ProducerRecord<String, String>(
                    TOPIC, "hello-baafoo-kafka-test");
            Future<RecordMetadata> future = producer.send(record);
            RecordMetadata metadata = future.get(5, TimeUnit.SECONDS);
            System.out.println("    发送成功: partition=" + metadata.partition()
                    + " offset=" + metadata.offset());
        } catch (Exception e) {
            System.out.println("    发送失败: " + e.getMessage());
            System.out.println("    (无 Agent 时连接失败属正常行为)");
        } finally {
            if (producer != null) producer.close();
        }
        System.out.println();
    }

    private void testProduceWithKey() throws Exception {
        System.out.println("  [发送消息+Key] topic=" + TOPIC);
        KafkaProducer<String, String> producer = null;
        try {
            producer = new KafkaProducer<String, String>(createProducerConfig());

            ProducerRecord<String, String> record = new ProducerRecord<String, String>(
                    TOPIC, "test-key", "hello-baafoo-kafka-with-key");
            Future<RecordMetadata> future = producer.send(record);
            RecordMetadata metadata = future.get(5, TimeUnit.SECONDS);
            System.out.println("    发送成功: key=test-key partition=" + metadata.partition()
                    + " offset=" + metadata.offset());
        } catch (Exception e) {
            System.out.println("    发送失败: " + e.getMessage());
            System.out.println("    (无 Agent 时连接失败属正常行为)");
        } finally {
            if (producer != null) producer.close();
        }
        System.out.println();
    }

    private void testProduceWithHeaders() throws Exception {
        System.out.println("  [发送消息+Headers] topic=" + TOPIC);
        KafkaProducer<String, String> producer = null;
        try {
            producer = new KafkaProducer<String, String>(createProducerConfig());

            ProducerRecord<String, String> record = new ProducerRecord<String, String>(
                    TOPIC, "test-key-2", "hello-baafoo-kafka-with-headers");
            record.headers().add("X-Baafoo-Test", "true".getBytes("UTF-8"));
            record.headers().add("X-Source", "baafoo-test-app".getBytes("UTF-8"));
            Future<RecordMetadata> future = producer.send(record);
            RecordMetadata metadata = future.get(5, TimeUnit.SECONDS);
            System.out.println("    发送成功: partition=" + metadata.partition()
                    + " offset=" + metadata.offset());
        } catch (Exception e) {
            System.out.println("    发送失败: " + e.getMessage());
            System.out.println("    (无 Agent 时连接失败属正常行为)");
        } finally {
            if (producer != null) producer.close();
        }
        System.out.println();
    }

    private void testConsume() throws Exception {
        System.out.println("  [消费消息] topic=" + TOPIC);
        KafkaConsumer<String, String> consumer = null;
        try {
            consumer = new KafkaConsumer<String, String>(createConsumerConfig());
            System.out.println("    Consumer 创建成功");

            String bootstrapServers = (String) createConsumerConfig().get(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG);
            System.out.println("    bootstrap.servers: " + bootstrapServers);

            boolean redirected = bootstrapServers.contains("9002") || bootstrapServers.contains("127.0.0.1");
            System.out.println("    挡板拦截: " + (redirected ? "✓ 是 (bootstrap.servers 已被替换)" : "✗ 否"));

            consumer.subscribe(Collections.singletonList(TOPIC));
            System.out.println("    已订阅 topic: " + TOPIC);

            ConsumerRecords<String, String> records = consumer.poll(Duration.ofSeconds(5));
            if (records.isEmpty()) {
                System.out.println("    未收到消息 (5秒超时)");
            } else {
                for (ConsumerRecord<String, String> record : records) {
                    System.out.println("    收到消息: key=" + record.key()
                            + " value=" + record.value()
                            + " partition=" + record.partition()
                            + " offset=" + record.offset());
                }
                System.out.println("    共收到 " + records.count() + " 条消息");
            }
        } catch (Exception e) {
            System.out.println("    消费失败: " + e.getMessage());
            System.out.println("    (无 Agent 时连接失败属正常行为)");
        } finally {
            if (consumer != null) consumer.close();
        }
        System.out.println();
    }
}
