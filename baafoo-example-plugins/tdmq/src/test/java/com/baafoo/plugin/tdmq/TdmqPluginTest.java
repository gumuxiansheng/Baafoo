package com.baafoo.plugin.tdmq;

import com.baafoo.plugin.InterceptTarget;
import com.baafoo.plugin.PluginContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

public class TdmqPluginTest {

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

    @Test
    public void testInterceptPulsarProtocolRedirectsToTdmq() {
        // A Pulsar connection to a remote broker must redirect to the TDMQ stub.
        TdmqPlugin plugin = new TdmqPlugin();

        PluginContext ctx = new PluginContext();
        ctx.setProtocol("pulsar");
        ctx.setHost("pulsar-broker");
        ctx.setPort(6650);

        com.baafoo.plugin.InterceptResult result = plugin.intercept(ctx);

        assertTrue(result.isRedirect());
        assertFalse(result.isStubbed()); // redirect is not a canned-response stub
        assertEquals("localhost", result.getRedirectHost());
        assertEquals(TdmqPlugin.TDMQ_BROKER_PORT, result.getRedirectPort());
    }

    @Test
    public void testInterceptLocalhostPassthrough() {
        // localhost must NOT be redirected (avoids a redirect loop).
        TdmqPlugin plugin = new TdmqPlugin();

        PluginContext ctx = new PluginContext();
        ctx.setProtocol("pulsar");
        ctx.setHost("localhost");
        ctx.setPort(6650);

        com.baafoo.plugin.InterceptResult result = plugin.intercept(ctx);

        assertFalse(result.isRedirect());
        assertFalse(result.isStubbed());
    }

    @Test
    public void testInterceptLoopbackIpPassthrough() {
        // 127.0.0.1 must NOT be redirected either.
        TdmqPlugin plugin = new TdmqPlugin();

        PluginContext ctx = new PluginContext();
        ctx.setProtocol("pulsar");
        ctx.setHost("127.0.0.1");
        ctx.setPort(6650);

        com.baafoo.plugin.InterceptResult result = plugin.intercept(ctx);

        assertFalse(result.isRedirect());
    }

    @Test
    public void testInterceptCaseInsensitiveLocalhost() {
        // "LOCALHOST" must also pass through (regression guard for the
        // case-sensitive equals("localhost") bug the redirect rewrite fixed).
        TdmqPlugin plugin = new TdmqPlugin();

        PluginContext ctx = new PluginContext();
        ctx.setProtocol("pulsar");
        ctx.setHost("LOCALHOST");
        ctx.setPort(6650);

        com.baafoo.plugin.InterceptResult result = plugin.intercept(ctx);

        assertFalse(result.isRedirect());
    }

    @Test
    public void testInterceptNonPulsarProtocol() {
        TdmqPlugin plugin = new TdmqPlugin();

        PluginContext ctx = new PluginContext();
        ctx.setProtocol("http");
        ctx.setHost("example.com");
        ctx.setPort(80);

        com.baafoo.plugin.InterceptResult result = plugin.intercept(ctx);

        assertFalse(result.isRedirect());
        assertFalse(result.isStubbed());
    }

    @Test
    public void testInitAndDestroy() {
        TdmqPlugin plugin = new TdmqPlugin();

        assertDoesNotThrow(plugin::init);
        assertDoesNotThrow(plugin::destroy);
    }
}
