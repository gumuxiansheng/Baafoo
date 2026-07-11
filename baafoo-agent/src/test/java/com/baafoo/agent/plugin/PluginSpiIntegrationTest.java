package com.baafoo.agent.plugin;

import com.baafoo.agent.BaafooAgent;
import com.baafoo.agent.GlobalRouteState;
import com.baafoo.agent.advice.JmsConnectionFactoryAdvice;
import com.baafoo.agent.advice.KafkaConsumerAdvice;
import com.baafoo.agent.advice.KafkaProducerAdvice;
import com.baafoo.agent.advice.RouteManager;
import com.baafoo.core.model.EnvironmentMode;
import com.baafoo.core.model.Rule;
import com.baafoo.plugin.AgentPlugin;
import com.baafoo.plugin.ConnectAdvice;
import com.baafoo.plugin.ConnectContext;
import com.baafoo.plugin.InterceptTarget;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/**
 * Integration tests for the Plugin SPI delegation path across all protocol
 * Advice classes. Verifies that each Advice (KafkaProducer, KafkaConsumer,
 * JMS) correctly consults the PluginManager and honors plugin redirect /
 * passthrough / failure semantics.
 *
 * <p>These tests prove that the SPI is wired end-to-end: the Advice calls
 * {@link PluginManager#getPlugin(InterceptTarget)}, invokes
 * {@link AgentPlugin#onConnect(ConnectContext)}, and respects the
 * {@link ConnectAdvice} — including fail-closed behavior on plugin
 * exceptions.</p>
 */
public class PluginSpiIntegrationTest {

    @Before
    public void setup() {
        GlobalRouteState.ROUTES.clear();
        GlobalRouteState.SERVER_HOST = "127.0.0.1";
        GlobalRouteState.KAFKA_PORT = 9002;
        GlobalRouteState.PULSAR_PORT = 9003;
        GlobalRouteState.JMS_PORT = 9004;
        RouteManager.setMode(EnvironmentMode.STUB);

        // Register protocol rules so hasProtocolRoutes() returns true.
        Rule kafkaRule = new Rule();
        kafkaRule.setId("kafka-rule");
        kafkaRule.setProtocol("kafka");
        kafkaRule.setEnabled(true);

        Rule jmsRule = new Rule();
        jmsRule.setId("jms-rule");
        jmsRule.setProtocol("jms");
        jmsRule.setEnabled(true);

        Rule tcpRule = new Rule();
        tcpRule.setId("tcp-rule");
        tcpRule.setProtocol("tcp");
        tcpRule.setEnabled(true);

        RouteManager.updateRules(java.util.Arrays.asList(kafkaRule, jmsRule, tcpRule));
    }

    @After
    public void teardown() throws Exception {
        RouteManager.setMode(EnvironmentMode.PASSTHROUGH);
        RouteManager.updateRules(Collections.<Rule>emptyList());
        injectPluginManager(null);
    }

    // ==================== Kafka Producer SPI ====================

    @Test
    public void kafkaProducer_pluginRedirect_overridesDefault() throws Exception {
        CountingPlugin plugin = new CountingPlugin(InterceptTarget.KAFKA, "localhost", 9050);
        PluginManager pm = installPlugin(plugin);

        Properties props = new Properties();
        props.setProperty("bootstrap.servers", "real-kafka:9092");

        KafkaProducerAdvice.onConstructor(new Object[]{props});

        assertTrue("Kafka plugin onConnect() must be invoked", plugin.invocationCount.get() > 0);
        assertEquals("kafka", plugin.lastProtocol);
        assertEquals("real-kafka", plugin.lastHost);
        assertEquals(9092, plugin.lastPort);
        // Plugin redirected to localhost:9050
        assertEquals("localhost:9050", props.getProperty("bootstrap.servers"));
    }

    @Test
    public void kafkaProducer_pluginPassthrough_usesDefault() throws Exception {
        PassthroughPlugin plugin = new PassthroughPlugin(InterceptTarget.KAFKA);
        PluginManager pm = installPlugin(plugin);

        Properties props = new Properties();
        props.setProperty("bootstrap.servers", "real-kafka:9092");

        KafkaProducerAdvice.onConstructor(new Object[]{props});

        assertTrue(plugin.invocationCount.get() > 0);
        // Passthrough → falls back to default SERVER_HOST:KAFKA_PORT
        assertEquals("127.0.0.1:9002", props.getProperty("bootstrap.servers"));
    }

