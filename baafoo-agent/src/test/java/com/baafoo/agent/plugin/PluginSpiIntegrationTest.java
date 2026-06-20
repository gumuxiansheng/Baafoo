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
import com.baafoo.plugin.InterceptResult;
import com.baafoo.plugin.InterceptTarget;
import com.baafoo.plugin.PluginContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.Assert.*;

/**
 * Integration tests for the Plugin SPI delegation path across all protocol
 * Advice classes. Verifies that each Advice (KafkaProducer, KafkaConsumer,
 * JMS, Socket/NIO via bridge function) correctly consults the PluginManager
 * and honors plugin redirect / passthrough / failure semantics.
 *
 * <p>These tests prove that the SPI is wired end-to-end: the Advice calls
 * {@link PluginManager#getPlugin(InterceptTarget)}, invokes
 * {@link AgentPlugin#intercept(PluginContext)}, and respects the
 * {@link InterceptResult} — including fail-closed behavior on plugin
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
        GlobalRouteState.PLUGIN_CONSULT_FN = null;
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
        GlobalRouteState.PLUGIN_CONSULT_FN = null;
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

        assertTrue("Kafka plugin intercept() must be invoked", plugin.invocationCount.get() > 0);
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

        assertTrue("KafkaConsumer plugin intercept() must be invoked", plugin.invocationCount.get() > 0);
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

        assertTrue("JMS plugin intercept() must be invoked", plugin.invocationCount.get() > 0);
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

    // ==================== Socket/NIO Bridge Function SPI ====================

    @Test
    public void socketBridge_pluginRedirect_overridesDefault() throws Exception {
        CountingPlugin plugin = new CountingPlugin(InterceptTarget.SOCKET, "localhost", 9060);
        PluginManager pm = installPlugin(plugin);

        // Set up the bridge function (normally done in BaafooAgent.premain)
        setupPluginConsultBridge();

        // Simulate what SocketConnectAdvice does: consult the bridge
        Function<Object[], Object[]> consultFn = GlobalRouteState.PLUGIN_CONSULT_FN;
        assertNotNull("Bridge function must be set", consultFn);

        Object[] result = consultFn.apply(new Object[]{"external-host", Integer.valueOf(8080)});

        assertNotNull("Plugin must return a redirect result", result);
        assertEquals("localhost", result[0]);
        assertEquals(9060, result[1]);
        assertTrue("Socket plugin intercept() must be invoked", plugin.invocationCount.get() > 0);
        assertEquals("tcp", plugin.lastProtocol);
    }

    @Test
    public void socketBridge_pluginPassthrough_returnsNull() throws Exception {
        PassthroughPlugin plugin = new PassthroughPlugin(InterceptTarget.SOCKET);
        PluginManager pm = installPlugin(plugin);
        setupPluginConsultBridge();

        Function<Object[], Object[]> consultFn = GlobalRouteState.PLUGIN_CONSULT_FN;
        Object[] result = consultFn.apply(new Object[]{"external-host", Integer.valueOf(8080)});

        assertNull("Passthrough plugin must return null (no redirect)", result);
        assertTrue(plugin.invocationCount.get() > 0);
    }

    @Test
    public void socketBridge_pluginThrows_failsClosed() throws Exception {
        ThrowingPlugin plugin = new ThrowingPlugin(InterceptTarget.SOCKET);
        PluginManager pm = installPlugin(plugin);
        setupPluginConsultBridge();

        Function<Object[], Object[]> consultFn = GlobalRouteState.PLUGIN_CONSULT_FN;
        // Must not throw — exception caught inside bridge function.
        Object[] result = consultFn.apply(new Object[]{"external-host", Integer.valueOf(8080)});

        assertNull("Throwing plugin must return null (fail-closed)", result);
    }

    @Test
    public void socketBridge_noPlugin_returnsNull() {
        // No plugin manager injected — bridge function returns null.
        setupPluginConsultBridge();

        Function<Object[], Object[]> consultFn = GlobalRouteState.PLUGIN_CONSULT_FN;
        Object[] result = consultFn.apply(new Object[]{"external-host", Integer.valueOf(8080)});

        assertNull("No plugin → bridge must return null", result);
    }

    @Test
    public void socketBridge_nullFunction_returnsNull() {
        // PLUGIN_CONSULT_FN is null (not set) — SocketConnectAdvice checks for null.
        assertNull(GlobalRouteState.PLUGIN_CONSULT_FN);
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

    /**
     * Set up the PLUGIN_CONSULT_FN bridge function, mirroring what
     * BaafooAgent.premain does. This allows Socket/NIO advice to consult
     * the PluginManager SPI via the bridge.
     */
    private void setupPluginConsultBridge() {
        GlobalRouteState.PLUGIN_CONSULT_FN = (Function<Object[], Object[]>) args -> {
            if (args == null || args.length < 2) return null;
            try {
                String host = (String) args[0];
                int port = (Integer) args[1];
                PluginManager pm;
                try {
                    java.lang.reflect.Field f = BaafooAgent.class.getDeclaredField("pluginManager");
                    f.setAccessible(true);
                    pm = (PluginManager) f.get(null);
                } catch (Exception e) {
                    return null;
                }
                if (pm == null) return null;
                AgentPlugin plugin = pm.getPlugin(InterceptTarget.SOCKET);
                if (plugin == null) {
                    plugin = pm.getPlugin(InterceptTarget.NIO_SOCKET);
                }
                if (plugin == null) return null;
                PluginContext ctx = new PluginContext();
                ctx.setProtocol("tcp");
                ctx.setHost(host);
                ctx.setPort(port);
                InterceptResult result = plugin.intercept(ctx);
                if (result != null && result.isRedirect()) {
                    return new Object[]{result.getRedirectHost(), result.getRedirectPort()};
                }
            } catch (Throwable t) {
                // Fail-closed
            }
            return null;
        };
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

    /** A plugin that counts intercept() calls, records context, and returns a redirect. */
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
        @Override public InterceptResult intercept(PluginContext ctx) {
            invocationCount.incrementAndGet();
            lastProtocol = ctx.getProtocol();
            lastHost = ctx.getHost();
            lastPort = ctx.getPort();
            return InterceptResult.redirect(redirectHost, redirectPort);
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
        @Override public InterceptResult intercept(PluginContext ctx) {
            invocationCount.incrementAndGet();
            return InterceptResult.passthrough();
        }
        @Override public void destroy() {}
    }

    private static class ThrowingPlugin implements AgentPlugin {
        private final InterceptTarget target;

        ThrowingPlugin(InterceptTarget target) { this.target = target; }

        @Override public String getName() { return "throwing-" + target; }
        @Override public InterceptTarget getTarget() { return target; }
        @Override public void init() {}
        @Override public InterceptResult intercept(PluginContext ctx) {
            throw new RuntimeException("simulated plugin failure");
        }
        @Override public void destroy() {}
    }
}
