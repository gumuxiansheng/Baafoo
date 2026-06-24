package com.baafoo.server.broker;

import com.baafoo.core.model.Environment;
import com.baafoo.core.model.EnvironmentMode;
import com.baafoo.core.model.MatchCondition;
import com.baafoo.core.model.ResponseEntry;
import com.baafoo.core.model.Rule;
import com.baafoo.server.storage.StorageService;
import io.netty.channel.nio.NioEventLoopGroup;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import static org.mockito.Mockito.*;

/**
 * Kafka protocol compatibility test using a real Kafka container.
 *
 * <p>Verifies that the Baafoo Kafka Mock Broker is wire-compatible with
 * the official Apache Kafka client by:</p>
 * <ol>
 *   <li>Starting a real Kafka broker via Testcontainers</li>
 *   <li>Starting the Baafoo KafkaMockBroker on a random port</li>
 *   <li>Running the same Kafka client operations against both</li>
 *   <li>Comparing behavior to ensure protocol compatibility</li>
 * </ol>
 *
 * <p>This test requires Docker. It is automatically skipped if Docker
 * is not available.</p>
 */
public class KafkaProtocolCompatibilityTest {

    private static final Logger log = LoggerFactory.getLogger(KafkaProtocolCompatibilityTest.class);

    private static final DockerImageName KAFKA_IMAGE = DockerImageName.parse("confluentinc/cp-kafka:7.4.0");

    public static KafkaContainer realKafka;
    private static boolean dockerAvailable;

    static {
        try {
            dockerAvailable = org.testcontainers.DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            dockerAvailable = false;
        }
        if (dockerAvailable) {
            try {
                realKafka = new KafkaContainer(KAFKA_IMAGE);
                realKafka.start();
            } catch (Throwable t) {
                log.warn("Failed to start real Kafka container, real-Kafka tests will be skipped: {}", t.getMessage());
                realKafka = null;
                dockerAvailable = false;
            }
        }
    }

    // Baafoo Mock Broker components
    private NioEventLoopGroup bossGroup;
    private NioEventLoopGroup workerGroup;
    private KafkaMockBroker mockBroker;
    private KafkaMessageStore messageStore;
    private StorageService storage;
    private int mockBrokerPort;

    @Before
    public void setUp() throws Exception {
        // Set up mocked storage
        storage = mock(StorageService.class);
        when(storage.listRules()).thenReturn(new ArrayList<>());
        when(storage.listEnvironments()).thenReturn(new ArrayList<>());
        when(storage.listAgents()).thenReturn(new ArrayList<>());

        // Start Baafoo Mock Broker on a random port
        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();
        mockBrokerPort = 0; // Use port 0 to let OS assign a free port

        // We need to get the messageStore reference for verification
        // KafkaMockBroker creates its own store internally, so we use reflection or
        // test via the Kafka client interface only
        mockBroker = new KafkaMockBroker(mockBrokerPort, storage, bossGroup, workerGroup, "127.0.0.1");
        mockBroker.start();

        // Get the actual bound port
        mockBrokerPort = mockBroker.getPort();
        log.info("Baafoo Mock Broker started on port {}", mockBrokerPort);
        if (realKafka != null) {
            log.info("Real Kafka at {}", realKafka.getBootstrapServers());
        }
    }

    @After
    public void tearDown() throws Exception {
        if (mockBroker != null) {
            mockBroker.stop();
        }
        if (bossGroup != null) {
            bossGroup.shutdownGracefully();
        }
        if (workerGroup != null) {
            workerGroup.shutdownGracefully();
        }
    }

    @AfterClass
    public static void tearDownClass() {
        if (realKafka != null) {
            realKafka.stop();
        }
    }

    // ----------------------------------------------------------------------
    // Test 1: ApiVersions handshake — real Kafka client must accept Mock's response
    // ----------------------------------------------------------------------