    @Test
    public void kafkaProducer_pluginThrows_failsClosed() throws Exception {
        ThrowingPlugin plugin = new ThrowingPlugin(InterceptTarget.KAFKA);
        PluginManager pm = installPlugin(plugin);

        Properties props = new Properties();
        props.setProperty("bootstrap.servers", "real-kafka:9092");

        // Must not throw — plugin exception is caught inside the advice.
        KafkaProducerAdvice.onConstructor(new Object[]{props});

        // Fail-closed → falls back to default
        assertEquals("127.0.0.1:9002", props.getProperty("bootstrap.servers"));
    }

    @Test
    public void kafkaProducer_noPlugin_usesDefault() {
        // No plugin manager injected — getPluginManager() returns null.
        Properties props = new Properties();
        props.setProperty("bootstrap.servers", "real-kafka:9092");

        KafkaProducerAdvice.onConstructor(new Object[]{props});

        assertEquals("127.0.0.1:9002", props.getProperty("bootstrap.servers"));
    }

    // ==================== Kafka Consumer SPI ====================

    @Test
    public void kafkaConsumer_pluginRedirect_overridesDefault() throws Exception {
        CountingPlugin plugin = new CountingPlugin(InterceptTarget.KAFKA, "localhost", 9050);
        PluginManager pm = installPlugin(plugin);

        Map<String, Object> configs = new HashMap<String, Object>();
        configs.put("bootstrap.servers", "real-kafka:9092");

        KafkaConsumerAdvice.onConstructor(new Object[]{configs});

        assertTrue("KafkaConsumer plugin onConnect() must be invoked", plugin.invocationCount.get() > 0);
        assertEquals("localhost:9050", configs.get("bootstrap.servers"));
    }

    @Test
    public void kafkaConsumer_noPlugin_usesDefault() {
        Map<String, Object> configs = new HashMap<String, Object>();
        configs.put("bootstrap.servers", "real-kafka:9092");

        KafkaConsumerAdvice.onConstructor(new Object[]{configs});

        assertEquals("127.0.0.1:9002", configs.get("bootstrap.servers"));
    }

    // ==================== JMS SPI ====================

    @Test
    public void jms_pluginRedirect_overridesDefault() throws Exception {
        CountingPlugin plugin = new CountingPlugin(InterceptTarget.JMS, "localhost", 9054);
        PluginManager pm = installPlugin(plugin);

        // Create a mock ActiveMQConnectionFactory-like object with getBrokerURL/setBrokerURL
        Object factory = createMockConnectionFactory("tcp://real-jms:61616");

        JmsConnectionFactoryAdvice.onConstructorExit(factory);

        assertTrue("JMS plugin onConnect() must be invoked", plugin.invocationCount.get() > 0);
        assertEquals("jms", plugin.lastProtocol);
        assertEquals("real-jms", plugin.lastHost);
        assertEquals(61616, plugin.lastPort);
        // Plugin redirected to localhost:9054
        assertEquals("tcp://localhost:9054", getBrokerUrl(factory));
    }

    @Test
    public void jms_noPlugin_usesDefault() throws Exception {
        Object factory = createMockConnectionFactory("tcp://real-jms:61616");

        JmsConnectionFactoryAdvice.onConstructorExit(factory);

        assertEquals("tcp://127.0.0.1:9004", getBrokerUrl(factory));
    }

    // ==================== Path segment extraction helpers ====================

    @Test
    public void pulsarExtractPathSegments_withTenantNamespace() {
        // Test the helper method directly
        String[] segments = com.baafoo.agent.advice.PulsarClientAdvice.extractPathSegments(
                "pulsar://broker:6650/my-tenant/my-namespace");
        assertEquals(2, segments.length);
        assertEquals("my-tenant", segments[0]);
        assertEquals("my-namespace", segments[1]);
    }

    @Test
    public void pulsarExtractPathSegments_noPath() {
        String[] segments = com.baafoo.agent.advice.PulsarClientAdvice.extractPathSegments(
                "pulsar://broker:6650");
        assertEquals(0, segments.length);
    }

    @Test
    public void pulsarExtractPathSegments_null() {
        String[] segments = com.baafoo.agent.advice.PulsarClientAdvice.extractPathSegments(null);
        assertEquals(0, segments.length);
    }

    @Test
    public void jmsExtractDestination_withPath() {
        String dest = com.baafoo.agent.advice.JmsConnectionFactoryAdvice.extractDestination(
                "tcp://broker:61616/queue.orders?jms.useAsyncSend=true");
        assertEquals("queue.orders", dest);
    }

