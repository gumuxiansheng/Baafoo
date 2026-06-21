package com.baafoo.plugin.feign;

import com.baafoo.plugin.InterceptResult;
import com.baafoo.plugin.InterceptTarget;
import com.baafoo.plugin.PluginContext;
import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;

import static org.junit.Assert.*;

public class FeignPluginTest {

    private FeignPlugin plugin;

    @Before
    public void setUp() {
        plugin = new FeignPlugin();
        plugin.init();
    }

    @Test
    public void testGetName() {
        assertEquals("feign-plugin", plugin.getName());
    }

    @Test
    public void testGetTarget() {
        assertEquals(InterceptTarget.FEIGN, plugin.getTarget());
    }

    @Test
    public void testInitRegistersDefaultStubs() {
        assertTrue(plugin.getStubCount() >= 4);
    }

    @Test
    public void testInterceptStubbedGet() {
        PluginContext ctx = buildContext("http", "httpbin.org", 80, "GET", "/get");
        InterceptResult result = plugin.intercept(ctx);

        assertTrue(result.isStubbed());
        assertEquals(200, result.getStatusCode());
        assertNotNull(result.getResponseData());
        assertTrue(result.getResponseData().length > 0);
        assertEquals("true", result.getResponseHeaders().get("X-Baafoo-Stub"));
        assertEquals("feign-plugin", result.getResponseHeaders().get("X-Baafoo-Plugin"));
    }

    @Test
    public void testInterceptStubbedPost() {
        PluginContext ctx = buildContext("http", "httpbin.org", 80, "POST", "/post");
        InterceptResult result = plugin.intercept(ctx);

        assertTrue(result.isStubbed());
        assertEquals(201, result.getStatusCode());
        assertNotNull(result.getResponseData());
    }

    @Test
    public void testInterceptStubbedDelete() {
        PluginContext ctx = buildContext("http", "httpbin.org", 80, "DELETE", "/delete");
        InterceptResult result = plugin.intercept(ctx);

        assertTrue(result.isStubbed());
        assertEquals(204, result.getStatusCode());
    }

    @Test
    public void testInterceptPassthrough() {
        PluginContext ctx = buildContext("http", "httpbin.org", 80, "GET", "/status/404");
        ctx.setOriginalCall(new Callable<InterceptResult>() {
            @Override
            public InterceptResult call() {
                return InterceptResult.passthrough();
            }
        });

        InterceptResult result = plugin.intercept(ctx);
        assertFalse(result.isStubbed());
    }

    @Test
    public void testInterceptNoOriginalCall() {
        PluginContext ctx = buildContext("http", "httpbin.org", 80, "GET", "/unknown");
        InterceptResult result = plugin.intercept(ctx);
        assertFalse(result.isStubbed());
    }

    @Test
    public void testInterceptOriginalCallFailure() {
        PluginContext ctx = buildContext("http", "httpbin.org", 80, "GET", "/unknown");
        ctx.setOriginalCall(new Callable<InterceptResult>() {
            @Override
            public InterceptResult call() throws Exception {
                throw new RuntimeException("Connection refused");
            }
        });

        InterceptResult result = plugin.intercept(ctx);
        assertTrue(result.isStubbed());
        assertEquals(500, result.getStatusCode());
        assertTrue(result.getErrorMessage().contains("Connection refused"));
    }

    @Test
    public void testRegisterCustomStub() {
        int before = plugin.getStubCount();
        Map<String, String> headers = new HashMap<String, String>();
        headers.put("X-Custom", "test");
        plugin.registerStub("GET", "/api/v1/test", 200, "{\"custom\":true}", headers);

        assertEquals(before + 1, plugin.getStubCount());

        PluginContext ctx = buildContext("http", "api.test.com", 80, "GET", "/api/v1/test");
        InterceptResult result = plugin.intercept(ctx);

        assertTrue(result.isStubbed());
        assertEquals(200, result.getStatusCode());
        assertEquals("test", result.getResponseHeaders().get("X-Custom"));
    }

    @Test
    public void testRemoveStub() {
        plugin.registerStub("GET", "/temp", 200, "temp", null);
        int withTemp = plugin.getStubCount();
        plugin.removeStub("GET", "/temp");
        assertEquals(withTemp - 1, plugin.getStubCount());

        PluginContext ctx = buildContext("http", "test.com", 80, "GET", "/temp");
        InterceptResult result = plugin.intercept(ctx);
        assertFalse(result.isStubbed());
    }

    @Test
    public void testInterceptCount() {
        long before = plugin.getInterceptCount();
        plugin.intercept(buildContext("http", "httpbin.org", 80, "GET", "/get"));
        plugin.intercept(buildContext("http", "httpbin.org", 80, "POST", "/post"));
        assertEquals(before + 2, plugin.getInterceptCount());
    }

    @Test
    public void testDestroy() {
        plugin.destroy();
        assertEquals(0, plugin.getStubCount());

        PluginContext ctx = buildContext("http", "httpbin.org", 80, "GET", "/get");
        InterceptResult result = plugin.intercept(ctx);
        assertFalse(result.isStubbed());
    }

    @Test
    public void testStubResponseBodyContent() {
        PluginContext ctx = buildContext("http", "httpbin.org", 80, "GET", "/get");
        InterceptResult result = plugin.intercept(ctx);

        String body = new String(result.getResponseData());
        assertTrue(body.contains("stub.baafoo.local"));
    }

    private PluginContext buildContext(String protocol, String host, int port,
                                       String method, String path) {
        PluginContext ctx = new PluginContext();
        ctx.setProtocol(protocol);
        ctx.setHost(host);
        ctx.setPort(port);

        Map<String, String> headers = new HashMap<String, String>();
        headers.put("X-Feign-Method", method);
        headers.put("X-Feign-Path", path);
        ctx.setHeaders(headers);

        return ctx;
    }
}
