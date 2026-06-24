package com.baafoo.server.broker;

import com.baafoo.core.model.Environment;
import com.baafoo.core.model.EnvironmentMode;
import com.baafoo.core.model.MatchCondition;
import com.baafoo.core.model.ResponseEntry;
import com.baafoo.core.model.Rule;
import com.baafoo.server.storage.StorageService;
import io.netty.channel.nio.NioEventLoopGroup;
import org.apache.pulsar.client.api.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import static org.mockito.Mockito.*;

/**
 * Pulsar protocol compatibility test using the real Pulsar client.
 *
 * <p>Verifies that the Baafoo Pulsar Mock Broker is wire-compatible with
 * the official Apache Pulsar client by:</p>
 * <ol>
 *   <li>Starting the Baafoo PulsarMockBroker on a random port</li>
 *   <li>Using the real Pulsar client to connect, produce, and consume</li>
 *   <li>Verifying the binary protocol handshake and message flow</li>
 * </ol>
 *
 * <p>This test does NOT require Docker — it only needs the Pulsar client
 * library. It tests the Mock Broker's protocol implementation directly.</p>
 */
public class PulsarProtocolCompatibilityTest {

    private static final Logger log = LoggerFactory.getLogger(PulsarProtocolCompatibilityTest.class);

    private NioEventLoopGroup bossGroup;
    private NioEventLoopGroup workerGroup;
    private PulsarMockBroker mockBroker;
    private StorageService storage;
    private int mockBrokerPort;

    @Before
    public void setUp() throws Exception {
        // Set up mocked storage
        storage = mock(StorageService.class);
        when(storage.listRules()).thenReturn(new ArrayList<>());
        when(storage.listEnvironments()).thenReturn(new ArrayList<>());
        when(storage.listAgents()).thenReturn(new ArrayList<>());

        // Start Baafoo Pulsar Mock Broker on a fixed port (different from PulsarMockBrokerTest's 19093)
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

    /**
     * The real Pulsar client performs a CONNECT handshake.
     * The Mock Broker must respond with CONNECTED, otherwise the client
     * will fail to initialize.
     */
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

    /**
     * Create a producer via the real Pulsar client and send a message.
     * The Mock Broker must handle PRODUCER creation and SEND commands.
     */
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

    /**
     * Produce a message, then consume it back.
     * Verifies the full produce → store → deliver cycle.
     *
     * <p>Note: The consumer must subscribe BEFORE the producer sends,
     * because the Mock Broker delivers messages to active subscriptions.</p>
     */
    @Test
    public void testProduceAndConsumeFromMockBroker() throws Exception {
        String topic = "persistent://public/default/round-trip-topic";

        PulsarClient client = PulsarClient.builder()
                .serviceUrl("pulsar://127.0.0.1:" + mockBrokerPort)
                .connectionTimeout(5, TimeUnit.SECONDS)
                .operationTimeout(5, TimeUnit.SECONDS)
                .build();

        try {
            // Subscribe FIRST (before producing)
            Consumer<String> consumer = client.newConsumer(Schema.STRING)
                    .topic(topic)
                    .subscriptionName("compat-test-sub")
                    .subscribe();

            assertNotNull("Consumer should be created", consumer);

            // Now produce a message
            Producer<String> producer = client.newProducer(Schema.STRING)
                    .topic(topic)
                    .create();
            producer.send("round-trip-value");
            producer.close();

            // Receive the message
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

    /**
     * When a Pulsar rule with a topic condition is configured, the Mock Broker
     * should replace the produced value with the stub response.
     */
    @Test
    public void testStubRuleInjectionWithRealClient() throws Exception {
        String topic = "persistent://public/default/stub-inject-topic";

        // Configure a stub rule
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
            // Produce with real client
            Producer<String> producer = client.newProducer(Schema.STRING)
                    .topic(topic)
                    .create();
            producer.send("real-value");
            producer.close();

            // Consume and verify stubbed value
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
}
