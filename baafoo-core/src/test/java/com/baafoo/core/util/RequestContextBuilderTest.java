package com.baafoo.core.util;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class RequestContextBuilderTest {

    @Test
    public void buildEmpty() {
        TemplateEngine.RequestContext ctx = TemplateEngine.RequestContext.builder().build();
        assertNull(ctx.getMethod());
        assertNull(ctx.getPath());
        assertNull(ctx.getHost());
        assertNull(ctx.getHeaders());
        assertNull(ctx.getQueryParams());
        assertNull(ctx.getBody());
        assertNull(ctx.getEnvironment());
        assertEquals(0, ctx.getRequestCount());
    }

    @Test
    public void buildWithAllFields() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        Map<String, String> query = new HashMap<>();
        query.put("page", "1");

        TemplateEngine.RequestContext ctx = TemplateEngine.RequestContext.builder()
                .method("POST")
                .path("/api/test")
                .host("example.com")
                .headers(headers)
                .queryParams(query)
                .body("{\"key\":\"value\"}")
                .environment("staging-a")
                .requestCount(5)
                .build();

        assertEquals("POST", ctx.getMethod());
        assertEquals("/api/test", ctx.getPath());
        assertEquals("example.com", ctx.getHost());
        assertEquals("application/json", ctx.getHeaders().get("Content-Type"));
        assertEquals("1", ctx.getQueryParams().get("page"));
        assertEquals("{\"key\":\"value\"}", ctx.getBody());
        assertEquals("staging-a", ctx.getEnvironment());
        assertEquals(5, ctx.getRequestCount());
    }

    @Test
    public void settersWork() {
        TemplateEngine.RequestContext ctx = new TemplateEngine.RequestContext();
        ctx.setMethod("GET");
        ctx.setPath("/x");
        ctx.setHost("h");
        ctx.setBody("b");
        ctx.setEnvironment("env");
        ctx.setRequestCount(3);

        assertEquals("GET", ctx.getMethod());
        assertEquals("/x", ctx.getPath());
        assertEquals("h", ctx.getHost());
        assertEquals("b", ctx.getBody());
        assertEquals("env", ctx.getEnvironment());
        assertEquals(3, ctx.getRequestCount());
    }

    @Test
    public void sixArgConstructor() {
        TemplateEngine.RequestContext ctx = new TemplateEngine.RequestContext(
                "PUT", "/r", "host", null, null, "body");
        assertEquals("PUT", ctx.getMethod());
        assertEquals("/r", ctx.getPath());
        assertEquals("host", ctx.getHost());
        assertEquals("body", ctx.getBody());
    }

    @Test
    public void sevenArgConstructor() {
        TemplateEngine.RequestContext ctx = new TemplateEngine.RequestContext(
                "DELETE", "/d", "h", null, null, "b", "staging-b");
        assertEquals("DELETE", ctx.getMethod());
        assertEquals("staging-b", ctx.getEnvironment());
    }
}