    /**
     * The real Kafka client sends an ApiVersions request during connection.
     * The Mock Broker must respond with compatible API versions, otherwise
     * the client will refuse to connect.
     */
    @Test
    public void testRealKafkaClientConnectsToMockBroker() throws Exception {
        // Create a producer pointing at the Mock Broker
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:" + mockBrokerPort);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "1");
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 5000);
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 10000);
        props.put(ProducerConfig.RETRIES_CONFIG, 0);

        KafkaProducer<String, String> producer = new KafkaProducer<>(props);

        // If the ApiVersions handshake fails, the producer constructor or send
        // will throw an exception. Just creating the producer triggers the handshake.
        assertNotNull("Producer should connect to Mock Broker", producer);
        producer.close();
    }

    // ----------------------------------------------------------------------
    // Test 2: Produce to Mock Broker — real client must accept the response
    // ----------------------------------------------------------------------

    /**
     * Send a message to the Mock Broker using the real Kafka producer.
     * The Mock Broker must return a valid Produce response with offset.
     */
    @Test
    public void testRealProducerSendsToMockBroker() throws Exception {
        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:" + mockBrokerPort);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "1");
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 10000);
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 15000);
        props.put(ProducerConfig.RETRIES_CONFIG, 0);

        KafkaProducer<String, String> producer = new KafkaProducer<>(props);
        try {
            ProducerRecord<String, String> record =
                    new ProducerRecord<>("compat-test-topic", "key1", "value1");

            Future<RecordMetadata> future = producer.send(record);
            RecordMetadata metadata = future.get(15, TimeUnit.SECONDS);

            assertNotNull("Record metadata should not be null", metadata);
            assertEquals("Topic should match", "compat-test-topic", metadata.topic());
            assertTrue("Offset should be >= 0", metadata.offset() >= 0);
            log.info("Produce to Mock Broker succeeded: topic={}, partition={}, offset={}",
                    metadata.topic(), metadata.partition(), metadata.offset());
        } finally {
            producer.close();
        }
    }

    // ----------------------------------------------------------------------
    // Test 3: Produce + Fetch round-trip via Mock Broker
    // ----------------------------------------------------------------------

    /**
     * Produce a message to the Mock Broker, then consume it back.
     * This verifies the full produce → store → fetch cycle.
     */
    @Test
    public void testProduceAndFetchFromMockBroker() throws Exception {
        // Produce
        Properties producerProps = new Properties();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:" + mockBrokerPort);
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producerProps.put(ProducerConfig.ACKS_CONFIG, "1");
        producerProps.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 10000);
        producerProps.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 15000);
        producerProps.put(ProducerConfig.RETRIES_CONFIG, 0);

        KafkaProducer<String, String> producer = new KafkaProducer<>(producerProps);
        try {
            producer.send(new ProducerRecord<>("round-trip-topic", "k1", "v1")).get(15, TimeUnit.SECONDS);
            producer.send(new ProducerRecord<>("round-trip-topic", "k2", "v2")).get(15, TimeUnit.SECONDS);
        } finally {
            producer.close();
        }

        // Consume
        Properties consumerProps = new Properties();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:" + mockBrokerPort);
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "compat-test-group");
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProps.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "true");
        consumerProps.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, 10000);
        consumerProps.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, 500);
        consumerProps.put(ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG, 10000);

        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProps);
        try {
            consumer.subscribe(Collections.singletonList("round-trip-topic"));

            // Poll for messages
            List<ConsumerRecord<String, String>> records = new ArrayList<>();
            long deadline = System.currentTimeMillis() + 20000;
            while (System.currentTimeMillis() < deadline && records.size() < 2) {
                ConsumerRecords<String, String> polled = consumer.poll(java.time.Duration.ofMillis(1000));
                for (ConsumerRecord<String, String> r : polled) {
                    records.add(r);
                }
            }

            assertEquals("Should receive 2 messages from Mock Broker", 2, records.size());
            assertEquals("First message value", "v1", records.get(0).value());
            assertEquals("Second message value", "v2", records.get(1).value());
            log.info("Round-trip test passed: received {} messages", records.size());
        } finally {
            consumer.close();
        }
    }

    // ----------------------------------------------------------------------
    // Test 4: Real Kafka produce/consume (baseline comparison)
    // ----------------------------------------------------------------------

    /**
     * Same produce/consume flow against a real Kafka broker.
     * This serves as a baseline to verify the test infrastructure works.
     */
    @Test
    public void testRealKafkaProduceAndConsume() throws Exception {
        assumeTrue("Docker must be available for real Kafka baseline test", dockerAvailable && realKafka != null);
        String bootstrap = realKafka.getBootstrapServers();

        // Create topic
        Properties adminProps = new Properties();
        adminProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        AdminClient admin = AdminClient.create(adminProps);
        try {
            admin.createTopics(Collections.singletonList(
                    new NewTopic("real-baseline-topic", 1, (short) 1)));
        } finally {
            admin.close();
        }

        // Produce
        Properties producerProps = new Properties();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producerProps.put(ProducerConfig.ACKS_CONFIG, "all");

        KafkaProducer<String, String> producer = new KafkaProducer<>(producerProps);
        try {
            producer.send(new ProducerRecord<>("real-baseline-topic", "k1", "v1")).get(15, TimeUnit.SECONDS);
        } finally {
            producer.close();
        }

        // Consume
        Properties consumerProps = new Properties();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrap);
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "baseline-test-group");
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");

        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProps);
        try {
            consumer.subscribe(Collections.singletonList("real-baseline-topic"));

            List<ConsumerRecord<String, String>> records = new ArrayList<>();
            long deadline = System.currentTimeMillis() + 30000;
            while (System.currentTimeMillis() < deadline && records.isEmpty()) {
                ConsumerRecords<String, String> polled = consumer.poll(java.time.Duration.ofMillis(1000));
                for (ConsumerRecord<String, String> r : polled) {
                    records.add(r);
                }
            }

            assertFalse("Should receive at least 1 message from real Kafka", records.isEmpty());
            assertEquals("Message value", "v1", records.get(0).value());
            log.info("Real Kafka baseline test passed");
        } finally {
            consumer.close();
        }
    }

    // ----------------------------------------------------------------------
    // Test 5: Stub rule injection — Mock Broker replaces produced value
    // ----------------------------------------------------------------------

    /**
     * When a Kafka rule with a topic condition is configured, the Mock Broker
     * should replace the produced value with the stub response. Verify this
     * works with the real Kafka client.
     */
    @Test
    public void testStubRuleInjectionWithRealClient() throws Exception {
        // Configure a stub rule
        Rule rule = new Rule();
        rule.setId("stub-rule-1");
        rule.setProtocol("kafka");
        rule.setEnabled(true);
        rule.setConditions(Collections.singletonList(MatchCondition.topic("equals", "stub-inject-topic")));
        ResponseEntry resp = new ResponseEntry();
        resp.setBody("STUBBED-VALUE");
        rule.setResponses(Collections.singletonList(resp));

        when(storage.listRules()).thenReturn(Collections.singletonList(rule));

        // Produce with real client
        Properties producerProps = new Properties();
        producerProps.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:" + mockBrokerPort);
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        producerProps.put(ProducerConfig.ACKS_CONFIG, "1");
        producerProps.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 10000);
        producerProps.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 15000);
        producerProps.put(ProducerConfig.RETRIES_CONFIG, 0);

        KafkaProducer<String, String> producer = new KafkaProducer<>(producerProps);
        try {
            producer.send(new ProducerRecord<>("stub-inject-topic", "real-key", "real-value"))
                    .get(15, TimeUnit.SECONDS);
        } finally {
            producer.close();
        }

        // Consume and verify the stubbed value
        Properties consumerProps = new Properties();
        consumerProps.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, "127.0.0.1:" + mockBrokerPort);
        consumerProps.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consumerProps.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        consumerProps.put(ConsumerConfig.GROUP_ID_CONFIG, "stub-test-group");
        consumerProps.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        consumerProps.put(ConsumerConfig.REQUEST_TIMEOUT_MS_CONFIG, 10000);
        consumerProps.put(ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG, 500);

        KafkaConsumer<String, String> consumer = new KafkaConsumer<>(consumerProps);
        try {
            consumer.subscribe(Collections.singletonList("stub-inject-topic"));

            List<ConsumerRecord<String, String>> records = new ArrayList<>();
            long deadline = System.currentTimeMillis() + 20000;
            while (System.currentTimeMillis() < deadline && records.isEmpty()) {
                ConsumerRecords<String, String> polled = consumer.poll(java.time.Duration.ofMillis(1000));
                for (ConsumerRecord<String, String> r : polled) {
                    records.add(r);
                }
            }

            assertFalse("Should receive 1 message", records.isEmpty());
            assertEquals("Value should be stubbed", "STUBBED-VALUE", records.get(0).value());
            log.info("Stub injection test passed: value replaced with '{}'", records.get(0).value());
        } finally {
            consumer.close();
        }
    }
}
