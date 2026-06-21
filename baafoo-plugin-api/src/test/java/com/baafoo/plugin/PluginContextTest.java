package com.baafoo.plugin;

import org.junit.Test;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import static org.junit.Assert.*;

public class PluginContextTest {

    @Test
    public void testDefaults() {
        PluginContext ctx = new PluginContext();
        assertTrue(ctx.getHeaders().isEmpty());
        assertNotNull(ctx.getRequestData());
        assertEquals(0, ctx.getRequestData().length);
        assertEquals(0, ctx.getConditionIndex());
        assertFalse(ctx.isRecording());
    }

    @Test
    public void testGettersAndSetters() {
        PluginContext ctx = new PluginContext();
        ctx.setProtocol("http");
        ctx.setHost("api.test.com");
        ctx.setPort(8084);
        ctx.setServiceName("my-svc");
        ctx.setRuleId("rule-1");
        ctx.setRuleName("test rule");
        ctx.setResponseName("成功");
        ctx.setConditionIndex(2);
        ctx.setRecording(true);

        Map<String, String> headers = new HashMap<String, String>();
        headers.put("Accept", "application/json");
        ctx.setHeaders(headers);

        byte[] data = "request-body".getBytes();
        ctx.setRequestData(data);

        Callable<InterceptResult> callable = new Callable<InterceptResult>() {
            @Override
            public InterceptResult call() {
                return InterceptResult.passthrough();
            }
        };
        ctx.setOriginalCall(callable);

        assertEquals("http", ctx.getProtocol());
        assertEquals("api.test.com", ctx.getHost());
        assertEquals(8084, ctx.getPort());
        assertEquals("my-svc", ctx.getServiceName());
        assertEquals("rule-1", ctx.getRuleId());
        assertEquals("test rule", ctx.getRuleName());
        assertEquals("成功", ctx.getResponseName());
        assertEquals(2, ctx.getConditionIndex());
        assertTrue(ctx.isRecording());
        assertEquals("application/json", ctx.getHeaders().get("Accept"));
        assertArrayEquals(data, ctx.getRequestData());
        assertNotNull(ctx.getOriginalCall());
    }

    @Test
    public void testToString() {
        PluginContext ctx = new PluginContext();
        ctx.setProtocol("http");
        ctx.setHost("host");
        ctx.setPort(1234);
        assertTrue(ctx.toString().contains("http"));
        assertTrue(ctx.toString().contains("host"));
        assertTrue(ctx.toString().contains("1234"));
    }

    @Test
    public void testPluginConfigDefault() {
        PluginContext ctx = new PluginContext();
        assertNotNull(ctx.getPluginConfig());
        assertTrue(ctx.getPluginConfig().isEmpty());
    }

    @Test
    public void testPluginConfigSetter() {
        PluginContext ctx = new PluginContext();
        Map<String, Object> config = new HashMap<String, Object>();
        config.put("brokerPort", 9005);
        config.put("enabled", true);
        ctx.setPluginConfig(config);
        assertEquals(9005, ctx.getPluginConfig().get("brokerPort"));
        assertEquals(true, ctx.getPluginConfig().get("enabled"));
    }

    @Test
    public void testProtocolSpecificFieldsDefault() {
        PluginContext ctx = new PluginContext();
        assertNull(ctx.getTopic());
        assertNull(ctx.getPartition());
        assertNull(ctx.getKey());
        assertNull(ctx.getTenant());
        assertNull(ctx.getNamespace());
        assertNull(ctx.getDestination());
        assertNull(ctx.getMessageType());
        assertNull(ctx.getMethod());
        assertNull(ctx.getPath());
        assertNull(ctx.getQueryParams());
    }

    @Test
    public void testProtocolSpecificFieldsKafka() {
        PluginContext ctx = new PluginContext();
        ctx.setProtocol("kafka");
        ctx.setTopic("orders");
        ctx.setPartition(3);
        ctx.setKey("order-123");
        assertEquals("orders", ctx.getTopic());
        assertEquals(Integer.valueOf(3), ctx.getPartition());
        assertEquals("order-123", ctx.getKey());
    }

    @Test
    public void testProtocolSpecificFieldsPulsar() {
        PluginContext ctx = new PluginContext();
        ctx.setProtocol("pulsar");
        ctx.setTenant("public");
        ctx.setNamespace("default");
        assertEquals("public", ctx.getTenant());
        assertEquals("default", ctx.getNamespace());
    }

    @Test
    public void testProtocolSpecificFieldsJms() {
        PluginContext ctx = new PluginContext();
        ctx.setProtocol("jms");
        ctx.setDestination("queue.orders");
        ctx.setMessageType("text");
        assertEquals("queue.orders", ctx.getDestination());
        assertEquals("text", ctx.getMessageType());
    }

    @Test
    public void testProtocolSpecificFieldsHttp() {
        PluginContext ctx = new PluginContext();
        ctx.setProtocol("http");
        ctx.setMethod("POST");
        ctx.setPath("/api/orders");
        Map<String, String> params = new HashMap<String, String>();
        params.put("page", "1");
        ctx.setQueryParams(params);
        assertEquals("POST", ctx.getMethod());
        assertEquals("/api/orders", ctx.getPath());
        assertEquals("1", ctx.getQueryParams().get("page"));
    }

    @Test
    public void testToStringWithProtocolFields() {
        PluginContext ctx = new PluginContext();
        ctx.setProtocol("kafka");
        ctx.setHost("broker");
        ctx.setPort(9092);
        ctx.setTopic("events");
        ctx.setPartition(0);
        String s = ctx.toString();
        assertTrue(s.contains("kafka"));
        assertTrue(s.contains("events"));
        assertTrue(s.contains("partition=0"));
    }

    @Test
    public void testToStringOmitsNullProtocolFields() {
        PluginContext ctx = new PluginContext();
        ctx.setProtocol("http");
        ctx.setHost("host");
        ctx.setPort(80);
        String s = ctx.toString();
        assertFalse(s.contains("topic"));
        assertFalse(s.contains("partition"));
        assertFalse(s.contains("tenant"));
    }
}
