package com.baafoo.server.broker;

import com.baafoo.core.model.Environment;
import com.baafoo.core.model.EnvironmentMode;
import com.baafoo.core.model.MatchCondition;
import com.baafoo.core.model.RecordingEntry;
import com.baafoo.core.model.ResponseEntry;
import com.baafoo.core.model.Rule;
import com.baafoo.server.storage.StorageService;
import org.apache.activemq.ActiveMQConnectionFactory;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import javax.jms.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Enumeration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Integration tests for {@link JmsMockBroker}.
 *
 * <p>Tests the four acceptance criteria:
 * <ul>
 *   <li>AC-01: Queue mode FIFO delivery</li>
 *   <li>AC-02: Topic mode broadcast</li>
 *   <li>AC-03: Message delay and ordering</li>
 *   <li>AC-04: Dead letter queue simulation</li>
 * </ul></p>
 *
 * <p>Uses the ActiveMQ 5.x client (OpenWire protocol) to connect to the
 * embedded Artemis broker, verifying full protocol compatibility.</p>
 */
public class JmsMockBrokerTest {

    // Port 19004 collides with the Docker Compose staging environment
    // (testing/enterprise/common/docker-compose.base.yml maps host 19004 →
    // container 9004, the JMS broker port). When Docker staging is running,
    // JmsMockBroker.start() fails with "AMQ229230: Failed to bind acceptor
    // jms-tcp to 0.0.0.0:19004", and every consumer.receive() then times out
    // because the broker never actually started. Using a port outside the
    // Docker staging range (19000-19005) avoids the conflict.
    private static final int TEST_PORT = 29604;

    private JmsMockBroker broker;
    private Connection connection;
    private StorageService storage;

    @Before
    public void setUp() throws Exception {
        storage = mock(StorageService.class);
        when(storage.listRules()).thenReturn(Collections.<Rule>emptyList());
        when(storage.listAgents()).thenReturn(Collections.<StorageService.AgentRegistration>emptyList());
        when(storage.listEnvironments()).thenReturn(Collections.<Environment>emptyList());

        broker = new JmsMockBroker(TEST_PORT, 3, storage);
        broker.start();
        ActiveMQConnectionFactory cf = new ActiveMQConnectionFactory("tcp://127.0.0.1:" + TEST_PORT);
        connection = cf.createConnection();
    }

    @After
    public void tearDown() throws Exception {
        if (connection != null) {
            try {
                connection.close();
            } catch (Exception ignored) {
            }
        }
        if (broker != null) {
            try {
                broker.stop();
            } catch (Exception ignored) {
            }
        }
    }

    // ---- AC-01: Queue mode FIFO delivery ----

    @Test
    public void testQueueFifoDelivery() throws Exception {
        broker.createQueue("testQueue");
        broker.sendPresetMessage("testQueue", "msg1", 0);
        broker.sendPresetMessage("testQueue", "msg2", 0);
        broker.sendPresetMessage("testQueue", "msg3", 0);

        Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        Queue queue = session.createQueue("testQueue");
        MessageConsumer consumer = session.createConsumer(queue);
        connection.start();

        // Messages should arrive in FIFO order
        TextMessage msg1 = (TextMessage) consumer.receive(3000);
        assertNotNull("Should receive first message", msg1);
        assertEquals("msg1", msg1.getText());

        TextMessage msg2 = (TextMessage) consumer.receive(3000);
        assertNotNull("Should receive second message", msg2);
        assertEquals("msg2", msg2.getText());

        TextMessage msg3 = (TextMessage) consumer.receive(3000);
        assertNotNull("Should receive third message", msg3);
        assertEquals("msg3", msg3.getText());

        session.close();
    }

    // ---- AC-02: Topic mode broadcast ----

