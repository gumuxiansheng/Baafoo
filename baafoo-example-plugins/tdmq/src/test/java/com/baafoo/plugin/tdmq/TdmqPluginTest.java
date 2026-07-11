package com.baafoo.plugin.tdmq;

import com.baafoo.plugin.ConnectAdvice;
import com.baafoo.plugin.ConnectContext;
import com.baafoo.plugin.InterceptTarget;
import com.baafoo.plugin.PluginEvent;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class TdmqPluginTest {

    // ---- Metadata ----

    @Test
    public void testPluginName() {
        TdmqPlugin plugin = new TdmqPlugin();
        assertEquals("tdmq", plugin.getName());
    }

    @Test
    public void testPluginTarget() {
        TdmqPlugin plugin = new TdmqPlugin();
        assertEquals(InterceptTarget.PULSAR, plugin.getTarget());
    }

    // ---- onConnect (new API) ----

    @Test
    public void testOnConnectPulsarRedirectsToTdmq() {
        TdmqPlugin plugin = new TdmqPlugin();

        ConnectContext ctx = newConnectContext("pulsar", "pulsar-broker", 6650);
        ConnectAdvice advice = plugin.onConnect(ctx);

        assertTrue(advice.isRedirect());
        assertEquals("localhost", advice.getRedirectHost());
        assertEquals(TdmqPlugin.TDMQ_BROKER_PORT, advice.getRedirectPort());
    }

    @Test
    public void testOnConnectLocalhostPassthrough() {
        TdmqPlugin plugin = new TdmqPlugin();

        ConnectContext ctx = newConnectContext("pulsar", "localhost", 6650);
        ConnectAdvice advice = plugin.onConnect(ctx);

        assertTrue(advice.isPassthrough());
        assertFalse(advice.isRedirect());
    }

    @Test
    public void testOnConnectLoopbackIpPassthrough() {
        TdmqPlugin plugin = new TdmqPlugin();

        ConnectContext ctx = newConnectContext("pulsar", "127.0.0.1", 6650);
        ConnectAdvice advice = plugin.onConnect(ctx);

        assertFalse(advice.isRedirect());
        assertTrue(advice.isPassthrough());
    }

    @Test
    public void testOnConnectCaseInsensitiveLocalhost() {
        TdmqPlugin plugin = new TdmqPlugin();

        ConnectContext ctx = newConnectContext("pulsar", "LOCALHOST", 6650);
        ConnectAdvice advice = plugin.onConnect(ctx);

        assertFalse(advice.isRedirect());
    }

    @Test
    public void testOnConnectNonPulsarProtocol() {
        TdmqPlugin plugin = new TdmqPlugin();

        ConnectContext ctx = newConnectContext("http", "example.com", 80);
        ConnectAdvice advice = plugin.onConnect(ctx);

        assertFalse(advice.isRedirect());
        assertTrue(advice.isPassthrough());
    }

    @Test
    public void testOnConnectNullHost() {
        TdmqPlugin plugin = new TdmqPlugin();

        ConnectContext ctx = newConnectContext("pulsar", null, 6650);
        ConnectAdvice advice = plugin.onConnect(ctx);

        assertFalse(advice.isRedirect());
        assertTrue(advice.isPassthrough());
    }

    // ---- onEvent (new API) ----

    @Test
    public void testOnEventNoThrow() {
        TdmqPlugin plugin = new TdmqPlugin();

        PluginEvent event = PluginEvent.connectionRedirected("pulsar", "pulsar-broker:6650", "localhost:9005");
        assertDoesNotThrow(() -> plugin.onEvent(event));

        PluginEvent otherEvent = PluginEvent.connectionPassthrough("pulsar", "localhost:6650");
        assertDoesNotThrow(() -> plugin.onEvent(otherEvent));
    }

    // ---- Lifecycle ----

    @Test
    public void testInitAndDestroy() {
        TdmqPlugin plugin = new TdmqPlugin();

        assertDoesNotThrow(plugin::init);
        assertDoesNotThrow(plugin::destroy);
    }

    // ---- Helpers ----

    private ConnectContext newConnectContext(String protocol, String host, int port) {
        return new ConnectContext(protocol, host, port, null, null, null, null);
    }
}
