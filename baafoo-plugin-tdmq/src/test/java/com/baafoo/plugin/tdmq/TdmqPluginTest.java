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
    public void testInterceptPulsarProtocol() {
        TdmqPlugin plugin = new TdmqPlugin();
        
        PluginContext ctx = new PluginContext();
        ctx.setProtocol("pulsar");
        ctx.setHost("pulsar-broker");
        ctx.setPort(6650);
        
        com.baafoo.plugin.InterceptResult result = plugin.intercept(ctx);
        
        assertTrue(result.isStubbed());
        assertTrue(new String(result.getResponseData()).contains("redirected"));
        assertTrue(new String(result.getResponseData()).contains("pulsar-broker"));
    }

    @Test
    public void testInterceptLocalhostPassthrough() {
        TdmqPlugin plugin = new TdmqPlugin();
        
        PluginContext ctx = new PluginContext();
        ctx.setProtocol("pulsar");
        ctx.setHost("localhost");
        ctx.setPort(6650);
        
        com.baafoo.plugin.InterceptResult result = plugin.intercept(ctx);
        
        assertFalse(result.isStubbed());
    }

    @Test
    public void testInterceptNonPulsarProtocol() {
        TdmqPlugin plugin = new TdmqPlugin();
        
        PluginContext ctx = new PluginContext();
        ctx.setProtocol("http");
        ctx.setHost("example.com");
        ctx.setPort(80);
        
        com.baafoo.plugin.InterceptResult result = plugin.intercept(ctx);
        
        assertFalse(result.isStubbed());
    }

    @Test
    public void testInitAndDestroy() {
        TdmqPlugin plugin = new TdmqPlugin();
        
        assertDoesNotThrow(plugin::init);
        assertDoesNotThrow(plugin::destroy);
    }
}