    @Test
    public void jmsExtractDestination_noPath() {
        assertNull(com.baafoo.agent.advice.JmsConnectionFactoryAdvice.extractDestination(
                "tcp://broker:61616"));
    }

    @Test
    public void jmsExtractDestination_null() {
        assertNull(com.baafoo.agent.advice.JmsConnectionFactoryAdvice.extractDestination(null));
    }

    // ==================== Helpers ====================

    /** Install a plugin into a fresh PluginManager and inject it into BaafooAgent. */
    private PluginManager installPlugin(AgentPlugin plugin) throws Exception {
        PluginManager pm = new PluginManager();
        java.lang.reflect.Field f = PluginManager.class.getDeclaredField("plugins");
        f.setAccessible(true);
        @SuppressWarnings("unchecked")
        Map<InterceptTarget, AgentPlugin> map =
                (Map<InterceptTarget, AgentPlugin>) f.get(pm);
        map.put(plugin.getTarget(), plugin);
        injectPluginManager(pm);
        return pm;
    }

    /** Inject (or clear) the PluginManager returned by BaafooAgent.getPluginManager(). */
    private static void injectPluginManager(PluginManager pm) throws Exception {
        java.lang.reflect.Field f = BaafooAgent.class.getDeclaredField("pluginManager");
        f.setAccessible(true);
        f.set(null, pm);
    }

    /** Create a mock object with getBrokerURL/setBrokerURL methods. */
    private Object createMockConnectionFactory(final String initialUrl) {
        return new MockConnectionFactory(initialUrl);
    }

    private String getBrokerUrl(Object factory) throws Exception {
        return ((MockConnectionFactory) factory).getBrokerURL();
    }

    /**
     * Public static nested class so that reflection from
     * {@code com.baafoo.agent.advice.JmsConnectionFactoryAdvice} (a different
     * package) can access getBrokerURL/setBrokerURL. Anonymous classes are
     * package-private and inaccessible cross-package via reflection.
     */
    public static class MockConnectionFactory {
        private String url;

        public MockConnectionFactory(String url) { this.url = url; }

        public String getBrokerURL() { return url; }

        public void setBrokerURL(String url) { this.url = url; }
    }

    // ==================== Test Plugins ====================

    /** A plugin that counts onConnect() calls, records context, and returns a redirect. */
    private static class CountingPlugin implements AgentPlugin {
        final AtomicInteger invocationCount = new AtomicInteger();
        volatile String lastProtocol;
        volatile String lastHost;
        volatile int lastPort;
        private final InterceptTarget target;
        private final String redirectHost;
        private final int redirectPort;

        CountingPlugin(InterceptTarget target, String redirectHost, int redirectPort) {
            this.target = target;
            this.redirectHost = redirectHost;
            this.redirectPort = redirectPort;
        }

        @Override public String getName() { return "counting-" + target; }
        @Override public InterceptTarget getTarget() { return target; }
        @Override public void init() {}
        @Override public ConnectAdvice onConnect(ConnectContext ctx) {
            invocationCount.incrementAndGet();
            lastProtocol = ctx.getProtocol();
            lastHost = ctx.getHost();
            lastPort = ctx.getPort();
            return ConnectAdvice.redirect(redirectHost, redirectPort);
        }
        @Override public void destroy() {}
    }

    private static class PassthroughPlugin implements AgentPlugin {
        final AtomicInteger invocationCount = new AtomicInteger();
        private final InterceptTarget target;

        PassthroughPlugin(InterceptTarget target) { this.target = target; }

        @Override public String getName() { return "passthrough-" + target; }
        @Override public InterceptTarget getTarget() { return target; }
        @Override public void init() {}
        @Override public ConnectAdvice onConnect(ConnectContext ctx) {
            invocationCount.incrementAndGet();
            return ConnectAdvice.passthrough();
        }
        @Override public void destroy() {}
    }

    private static class ThrowingPlugin implements AgentPlugin {
        private final InterceptTarget target;

        ThrowingPlugin(InterceptTarget target) { this.target = target; }

        @Override public String getName() { return "throwing-" + target; }
        @Override public InterceptTarget getTarget() { return target; }
        @Override public void init() {}
        @Override public ConnectAdvice onConnect(ConnectContext ctx) {
            throw new RuntimeException("simulated plugin failure");
        }
        @Override public void destroy() {}
    }
}
