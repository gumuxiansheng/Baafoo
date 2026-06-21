package com.baafoo.example.kafka;

import com.baafoo.plugin.InterceptResult;
import com.baafoo.plugin.InterceptTarget;
import com.baafoo.plugin.PluginContext;
import org.junit.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class KafkaRedirectPluginTest {

    @Test
    public void testDefaultRedirect() {
        KafkaRedirectPlugin plugin = new KafkaRedirectPlugin();
        plugin.init();

        PluginContext ctx = new PluginContext();
        ctx.setProtocol("kafka");
        ctx.setHost("real-kafka");
        ctx.setPort(9092);

        InterceptResult result = plugin.intercept(ctx);
        assertTrue(result.isRedirect());
        assertEquals("localhost", result.getRedirectHost());
        assertEquals(9050, result.getRedirectPort());
    }

    @Test
    public void testCustomConfig() {
        KafkaRedirectPlugin plugin = new KafkaRedirectPlugin();
        Map<String, Object> config = new HashMap<String, Object>();
        config.put("redirectHost", "mock-broker");
        config.put("redirectPort", 9999);
        plugin.configure(config);
        plugin.init();

        PluginContext ctx = new PluginContext();
        ctx.setProtocol("kafka");

        InterceptResult result = plugin.intercept(ctx);
        assertTrue(result.isRedirect());
        assertEquals("mock-broker", result.getRedirectHost());
        assertEquals(9999, result.getRedirectPort());
    }

    @Test
    public void testExcludeTopics() {
        KafkaRedirectPlugin plugin = new KafkaRedirectPlugin();
        Map<String, Object> config = new HashMap<String, Object>();
        config.put("excludeTopics", Arrays.asList("internal-health", "system-metrics"));
        plugin.configure(config);
        plugin.init();

        // Excluded topic → passthrough
        PluginContext ctx = new PluginContext();
        ctx.setProtocol("kafka");
        ctx.setTopic("internal-health");
        InterceptResult result = plugin.intercept(ctx);
        assertFalse("Excluded topic should passthrough", result.isRedirect());

        // Non-excluded topic → redirect
        PluginContext ctx2 = new PluginContext();
        ctx2.setProtocol("kafka");
        ctx2.setTopic("user-events");
        InterceptResult result2 = plugin.intercept(ctx2);
        assertTrue("Non-excluded topic should redirect", result2.isRedirect());
    }

    @Test
    public void testNullTopicRedirects() {
        KafkaRedirectPlugin plugin = new KafkaRedirectPlugin();
        plugin.init();

        PluginContext ctx = new PluginContext();
        ctx.setProtocol("kafka");
        // topic is null (not available at constructor interception point)

        InterceptResult result = plugin.intercept(ctx);
        assertTrue("Null topic should still redirect", result.isRedirect());
    }

    @Test
    public void testPluginMetadata() {
        KafkaRedirectPlugin plugin = new KafkaRedirectPlugin();
        assertEquals("kafka-redirect", plugin.getName());
        assertEquals(InterceptTarget.KAFKA, plugin.getTarget());
    }
}
