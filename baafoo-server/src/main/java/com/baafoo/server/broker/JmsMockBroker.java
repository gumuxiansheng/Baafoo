package com.baafoo.server.broker;

import com.baafoo.core.model.MatchCondition;
import com.baafoo.core.model.ResponseEntry;
import com.baafoo.core.model.Rule;
import com.baafoo.server.storage.StorageService;
import org.apache.activemq.artemis.api.core.RoutingType;
import org.apache.activemq.artemis.api.core.SimpleString;
import org.apache.activemq.artemis.api.core.TransportConfiguration;
import org.apache.activemq.artemis.api.jms.ActiveMQJMSClient;
import org.apache.activemq.artemis.api.jms.JMSFactoryType;
import org.apache.activemq.artemis.core.config.impl.ConfigurationImpl;
import org.apache.activemq.artemis.core.server.ActiveMQServer;
import org.apache.activemq.artemis.core.server.ActiveMQServers;
import org.apache.activemq.artemis.core.server.impl.AddressInfo;
import org.apache.activemq.artemis.core.settings.impl.AddressSettings;
import org.apache.activemq.artemis.jms.client.ActiveMQConnectionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jms.Connection;
import javax.jms.Destination;
import javax.jms.MessageProducer;
import javax.jms.Session;
import javax.jms.TextMessage;
import java.util.List;

/**
 * Embedded JMS Mock Broker backed by Apache ActiveMQ Artemis.
 *
 * <p>Provides a lightweight in-memory JMS broker that supports:
 * <ul>
 *   <li>Queue mode FIFO delivery (AC-01)</li>
 *   <li>Topic mode broadcast (AC-02)</li>
 *   <li>Message delay and ordering via scheduled delivery (AC-03)</li>
 *   <li>Dead letter queue simulation with configurable max-delivery-attempts (AC-04)</li>
 * </ul></p>
 *
 * <p>The broker listens on a configurable TCP port and speaks the OpenWire protocol,
 * which is the native protocol of Apache ActiveMQ 5.x clients. The Baafoo Agent
 * intercepts {@code ActiveMQConnectionFactory} and replaces the brokerURL to point
 * to this embedded broker.</p>
 */
public class JmsMockBroker {

    private static final Logger log = LoggerFactory.getLogger(JmsMockBroker.class);

    static final String DLQ_ADDRESS = "DLQ";
    static final String INVM_ACCEPTOR = "in-vm";
    static final String TCP_ACCEPTOR = "jms-tcp";

    private ActiveMQServer server;
    private ActiveMQConnectionFactory internalCf;
    private final int port;
    private final int maxDeliveryAttempts;
    private final StorageService storage;
    private volatile boolean started;

    public JmsMockBroker(int port) {
        this(port, 3, null);
    }

    public JmsMockBroker(int port, int maxDeliveryAttempts) {
        this(port, maxDeliveryAttempts, null);
    }

    public JmsMockBroker(int port, int maxDeliveryAttempts, StorageService storage) {
        this.port = port;
        this.maxDeliveryAttempts = maxDeliveryAttempts;
        this.storage = storage;
    }

    /**
     * Start the embedded Artemis broker.
     */
    public void start() throws Exception {
        ConfigurationImpl config = new ConfigurationImpl();
        config.setPersistenceEnabled(false);
        config.setSecurityEnabled(false);
        config.setIDCacheSize(2048);

        // TCP acceptor for external clients (OpenWire protocol)
        config.addAcceptorConfiguration(TCP_ACCEPTOR, "tcp://0.0.0.0:" + port);

        // In-VM acceptor for internal preset message delivery
        config.addAcceptorConfiguration(INVM_ACCEPTOR, "vm://0");

        // Address settings: DLQ configuration
        AddressSettings defaultSettings = new AddressSettings();
        defaultSettings.setMaxDeliveryAttempts(maxDeliveryAttempts);
        defaultSettings.setDeadLetterAddress(SimpleString.toSimpleString(DLQ_ADDRESS));
        defaultSettings.setAutoCreateQueues(true);
        defaultSettings.setAutoCreateAddresses(true);
        config.addAddressesSetting("#", defaultSettings);

        // Pre-configure DLQ address as anycast
        AddressSettings dlqSettings = new AddressSettings();
        dlqSettings.setAutoCreateQueues(true);
        dlqSettings.setAutoCreateAddresses(true);
        config.addAddressesSetting(DLQ_ADDRESS, dlqSettings);

        server = ActiveMQServers.newActiveMQServer(config);
        server.start();

        // Register the recording plugin to capture runtime JMS producer messages
        // from all protocols (OpenWire + Core) with proper environment resolution.
        if (storage != null) {
            server.registerBrokerPlugin(new JmsRecordingPlugin(storage));
        }

        // Create the DLQ queue
        server.createQueue(SimpleString.toSimpleString(DLQ_ADDRESS), RoutingType.ANYCAST,
                SimpleString.toSimpleString(DLQ_ADDRESS), null, true, false);

        // Internal connection factory using in-VM transport (no network overhead)
        internalCf = ActiveMQJMSClient.createConnectionFactoryWithoutHA(
                JMSFactoryType.CF,
                new TransportConfiguration("org.apache.activemq.artemis.core.remoting.impl.invm.InVMConnectorFactory"));

        started = true;
        log.info("JMS Mock Broker started on port {} (maxDeliveryAttempts={})", port, maxDeliveryAttempts);
    }

