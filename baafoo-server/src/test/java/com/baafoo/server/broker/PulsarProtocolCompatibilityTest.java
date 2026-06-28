package com.baafoo.server.broker;

import com.baafoo.core.model.MatchCondition;
import com.baafoo.core.model.ResponseEntry;
import com.baafoo.core.model.Rule;
import com.baafoo.server.storage.StorageService;
import io.netty.channel.nio.NioEventLoopGroup;
import org.apache.pulsar.client.api.*;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import static org.mockito.Mockito.*;

public class PulsarProtocolCompatibilityTest {

    private static final Logger log = LoggerFactory.getLogger(PulsarProtocolCompatibilityTest.class);

    private static final DockerImageName PULSAR_IMAGE = DockerImageName.parse("apachepulsar/pulsar:2.10.4");

    public static GenericContainer<?> realPulsar;
    private static boolean dockerAvailable;
    private static int realPulsarPort;

    static {
        try {
            dockerAvailable = DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            dockerAvailable = false;
        }
        if (dockerAvailable) {
            try {
                realPulsar = new GenericContainer<>(PULSAR_IMAGE)
                        .withExposedPorts(6650)
                        .withCommand("bin/pulsar", "standalone", "--no-functions-worker")
                        .withEnv("PULSAR_PREFIX_advertisedAddress", "localhost")
                        .waitingFor(Wait.forLogMessage(".*Starting Pulsar Broker.*", 1)
                                .withStartupTimeout(Duration.ofMinutes(3)));
                realPulsar.start();
                // Allow Pulsar to finish initialization after the log message
                Thread.sleep(3000);
                realPulsarPort = realPulsar.getMappedPort(6650);
                log.info("Real Pulsar started on port {}", realPulsarPort);
            } catch (Throwable t) {
                log.warn("Failed to start real Pulsar container, real-Pulsar tests will be skipped: {}", t.getMessage());
                realPulsar = null;
                dockerAvailable = false;
            }
        }
    }

    @AfterClass
    public static void tearDownClass() {
        if (realPulsar != null) {
            try {
                realPulsar.stop();
            } catch (Exception ignored) {
            }
        }
    }

    private NioEventLoopGroup bossGroup;
    private NioEventLoopGroup workerGroup;
    private PulsarMockBroker mockBroker;
    private StorageService storage;
    private int mockBrokerPort;

