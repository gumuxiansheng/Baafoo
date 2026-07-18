package com.baafoo.example.kafka;

import com.baafoo.plugin.ConnectAdvice;
import com.baafoo.plugin.ConnectContext;
import com.baafoo.plugin.InterceptTarget;
import com.baafoo.plugin.PluginEvent;
import com.baafoo.plugin.RequestAdvice;
import com.baafoo.plugin.RequestContext;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class KafkaRedirectPluginTest {

    // ---- onConnect (new API) ----

    @Test
    public void testOnConnectDefaultRedirect() {
        KafkaRedirectPlugin plugin = new KafkaRedirectPlugin();
        plugin.init();

        ConnectContext ctx = newConnectContext("kafka", "real-kafka", 9092);
        ConnectAdvice advice = plugin.onConnect(ctx);

        assertTrue(advice.isRedirect());
        assertEquals("localhost", advice.getRedirectHost());
        assertEquals(9050, advice.getRedirectPort());
    }

    @Test
    public void testOnConnectCustomConfig() {
        KafkaRedirectPlugin plugin = new KafkaRedirectPlugin();
        Map<String, Object> config = new HashMap<String, Object>();
        config.put("redirectHost", "mock-broker");
        config.put("redirectPort", 9999);
        plugin.configure(config);
        plugin.init();

        ConnectContext ctx = newConnectContext("kafka", "real-kafka", 9092);
        ConnectAdvice advice = plugin.onConnect(ctx);

        assertTrue(advice.isRedirect());
        assertEquals("mock-broker", advice.getRedirectHost());
        assertEquals(9999, advice.getRedirectPort());
    }

    @Test
    public void testOnConnectLocalhostPassthrough() {
        KafkaRedirectPlugin plugin = new KafkaRedirectPlugin();
        plugin.init();

        ConnectContext ctx = newConnectContext("kafka", "localhost", 9092);
        ConnectAdvice advice = plugin.onConnect(ctx);
        assertTrue(advice.isPassthrough(), "localhost should passthrough (avoid redirect loop)");
    }

    @Test
    public void testOnConnectLoopbackIpPassthrough() {
        KafkaRedirectPlugin plugin = new KafkaRedirectPlugin();
        plugin.init();

        ConnectContext ctx = newConnectContext("kafka", "127.0.0.1", 9092);
        ConnectAdvice advice = plugin.onConnect(ctx);
        assertTrue(advice.isPassthrough(), "127.0.0.1 should passthrough");
    }

    @Test
    public void testOnConnectCaseInsensitiveLocalhost() {
        KafkaRedirectPlugin plugin = new KafkaRedirectPlugin();
        plugin.init();

        ConnectContext ctx = newConnectContext("kafka", "LOCALHOST", 9092);
        ConnectAdvice advice = plugin.onConnect(ctx);
        assertTrue(advice.isPassthrough(), "'LOCALHOST' should passthrough");
    }

    @Test
    public void testOnConnectNullHostRedirects() {
        KafkaRedirectPlugin plugin = new KafkaRedirectPlugin();
        plugin.init();

        ConnectContext ctx = newConnectContext("kafka", null, 9092);
        ConnectAdvice advice = plugin.onConnect(ctx);
        assertTrue(advice.isRedirect(), "Null host should still redirect");
    }

    // ---- onRequest (new API) ----

    @Test
    public void testOnRequestAnyTopic() {
        KafkaRedirectPlugin plugin = new KafkaRedirectPlugin();
        plugin.init();

        RequestContext ctx = newRequestContext("kafka", "user-events");
        RequestAdvice advice = plugin.onRequest(ctx);

        assertEquals(RequestAdvice.Action.CONTINUE, advice.getAction());
    }

    @Test
    public void testOnRequestNullTopic() {
        KafkaRedirectPlugin plugin = new KafkaRedirectPlugin();
        plugin.init();

        RequestContext ctx = newRequestContext("kafka", null);
        RequestAdvice advice = plugin.onRequest(ctx);

        assertEquals(RequestAdvice.Action.CONTINUE, advice.getAction());
    }

    // ---- onEvent (new API) ----

    @Test
    public void testOnEventNoThrow() {
        KafkaRedirectPlugin plugin = new KafkaRedirectPlugin();
        plugin.init();

        PluginEvent event = PluginEvent.connectionRedirected("kafka", "real-kafka:9092", "localhost:9050");
        plugin.onEvent(event); // should not throw

        PluginEvent otherEvent = PluginEvent.connectionPassthrough("kafka", "localhost:9092");
        plugin.onEvent(otherEvent); // should not throw
    }

    // ---- Metadata & lifecycle ----

    @Test
    public void testPluginMetadata() {
        KafkaRedirectPlugin plugin = new KafkaRedirectPlugin();
        assertEquals("kafka-redirect", plugin.getName());
        assertEquals(InterceptTarget.KAFKA, plugin.getTarget());
    }

    @Test
    public void testDestroyNoThrow() {
        KafkaRedirectPlugin plugin = new KafkaRedirectPlugin();
        plugin.init();
        plugin.destroy();
    }

    // ---- Helpers ----

    private ConnectContext newConnectContext(String protocol, String host, int port) {
        return new ConnectContext(protocol, host, port, null, null, null, null);
    }

    private RequestContext newRequestContext(String protocol, String topic) {
        return new RequestContext(protocol, null, null, Collections.<String, String>emptyMap(),
                Collections.<String, String>emptyMap(), new byte[0], null, 0, null, false,
                null, null, topic, null, null);
    }
}