    /**
     * Stop the embedded Artemis broker.
     */
    public void stop() throws Exception {
        started = false;
        if (internalCf != null) {
            try {
                internalCf.close();
            } catch (Exception e) {
                log.warn("Error closing internal JMS connection factory: {}", e.getMessage());
            }
        }
        if (server != null) {
            server.stop();
        }
        log.info("JMS Mock Broker stopped");
    }

    /**
     * Create a JMS queue (anycast address).
     */
    public void createQueue(String name) throws Exception {
        ensureStarted();
        SimpleString ss = SimpleString.toSimpleString(name);
        if (server.getAddressInfo(ss) == null) {
            server.addAddressInfo(new AddressInfo(ss, RoutingType.ANYCAST));
        }
        server.createQueue(ss, RoutingType.ANYCAST, ss, null, true, false);
        log.info("Created JMS queue: {}", name);
    }

    /**
     * Create a JMS topic (multicast address).
     */
    public void createTopic(String name) throws Exception {
        ensureStarted();
        SimpleString ss = SimpleString.toSimpleString(name);
        if (server.getAddressInfo(ss) == null) {
            server.addAddressInfo(new AddressInfo(ss, RoutingType.MULTICAST));
        }
        log.info("Created JMS topic: {}", name);
    }

    /**
     * Send a preset message to a destination with optional delivery delay.
     *
     * @param destinationName the queue or topic name
     * @param body            the message body
     * @param delayMs         delivery delay in milliseconds (0 = no delay)
     */
    public void sendPresetMessage(String destinationName, String body, long delayMs) throws Exception {
        ensureStarted();
        Connection connection = null;
        try {
            connection = internalCf.createConnection();
            Session session = connection.createSession(false, Session.AUTO_ACKNOWLEDGE);

            Destination destination = resolveDestination(session, destinationName);
            MessageProducer producer = session.createProducer(destination);
            TextMessage message = session.createTextMessage(body);

            if (delayMs > 0) {
                // Artemis scheduled delivery: absolute timestamp in milliseconds
                message.setLongProperty("_AMQ_SCHED_DELIVERY", System.currentTimeMillis() + delayMs);
            }

            producer.send(message);
            connection.start();
            log.info("Sent preset message to {}: {} bytes, delay={}ms", destinationName, body.length(), delayMs);
        } finally {
            if (connection != null) {
                try {
                    connection.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    /**
     * Load JMS rules from storage and create destinations with preset messages.
     *
     * <p>Rule mapping convention:
     * <ul>
     *   <li>{@code rule.protocol} = "jms"</li>
     *   <li>{@code rule.serviceName} = destination name (e.g., "orderQueue", "priceTopic")</li>
     *   <li>Condition with type="jmsType" and value="queue" or "topic" (default: queue)</li>
     *   <li>{@code rule.responses} = preset messages with body and delayMs</li>
     * </ul></p>
     */
    public void loadRules(List<Rule> rules) throws Exception {
        ensureStarted();
        int loaded = 0;
        for (Rule rule : rules) {
            if (!"jms".equalsIgnoreCase(rule.getProtocol()) || !rule.isEnabled()) {
                continue;
            }

            String destName = rule.getServiceName();
            if (destName == null || destName.isEmpty()) {
                log.warn("JMS rule '{}' has no serviceName (destination name), skipping", rule.getName());
                continue;
            }

            // Determine destination type from conditions
            String destType = resolveDestinationType(rule);

            // Create destination
            if ("topic".equalsIgnoreCase(destType)) {
                createTopic(destName);
            } else {
                createQueue(destName);
            }

            // Load preset messages from responses.
            // Preset messages are rule configuration, not runtime traffic, so they
            // are NOT added to recordings. Runtime JMS producer traffic is captured
            // by JmsRecordingPlugin and will have proper environmentId/agentId.
            for (ResponseEntry response : rule.getResponses()) {
                String body = response.getBody();
                if (body != null && !body.isEmpty()) {
                    sendPresetMessage(destName, body, response.getDelayMs());
                    loaded++;
                }
            }
        }
        log.info("Loaded {} JMS preset messages from rules", loaded);
    }

    /**
     * Reload rules: stop the broker, recreate it, and load updated rules.
     */
    public void reloadRules(List<Rule> rules) throws Exception {
        log.info("Reloading JMS rules...");
        stop();
        start();
        loadRules(rules);
    }

    public boolean isStarted() {
        return started;
    }

    public int getPort() {
        return port;
    }

    public int getMaxDeliveryAttempts() {
        return maxDeliveryAttempts;
    }

    // ---- Internal helpers ----

    private void ensureStarted() {
        if (!started) {
            throw new IllegalStateException("JMS Mock Broker is not started");
        }
    }

    private String resolveDestinationType(Rule rule) {
        for (MatchCondition cond : rule.getConditions()) {
            if ("jmsType".equalsIgnoreCase(cond.getType())) {
                return cond.getValue();
            }
        }
        return "queue";
    }

    private Destination resolveDestination(Session session, String destinationName) throws Exception {
        SimpleString ss = SimpleString.toSimpleString(destinationName);
        AddressInfo addressInfo = server.getAddressInfo(ss);
        if (addressInfo != null && addressInfo.getRoutingTypes().contains(RoutingType.MULTICAST)) {
            return session.createTopic(destinationName);
        } else {
            return session.createQueue(destinationName);
        }
    }
}