    @Test
    public void testTopicBroadcast() throws Exception {
        broker.createTopic("testTopic");

        Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        Topic topic = session.createTopic("testTopic");

        // Create two subscribers
        MessageConsumer consumer1 = session.createConsumer(topic);
        MessageConsumer consumer2 = session.createConsumer(topic);

        connection.start();

        // Small delay to ensure subscribers are registered
        Thread.sleep(200);

        // Send a message via the broker's internal API
        broker.sendPresetMessage("testTopic", "broadcast-msg", 0);

        // Both subscribers should receive the message
        TextMessage received1 = (TextMessage) consumer1.receive(3000);
        assertNotNull("Subscriber 1 should receive the message", received1);
        assertEquals("broadcast-msg", received1.getText());

        TextMessage received2 = (TextMessage) consumer2.receive(3000);
        assertNotNull("Subscriber 2 should receive the message", received2);
        assertEquals("broadcast-msg", received2.getText());

        session.close();
    }

    // ---- AC-03: Message delay and ordering ----

    @Test
    public void testMessageDelay() throws Exception {
        broker.createQueue("delayQueue");

        // Send a message with 1 second delay
        broker.sendPresetMessage("delayQueue", "delayed-msg", 1000);

        Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        Queue queue = session.createQueue("delayQueue");
        MessageConsumer consumer = session.createConsumer(queue);
        connection.start();

        // Message should not be available immediately
        Message immediate = consumer.receive(300);
        assertNull("Message should not be delivered before delay", immediate);

        // Message should be available after the delay
        TextMessage delayed = (TextMessage) consumer.receive(5000);
        assertNotNull("Message should be delivered after delay", delayed);
        assertEquals("delayed-msg", delayed.getText());

        session.close();
    }

    @Test
    public void testMessageOrderingWithDelay() throws Exception {
        broker.createQueue("orderQueue");

        // Send immediate message first, then delayed message
        broker.sendPresetMessage("orderQueue", "immediate", 0);
        broker.sendPresetMessage("orderQueue", "delayed", 500);

        Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        Queue queue = session.createQueue("orderQueue");
        MessageConsumer consumer = session.createConsumer(queue);
        connection.start();

        // First message should be the immediate one
        TextMessage msg1 = (TextMessage) consumer.receive(3000);
        assertNotNull(msg1);
        assertEquals("immediate", msg1.getText());

        // Second message should be the delayed one
        TextMessage msg2 = (TextMessage) consumer.receive(5000);
        assertNotNull(msg2);
        assertEquals("delayed", msg2.getText());

        session.close();
    }

    // ---- AC-04: Dead letter queue simulation ----

    @Test
    public void testDeadLetterQueue() throws Exception {
        broker.createQueue("dlqTestQueue");

        // Send a message
        broker.sendPresetMessage("dlqTestQueue", "will-be-dlqd", 0);

        // Receive and rollback 3 times (maxDeliveryAttempts=3)
        // First delivery + rollback
        Session transactedSession1 = connection.createSession(true, Session.SESSION_TRANSACTED);
        Queue queue = transactedSession1.createQueue("dlqTestQueue");
        MessageConsumer consumer1 = transactedSession1.createConsumer(queue);
        connection.start();

        TextMessage msg1 = (TextMessage) consumer1.receive(3000);
        assertNotNull("Should receive message on first delivery", msg1);
        assertEquals("will-be-dlqd", msg1.getText());
        transactedSession1.rollback(); // Rollback triggers redelivery
        consumer1.close();

        // Second delivery + rollback
        MessageConsumer consumer2 = transactedSession1.createConsumer(queue);
        TextMessage msg2 = (TextMessage) consumer2.receive(3000);
        assertNotNull("Should receive message on second delivery", msg2);
        assertEquals("will-be-dlqd", msg2.getText());
        transactedSession1.rollback();
        consumer2.close();

        // Third delivery + rollback — after this, message should go to DLQ
        MessageConsumer consumer3 = transactedSession1.createConsumer(queue);
        TextMessage msg3 = (TextMessage) consumer3.receive(3000);
        assertNotNull("Should receive message on third delivery", msg3);
        assertEquals("will-be-dlqd", msg3.getText());
        transactedSession1.rollback();
        consumer3.close();
        transactedSession1.close();

        // Message should now be in the DLQ
        Session dlqSession = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        Queue dlq = dlqSession.createQueue(JmsMockBroker.DLQ_ADDRESS);
        MessageConsumer dlqConsumer = dlqSession.createConsumer(dlq);

        TextMessage dlqMsg = (TextMessage) dlqConsumer.receive(3000);
        assertNotNull("Message should be in DLQ after max delivery attempts", dlqMsg);
        assertEquals("will-be-dlqd", dlqMsg.getText());

        dlqSession.close();
    }