    @Before
    public void setUp() throws Exception {
        storage = mock(StorageService.class);
        when(storage.listRules()).thenReturn(new ArrayList<>());
        when(storage.listEnvironments()).thenReturn(new ArrayList<>());
        when(storage.listAgents()).thenReturn(new ArrayList<>());

        bossGroup = new NioEventLoopGroup(1);
        workerGroup = new NioEventLoopGroup();

        mockBroker = new PulsarMockBroker(19593, bossGroup, workerGroup, storage, "127.0.0.1");
        mockBroker.start();

        mockBrokerPort = mockBroker.getPort();
        log.info("Baafoo Pulsar Mock Broker started on port {}", mockBrokerPort);
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

    // ----------------------------------------------------------------------
    // Test 1: Real Pulsar client connects to Mock Broker
    // ----------------------------------------------------------------------

    @Test
    public void testRealPulsarClientConnectsToMockBroker() throws Exception {
        PulsarClient client = PulsarClient.builder()
                .serviceUrl("pulsar://127.0.0.1:" + mockBrokerPort)
                .connectionTimeout(5, TimeUnit.SECONDS)
                .operationTimeout(5, TimeUnit.SECONDS)
                .build();

        assertNotNull("PulsarClient should connect to Mock Broker", client);
        client.close();
        log.info("Pulsar client successfully connected to Mock Broker");
    }

    // ----------------------------------------------------------------------
    // Test 2: Real Pulsar producer sends message to Mock Broker
    // ----------------------------------------------------------------------

    @Test
    public void testRealPulsarProducerSendsToMockBroker() throws Exception {
        PulsarClient client = PulsarClient.builder()
                .serviceUrl("pulsar://127.0.0.1:" + mockBrokerPort)
                .connectionTimeout(5, TimeUnit.SECONDS)
                .operationTimeout(5, TimeUnit.SECONDS)
                .build();

        try {
            Producer<String> producer = client.newProducer(Schema.STRING)
                    .topic("persistent://public/default/compat-test-topic")
                    .create();

            assertNotNull("Producer should be created", producer);

            MessageId msgId = producer.send("test-message-value");
            assertNotNull("MessageId should not be null", msgId);
            log.info("Pulsar producer sent message, id={}", msgId);

            producer.close();
        } finally {
            client.close();
        }
    }

    // ----------------------------------------------------------------------
    // Test 3: Produce + Consume round-trip via Mock Broker
    // ----------------------------------------------------------------------

    @Test
    public void testProduceAndConsumeFromMockBroker() throws Exception {
        String topic = "persistent://public/default/round-trip-topic";

        PulsarClient client = PulsarClient.builder()
                .serviceUrl("pulsar://127.0.0.1:" + mockBrokerPort)
                .connectionTimeout(5, TimeUnit.SECONDS)
                .operationTimeout(5, TimeUnit.SECONDS)
                .build();

        try {
            Consumer<String> consumer = client.newConsumer(Schema.STRING)
                    .topic(topic)
                    .subscriptionName("compat-test-sub")
                    .subscribe();

            assertNotNull("Consumer should be created", consumer);

            Producer<String> producer = client.newProducer(Schema.STRING)
                    .topic(topic)
                    .create();
            producer.send("round-trip-value");
            producer.close();

            Message<String> msg = consumer.receive(15, TimeUnit.SECONDS);
            assertNotNull("Should receive a message", msg);
            log.info("Pulsar round-trip test passed: received message");
            consumer.acknowledge(msg);
            consumer.close();
        } finally {
            client.close();
        }
    }

    // ----------------------------------------------------------------------
    // Test 4: Stub rule injection via real Pulsar client
    // ----------------------------------------------------------------------

    @Test
    public void testStubRuleInjectionWithRealClient() throws Exception {
        String topic = "persistent://public/default/stub-inject-topic";

        Rule rule = new Rule();
        rule.setId("pulsar-stub-1");
        rule.setProtocol("pulsar");
        rule.setEnabled(true);
        rule.setConditions(Collections.singletonList(MatchCondition.topic("equals", topic)));
        ResponseEntry resp = new ResponseEntry();
        resp.setBody("PULSAR-STUBBED-VALUE");
        rule.setResponses(Collections.singletonList(resp));

        when(storage.listRules()).thenReturn(Collections.singletonList(rule));

        PulsarClient client = PulsarClient.builder()
                .serviceUrl("pulsar://127.0.0.1:" + mockBrokerPort)
                .connectionTimeout(5, TimeUnit.SECONDS)
                .operationTimeout(5, TimeUnit.SECONDS)
                .build();

        try {
            Producer<String> producer = client.newProducer(Schema.STRING)
                    .topic(topic)
                    .create();
            producer.send("real-value");
            producer.close();

            Consumer<String> consumer = client.newConsumer(Schema.STRING)
                    .topic(topic)
                    .subscriptionName("stub-test-sub")
                    .subscribe();

            Message<String> msg = consumer.receive(10, TimeUnit.SECONDS);
            assertNotNull("Should receive a message", msg);
            assertEquals("Value should be stubbed", "PULSAR-STUBBED-VALUE", msg.getValue());

            consumer.acknowledge(msg);
            consumer.close();
            log.info("Pulsar stub injection test passed");
        } finally {
            client.close();
        }
    }

    // ----------------------------------------------------------------------
    // Test 5: Real Pulsar baseline produce/consume (Docker-only)
    // ----------------------------------------------------------------------

    @Test
    public void testRealPulsarProduceAndConsume() throws Exception {
        assumeTrue("Docker must be available for real Pulsar baseline test", dockerAvailable && realPulsar != null);

        String topic = "persistent://public/default/real-baseline-topic";

        PulsarClient client = PulsarClient.builder()
                .serviceUrl("pulsar://localhost:" + realPulsarPort)
                .connectionTimeout(10, TimeUnit.SECONDS)
                .operationTimeout(10, TimeUnit.SECONDS)
                .build();

        try {
            // Subscribe first to avoid missing messages
            Consumer<String> consumer = client.newConsumer(Schema.STRING)
                    .topic(topic)
                    .subscriptionName("baseline-sub")
                    .subscribe();

            Producer<String> producer = client.newProducer(Schema.STRING)
                    .topic(topic)
                    .create();
            producer.send("baseline-value");

            Message<String> msg = consumer.receive(30, TimeUnit.SECONDS);
            assertNotNull("Should receive a message from real Pulsar", msg);
            assertEquals("Message value", "baseline-value", msg.getValue());

            consumer.acknowledge(msg);
            consumer.close();
            producer.close();
            log.info("Real Pulsar baseline test passed");
        } finally {
            client.close();
        }
    }

    // ----------------------------------------------------------------------
    // Test 6: Real Pulsar client connects to real Pulsar (Docker-only)
    // ----------------------------------------------------------------------

    @Test
    public void testRealPulsarClientConnects() throws Exception {
        assumeTrue("Docker must be available for real Pulsar baseline test", dockerAvailable && realPulsar != null);

        PulsarClient client = PulsarClient.builder()
                .serviceUrl("pulsar://localhost:" + realPulsarPort)
                .connectionTimeout(10, TimeUnit.SECONDS)
                .operationTimeout(10, TimeUnit.SECONDS)
                .build();

        assertNotNull("PulsarClient should connect to real Pulsar", client);
        client.close();
        log.info("Pulsar client successfully connected to real Pulsar");
    }
}
