package com.baafoo.plugin.feign;

import com.baafoo.plugin.ConnectAdvice;
import com.baafoo.plugin.ConnectContext;
import com.baafoo.plugin.InterceptTarget;
import com.baafoo.plugin.PluginEvent;
import com.baafoo.plugin.RequestAdvice;
import com.baafoo.plugin.RequestContext;
import com.baafoo.plugin.ResponseAdvice;
import com.baafoo.plugin.ResponseContext;
import org.junit.Before;
import org.junit.Test;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class FeignPluginTest {

    private FeignPlugin plugin;

    @Before
    public void setUp() {
        plugin = new FeignPlugin();
        plugin.init();
    }

    // ---- Metadata ----

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

    // ---- onConnect (new API) ----

    @Test
    public void testOnConnectPassthrough() {
        ConnectContext ctx = new ConnectContext("http", "httpbin.org", 80, null, null, null, null);
        ConnectAdvice advice = plugin.onConnect(ctx);

        assertTrue(advice.isPassthrough());
        assertFalse(advice.isRedirect());
        assertFalse(advice.isBlock());
    }

    // ---- onRequest (new API) ----

    @Test
    public void testOnRequestStubbedGet() {
        RequestContext ctx = newRequestContext("GET", "/get");
        RequestAdvice advice = plugin.onRequest(ctx);

        assertEquals(RequestAdvice.Action.SHORTCIRCUIT, advice.getAction());
        assertEquals(200, advice.getShortcutStatusCode());
        assertNotNull(advice.getShortcutBody());
        assertTrue(advice.getShortcutBody().length > 0);
        assertEquals("true", advice.getShortcutHeaders().get("X-Baafoo-Stub"));
        assertEquals("feign-plugin", advice.getShortcutHeaders().get("X-Baafoo-Plugin"));
    }

    @Test
    public void testOnRequestStubbedPost() {
        RequestContext ctx = newRequestContext("POST", "/post");
        RequestAdvice advice = plugin.onRequest(ctx);

        assertEquals(RequestAdvice.Action.SHORTCIRCUIT, advice.getAction());
        assertEquals(201, advice.getShortcutStatusCode());
        assertNotNull(advice.getShortcutBody());
    }

    @Test
    public void testOnRequestStubbedPut() {
        RequestContext ctx = newRequestContext("PUT", "/put");
        RequestAdvice advice = plugin.onRequest(ctx);

        assertEquals(RequestAdvice.Action.SHORTCIRCUIT, advice.getAction());
        assertEquals(200, advice.getShortcutStatusCode());
    }

    @Test
    public void testOnRequestStubbedDelete() {
        RequestContext ctx = newRequestContext("DELETE", "/delete");
        RequestAdvice advice = plugin.onRequest(ctx);

        assertEquals(RequestAdvice.Action.SHORTCIRCUIT, advice.getAction());
        assertEquals(204, advice.getShortcutStatusCode());
    }

    @Test
    public void testOnRequestNoStubProceed() {
        RequestContext ctx = newRequestContext("GET", "/status/404");
        RequestAdvice advice = plugin.onRequest(ctx);

        assertEquals(RequestAdvice.Action.CONTINUE, advice.getAction());
    }

    @Test
    public void testOnRequestCustomStub() {
        Map<String, String> headers = new HashMap<String, String>();
        headers.put("X-Custom", "test");
        plugin.registerStub("GET", "/api/v1/test", 200, "{\"custom\":true}", headers);

        RequestContext ctx = newRequestContext("GET", "/api/v1/test");
        RequestAdvice advice = plugin.onRequest(ctx);

        assertEquals(RequestAdvice.Action.SHORTCIRCUIT, advice.getAction());
        assertEquals(200, advice.getShortcutStatusCode());
        assertEquals("test", advice.getShortcutHeaders().get("X-Custom"));
    }

    @Test
    public void testOnRequestRemoveStub() {
        plugin.registerStub("GET", "/temp", 200, "temp", null);
        plugin.removeStub("GET", "/temp");

        RequestContext ctx = newRequestContext("GET", "/temp");
        RequestAdvice advice = plugin.onRequest(ctx);

        assertEquals(RequestAdvice.Action.CONTINUE, advice.getAction());
    }

    @Test
    public void testOnRequestStubBodyContent() {
        RequestContext ctx = newRequestContext("GET", "/get");
        RequestAdvice advice = plugin.onRequest(ctx);

        String body = new String(advice.getShortcutBody(), StandardCharsets.UTF_8);
        assertTrue(body.contains("stub.baafoo.local"));
    }

    @Test
    public void testOnRequestCount() {
        long before = plugin.getInterceptCount();
        plugin.onRequest(newRequestContext("GET", "/get"));
        plugin.onRequest(newRequestContext("POST", "/post"));
        assertEquals(before + 2, plugin.getInterceptCount());
    }

    // ---- onResponse (new API) ----

    @Test
    public void testOnResponseStubbedProceed() {
        RequestContext reqCtx = newRequestContext("GET", "/get");
        ResponseContext ctx = new ResponseContext("http", null, null, 200,
                Collections.<String, String>emptyMap(), new byte[0], reqCtx, true);

        ResponseAdvice advice = plugin.onResponse(ctx);
        assertEquals(ResponseAdvice.Action.CONTINUE, advice.getAction());
    }

    @Test
    public void testOnResponseNonStubbedAugment() {
        RequestContext reqCtx = newRequestContext("GET", "/status/404");
        ResponseContext ctx = new ResponseContext("http", null, null, 404,
                Collections.<String, String>emptyMap(), new byte[0], reqCtx, false);

        ResponseAdvice advice = plugin.onResponse(ctx);
        assertEquals(ResponseAdvice.Action.AUGMENT, advice.getAction());
        assertEquals("feign-plugin", advice.getAdditionalHeaders().get("X-Baafoo-Plugin"));
    }

    // ---- onEvent (new API) ----

    @Test
    public void testOnEventNoThrow() {
        PluginEvent event1 = PluginEvent.requestReceived("http", "GET", "/get");
        plugin.onEvent(event1); // should not throw

        PluginEvent event2 = PluginEvent.ruleMatched("rule-1", "test rule", "http");
        plugin.onEvent(event2); // should not throw

        PluginEvent event3 = PluginEvent.responseSent("http", 200, 50);
        plugin.onEvent(event3); // should not throw
    }

    // ---- Stub management ----

    @Test
    public void testRegisterCustomStub() {
        int before = plugin.getStubCount();
        Map<String, String> headers = new HashMap<String, String>();
        headers.put("X-Custom", "test");
        plugin.registerStub("GET", "/api/v1/test", 200, "{\"custom\":true}", headers);

        assertEquals(before + 1, plugin.getStubCount());
    }

    @Test
    public void testRemoveStub() {
        plugin.registerStub("GET", "/temp", 200, "temp", null);
        int withTemp = plugin.getStubCount();
        plugin.removeStub("GET", "/temp");
        assertEquals(withTemp - 1, plugin.getStubCount());
    }

    // ---- Lifecycle ----

    @Test
    public void testDestroyClearsStubs() {
        plugin.destroy();
        assertEquals(0, plugin.getStubCount());

        // After destroy, onRequest should still proceed (no stubs)
        RequestContext ctx = newRequestContext("GET", "/get");
        RequestAdvice advice = plugin.onRequest(ctx);
        assertEquals(RequestAdvice.Action.CONTINUE, advice.getAction());
    }

    // ---- Helpers ----

    private RequestContext newRequestContext(String method, String path) {
        return new RequestContext("http", method, path,
                Collections.<String, String>emptyMap(),
                Collections.<String, String>emptyMap(),
                new byte[0], "httpbin.org", 80, null, false);
    }
}