    // ---- Rule loading ----

    @Test
    public void testLoadRulesCreatesQueuesAndTopics() throws Exception {
        Rule queueRule = new Rule();
        queueRule.setProtocol("jms");
        queueRule.setServiceName("ruleQueue");
        queueRule.setEnabled(true);
        MatchCondition queueType = new MatchCondition();
        queueType.setType("jmsType");
        queueType.setValue("queue");
        queueRule.setConditions(Arrays.asList(queueType));

        ResponseEntry response = new ResponseEntry();
        response.setBody("preset-queue-msg");
        queueRule.setResponses(Arrays.asList(response));

        Rule topicRule = new Rule();
        topicRule.setProtocol("jms");
        topicRule.setServiceName("ruleTopic");
        topicRule.setEnabled(true);
        MatchCondition topicType = new MatchCondition();
        topicType.setType("jmsType");
        topicType.setValue("topic");
        topicRule.setConditions(Arrays.asList(topicType));

        ResponseEntry topicResponse = new ResponseEntry();
        topicResponse.setBody("preset-topic-msg");
        topicRule.setResponses(Arrays.asList(topicResponse));

        broker.loadRules(Arrays.asList(queueRule, topicRule));

        // Verify queue preset message
        Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        Queue queue = session.createQueue("ruleQueue");
        MessageConsumer consumer = session.createConsumer(queue);
        connection.start();

        TextMessage queueMsg = (TextMessage) consumer.receive(3000);
        assertNotNull("Should receive preset queue message", queueMsg);
        assertEquals("preset-queue-msg", queueMsg.getText());

        // Verify topic preset message (need to subscribe before sending)
        // Since preset messages are already sent, we won't receive them on a new subscription
        // This is expected JMS topic behavior — only subscribers that exist at send time get the message
        session.close();
    }

    @Test
    public void testLoadRulesSkipsNonJmsRules() throws Exception {
        Rule httpRule = new Rule();
        httpRule.setProtocol("http");
        httpRule.setServiceName("someService");
        httpRule.setEnabled(true);

        Rule disabledRule = new Rule();
        disabledRule.setProtocol("jms");
        disabledRule.setServiceName("disabledQueue");
        disabledRule.setEnabled(false);

        // Should not throw
        broker.loadRules(Arrays.asList(httpRule, disabledRule));
    }

    @Test
    public void testLoadRulesWithDelay() throws Exception {
        Rule rule = new Rule();
        rule.setProtocol("jms");
        rule.setServiceName("delayRuleQueue");
        rule.setEnabled(true);
        MatchCondition queueType = new MatchCondition();
        queueType.setType("jmsType");
        queueType.setValue("queue");
        rule.setConditions(Arrays.asList(queueType));

        ResponseEntry delayedResponse = new ResponseEntry();
        delayedResponse.setBody("delayed-preset");
        delayedResponse.setDelayMs(500);
        rule.setResponses(Arrays.asList(delayedResponse));

        broker.loadRules(Arrays.asList(rule));

        Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        Queue queue = session.createQueue("delayRuleQueue");
        MessageConsumer consumer = session.createConsumer(queue);
        connection.start();

        // Should not be available immediately
        Message immediate = consumer.receive(200);
        assertNull("Delayed preset message should not be delivered yet", immediate);

        // Should be available after delay
        TextMessage delayed = (TextMessage) consumer.receive(5000);
        assertNotNull("Delayed preset message should be delivered after delay", delayed);
        assertEquals("delayed-preset", delayed.getText());

        session.close();
    }

    // ---- Lifecycle ----

    @Test
    public void testBrokerStartAndStop() throws Exception {
        JmsMockBroker localBroker = new JmsMockBroker(29605);
        assertFalse(localBroker.isStarted());

        localBroker.start();
        assertTrue(localBroker.isStarted());
        assertEquals(29605, localBroker.getPort());
        assertEquals(3, localBroker.getMaxDeliveryAttempts());

        localBroker.stop();
        assertFalse(localBroker.isStarted());
    }

