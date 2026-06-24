package com.baafoo.server.broker;

import com.baafoo.core.model.Environment;
import com.baafoo.core.model.EnvironmentMode;
import com.baafoo.core.model.MatchCondition;
import com.baafoo.core.model.ResponseEntry;
import com.baafoo.core.model.Rule;
import com.baafoo.server.storage.StorageService;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.utility.DockerImageName;

import javax.jms.*;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;
import static org.mockito.Mockito.*;

/**
 * JMS protocol compatibility test using a real ActiveMQ container.
 *
 * <p>Verifies that the Baafoo JmsMockBroker (backed by embedded Artemis)
 * behaves consistently with a real ActiveMQ broker by:</p>
 * <ol>
 *   <li>Starting a real ActiveMQ container via Testcontainers</li>
 *   <li>Starting the Baafoo JmsMockBroker on a random port</li>
 *   <li>Running the same JMS operations against both</li>
 *   <li>Comparing behavior for queue/topic/stub scenarios</li>
 * </ol>
 */
public class JmsProtocolCompatibilityTest {

    private static final Logger log = LoggerFactory.getLogger(JmsProtocolCompatibilityTest.class);

    private static final DockerImageName ACTIVEMQ_IMAGE =
            DockerImageName.parse("rmohr/activemq:5.15.9");

    public static GenericContainer<?> realActiveMQ;
    private static boolean dockerAvailable;

    static {
        try {
            dockerAvailable = DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            dockerAvailable = false;
        }
        if (dockerAvailable) {
            try {
                realActiveMQ = new GenericContainer<>(ACTIVEMQ_IMAGE)
                        .withExposedPorts(61616);
                realActiveMQ.start();
            } catch (Throwable t) {
                log.warn("Failed to start real ActiveMQ container, real-ActiveMQ tests will be skipped: {}", t.getMessage());
                realActiveMQ = null;
                dockerAvailable = false;
            }
        }
    }

    // Baafoo Mock Broker
    private JmsMockBroker mockBroker;
    private StorageService storage;
    private int mockBrokerPort;

    @Before
    public void setUp() throws Exception {
        storage = mock(StorageService.class);
        when(storage.listRules()).thenReturn(Collections.<Rule>emptyList());
        when(storage.listAgents()).thenReturn(Collections.<StorageService.AgentRegistration>emptyList());
        when(storage.listEnvironments()).thenReturn(Collections.<Environment>emptyList());

        // Use a fixed port different from the existing JmsMockBrokerTest (19004)
        mockBrokerPort = 19604;
        mockBroker = new JmsMockBroker(mockBrokerPort, 3, storage);
        mockBroker.start();
        log.info("Baafoo JMS Mock Broker started on port {}", mockBrokerPort);
        if (realActiveMQ != null) {
            log.info("Real ActiveMQ at tcp://localhost:{}", realActiveMQ.getMappedPort(61616));
        }
    }

    @AfterClass
    public static void tearDownClass() {
        if (realActiveMQ != null) {
            realActiveMQ.stop();
        }
    }

    @After
    public void tearDown() throws Exception {
        if (mockBroker != null) {
            try { mockBroker.stop(); } catch (Exception ignored) {}
        }
    }

    // ----------------------------------------------------------------------
    // Test 1: Queue FIFO delivery — Mock Broker vs real ActiveMQ
    // ----------------------------------------------------------------------

    /**
     * Send 3 messages to a queue on both brokers.
     * Verify FIFO order is preserved in both.
     */
    @Test
    public void testQueueFifoOrder_MockBroker() throws Exception {
        testQueueFifo("127.0.0.1:" + mockBrokerPort, "mock-queue");
        log.info("Mock Broker queue FIFO test passed");
    }

    @Test
    public void testQueueFifoOrder_RealActiveMQ() throws Exception {
        assumeTrue("Docker must be available for real ActiveMQ test", dockerAvailable && realActiveMQ != null);
        testQueueFifo("localhost:" + realActiveMQ.getMappedPort(61616), "real-queue");
        log.info("Real ActiveMQ queue FIFO test passed");
    }

    private void testQueueFifo(String brokerUrl, String queueName) throws Exception {
        ActiveMQConnectionFactory cf = new ActiveMQConnectionFactory("tcp://" + brokerUrl);
        Connection connection = cf.createConnection();
        try {
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            Queue queue = session.createQueue(queueName);

            // Produce 3 messages
            MessageProducer producer = session.createProducer(queue);
            for (int i = 0; i < 3; i++) {
                producer.send(session.createTextMessage("msg-" + i));
            }

            // Consume and verify FIFO order
            MessageConsumer consumer = session.createConsumer(queue);
            connection.start();

            for (int i = 0; i < 3; i++) {
                TextMessage msg = (TextMessage) consumer.receive(5000);
                assertNotNull("Should receive message " + i, msg);
                assertEquals("FIFO order", "msg-" + i, msg.getText());
            }
        } finally {
            connection.close();
        }
    }

    // ----------------------------------------------------------------------
    // Test 2: Topic broadcast — Mock Broker vs real ActiveMQ
    // ----------------------------------------------------------------------

    /**
     * Send a message to a topic with 2 subscribers on both brokers.
     * Verify both subscribers receive the message.
     */
    @Test
    public void testTopicBroadcast_MockBroker() throws Exception {
        testTopicBroadcast("127.0.0.1:" + mockBrokerPort, "mock-topic");
        log.info("Mock Broker topic broadcast test passed");
    }

