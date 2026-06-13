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
}