    @Test
    public void testBrokerCustomMaxDeliveryAttempts() throws Exception {
        JmsMockBroker localBroker = new JmsMockBroker(29606, 5);
        assertEquals(5, localBroker.getMaxDeliveryAttempts());
        localBroker.start();
        localBroker.stop();
    }

    @Test(expected = java.lang.IllegalStateException.class)
    public void testCreateQueueBeforeStartThrows() throws Exception {
        JmsMockBroker localBroker = new JmsMockBroker(29607);
        localBroker.createQueue("shouldFail");
    }

    @Test
    public void testReloadRules() throws Exception {
        broker.createQueue("reloadQueue");
        broker.sendPresetMessage("reloadQueue", "before-reload", 0);

        // Reload should stop and restart the broker, clearing all destinations
        broker.reloadRules(Collections.<Rule>emptyList());

        // After reload, the old connection is invalid — create a new one
        if (connection != null) {
            try { connection.close(); } catch (Exception ignored) {}
        }
        ActiveMQConnectionFactory cf = new ActiveMQConnectionFactory("tcp://127.0.0.1:" + TEST_PORT);
        connection = cf.createConnection();

        // The broker is fresh, so we can create new destinations
        broker.createQueue("afterReloadQueue");
        broker.sendPresetMessage("afterReloadQueue", "after-reload", 0);

        Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        Queue queue = session.createQueue("afterReloadQueue");
        MessageConsumer consumer = session.createConsumer(queue);
        connection.start();

        TextMessage msg = (TextMessage) consumer.receive(3000);
        assertNotNull("Should receive message after reload", msg);
        assertEquals("after-reload", msg.getText());

        session.close();
    }

    // ---- Recording ----

    @Test
    public void testPresetMessagesAreNotRecorded() throws Exception {
        broker.createQueue("presetQueue");
        broker.sendPresetMessage("presetQueue", "preset-body", 0);

        // Preset messages are delivered via in-VM connection, so the broker plugin
        // cannot resolve them to an agent/environment and must not create recordings.
        verify(storage, never()).addRecording(any(RecordingEntry.class));
    }

    @Test
    public void testRuntimeProducerMessageRecorded() throws Exception {
        // Configure an agent/environment in RECORD_AND_STUB mode so runtime producer
        // traffic from 127.0.0.1 is captured.
        Environment env = new Environment();
        env.setName("record-env");
        env.setMode(EnvironmentMode.RECORD_AND_STUB);

        StorageService.AgentRegistration agent = new StorageService.AgentRegistration();
        agent.agentId = "jms-agent";
        agent.environment = "record-env";
        agent.agentIp = "127.0.0.1";
        agent.lastHeartbeat = System.currentTimeMillis();

        // Provide a matching rule so the recording plugin's MatchEngine finds a hit
        // and actually calls storage.addRecording().
        Rule recordingRule = new Rule();
        recordingRule.setId("jms-record-rule");
        recordingRule.setProtocol("jms");
        recordingRule.setEnabled(true);

        when(storage.listEnvironments()).thenReturn(Arrays.asList(env));
        when(storage.listAgents()).thenReturn(Arrays.asList(agent));
        when(storage.listRules()).thenReturn(Arrays.asList(recordingRule));

        broker.createQueue("recordQueue");

        Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);
        Queue queue = session.createQueue("recordQueue");
        MessageProducer producer = session.createProducer(queue);
        connection.start();

        TextMessage message = session.createTextMessage("runtime-jms-body");
        producer.send(message);
        producer.close();
        session.close();

        ArgumentCaptor<RecordingEntry> captor = ArgumentCaptor.forClass(RecordingEntry.class);
        verify(storage, timeout(3000).atLeast(1)).addRecording(captor.capture());

        RecordingEntry rec = captor.getValue();
        assertEquals("jms", rec.getProtocol());
        assertEquals("recordQueue", rec.getPath());
        assertEquals("runtime-jms-body", rec.getRequestBody());
        assertEquals("record-env", rec.getEnvironmentId());
        assertEquals("jms-agent", rec.getAgentId());
        assertEquals("127.0.0.1", rec.getAgentIp());
    }
}