    @Test
    public void testTopicBroadcast_RealActiveMQ() throws Exception {
        assumeTrue("Docker must be available for real ActiveMQ test", dockerAvailable && realActiveMQ != null);
        testTopicBroadcast("localhost:" + realActiveMQ.getMappedPort(61616), "real-topic");
        log.info("Real ActiveMQ topic broadcast test passed");
    }

    private void testTopicBroadcast(String brokerUrl, String topicName) throws Exception {
        ActiveMQConnectionFactory cf = new ActiveMQConnectionFactory("tcp://" + brokerUrl);
        Connection connection = cf.createConnection();
        try {
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            Topic topic = session.createTopic(topicName);

            // Create 2 subscribers BEFORE sending (durable subscriptions not needed
            // for non-durable topic consumers, but connection must be started)
            MessageConsumer sub1 = session.createConsumer(topic);
            MessageConsumer sub2 = session.createConsumer(topic);
            connection.start();

            // Send a message
            MessageProducer producer = session.createProducer(topic);
            producer.send(session.createTextMessage("broadcast-msg"));

            // Both subscribers should receive the message
            TextMessage msg1 = (TextMessage) sub1.receive(5000);
            TextMessage msg2 = (TextMessage) sub2.receive(5000);

            assertNotNull("Subscriber 1 should receive message", msg1);
            assertNotNull("Subscriber 2 should receive message", msg2);
            assertEquals("Subscriber 1 value", "broadcast-msg", msg1.getText());
            assertEquals("Subscriber 2 value", "broadcast-msg", msg2.getText());
        } finally {
            connection.close();
        }
    }

    // ----------------------------------------------------------------------
    // Test 3: Stub rule injection via Mock Broker
    // ----------------------------------------------------------------------

    /**
     * When a JMS rule with a queue condition is configured, the Mock Broker
     * should inject stub messages via preset message delivery.
     *
     * <p>The JmsMockBroker delivers preset messages when rules are loaded
     * (via {@code reloadRules}). This test verifies that a configured stub
     * rule's response body is delivered as a preset message to the queue.</p>
     */
    @Test
    public void testStubRuleInjection_MockBroker() throws Exception {
        // Configure a stub rule with a preset message
        Rule rule = new Rule();
        rule.setId("jms-stub-1");
        rule.setProtocol("jms");
        rule.setEnabled(true);
        rule.setServiceName("stub-queue");
        MatchCondition cond = new MatchCondition();
        cond.setType("jmsType");
        cond.setOperator("equals");
        cond.setValue("queue");
        rule.setConditions(Collections.singletonList(cond));
        ResponseEntry resp = new ResponseEntry();
        resp.setBody("JMS-STUBBED-VALUE");
        rule.setResponses(Collections.singletonList(resp));

        when(storage.listRules()).thenReturn(Collections.singletonList(rule));

        // Reload rules to restart the broker and send the preset message
        mockBroker.reloadRules(Collections.singletonList(rule));

        // Consume the preset message from the stub queue
        ActiveMQConnectionFactory cf = new ActiveMQConnectionFactory("tcp://127.0.0.1:" + mockBrokerPort);
        Connection connection = cf.createConnection();
        try {
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            Queue queue = session.createQueue("stub-queue");

            MessageConsumer consumer = session.createConsumer(queue);
            connection.start();

            TextMessage msg = (TextMessage) consumer.receive(5000);
            assertNotNull("Should receive a preset message", msg);
            assertEquals("Value should be the preset stub value", "JMS-STUBBED-VALUE", msg.getText());
            log.info("JMS stub injection (preset message) test passed");
        } finally {
            connection.close();
        }
    }

    // ----------------------------------------------------------------------
    // Test 4: Message count verification — Mock vs Real
    // ----------------------------------------------------------------------

    /**
     * Send N messages to a queue, then verify all N are received.
     * Tests message integrity on both brokers.
     */
    @Test
    public void testMessageCount_MockBroker() throws Exception {
        testMessageCount("127.0.0.1:" + mockBrokerPort, "count-mock-queue", 10);
    }

    @Test
    public void testMessageCount_RealActiveMQ() throws Exception {
        assumeTrue("Docker must be available for real ActiveMQ test", dockerAvailable && realActiveMQ != null);
        testMessageCount("localhost:" + realActiveMQ.getMappedPort(61616), "count-real-queue", 10);
    }

    private void testMessageCount(String brokerUrl, String queueName, int count) throws Exception {
        ActiveMQConnectionFactory cf = new ActiveMQConnectionFactory("tcp://" + brokerUrl);
        Connection connection = cf.createConnection();
        try {
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
            Queue queue = session.createQueue(queueName);

            // Produce N messages
            MessageProducer producer = session.createProducer(queue);
            for (int i = 0; i < count; i++) {
                producer.send(session.createTextMessage("message-" + i));
            }

            // Consume all and count
            MessageConsumer consumer = session.createConsumer(queue);
            connection.start();

            int received = 0;
            while (true) {
                TextMessage msg = (TextMessage) consumer.receive(3000);
                if (msg == null) break;
                assertEquals("Message " + received + " value", "message-" + received, msg.getText());
                received++;
            }
            assertEquals("Should receive all " + count + " messages", count, received);
        } finally {
            connection.close();
        }
    }
}
