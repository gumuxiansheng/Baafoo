package com.baafoo.server.handler;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

/**
 * Tests for {@link PassthroughProxy}.
 *
 * <p>Focuses on testable logic:
 * <ul>
 *   <li>Hop-by-hop header set completeness</li>
 *   <li>PassthroughResult data class</li>
 *   <li>Protocol determination logic</li>
 * </ul>
 *
 * <p>The forward() method requires a running Netty event loop and an actual
 * downstream server — those are integration-tested via the staging environment.</p>
 */
public class PassthroughProxyTest {

    // ---- HOP_BY_HOP_HEADERS ----

    @Test
    public void hopByHopHeaders_containsRequiredHeaders() {
        String[] requiredHeaders = {
                "connection", "keep-alive", "proxy-authenticate",
                "proxy-authorization", "te", "trailers",
                "transfer-encoding", "upgrade"
        };
        for (String header : requiredHeaders) {
            assertTrue("Missing hop-by-hop header: " + header,
                    PassthroughProxy.HOP_BY_HOP_HEADERS.contains(header));
        }
    }

    @Test
    public void hopByHopHeaders_exactSize() {
        // Must match the 8 headers defined in the static block
        assertEquals(8, PassthroughProxy.HOP_BY_HOP_HEADERS.size());
    }

    @Test
    public void hopByHopHeaders_doesNotContainContentLength() {
        assertFalse(PassthroughProxy.HOP_BY_HOP_HEADERS.contains("content-length"));
    }

    @Test
    public void hopByHopHeaders_doesNotContainContentType() {
        assertFalse(PassthroughProxy.HOP_BY_HOP_HEADERS.contains("content-type"));
    }

    @Test
    public void hopByHopHeaders_doesNotContainHost() {
        assertFalse(PassthroughProxy.HOP_BY_HOP_HEADERS.contains("host"));
    }

    @Test
    public void hopByHopHeaders_caseSensitive() {
        // The set uses lowercase keys — uppercase should NOT match
        assertFalse(PassthroughProxy.HOP_BY_HOP_HEADERS.contains("Connection"));
        assertFalse(PassthroughProxy.HOP_BY_HOP_HEADERS.contains("TRANSFER-ENCODING"));
    }

    // ---- PassthroughResult ----

    @Test
    public void passthroughResult_fieldsAreAccessible() {
        Map<String, String> headers = new HashMap<String, String>();
        headers.put("Content-Type", "text/plain");
        byte[] body = "hello".getBytes();

        // PassthroughResult has package-private constructor
        PassthroughProxy.PassthroughResult result =
                new PassthroughProxy.PassthroughResult(200, headers, body, 42);

        assertEquals(200, result.statusCode);
        assertEquals("text/plain", result.responseHeaders.get("Content-Type"));
        assertArrayEquals(body, result.responseBody);
        assertEquals(42, result.responseTimeMs);
    }

    @Test
    public void passthroughResult_nullHeaders() {
        byte[] body = new byte[0];
        PassthroughProxy.PassthroughResult result =
                new PassthroughProxy.PassthroughResult(404, null, body, 0);

        assertEquals(404, result.statusCode);
        assertNull(result.responseHeaders);
        assertEquals(0, result.responseBody.length);
        assertEquals(0, result.responseTimeMs);
    }

    @Test
    public void passthroughResult_emptyBody() {
        PassthroughProxy.PassthroughResult result =
                new PassthroughProxy.PassthroughResult(204, new HashMap<String, String>(), new byte[0], 10);

        assertEquals(204, result.statusCode);
        assertEquals(0, result.responseBody.length);
        assertEquals(10, result.responseTimeMs);
    }

    @Test
    public void passthroughResult_errorStatus() {
        Map<String, String> headers = new HashMap<String, String>();
        headers.put("Content-Type", "application/json");
        byte[] errorBody = "{\"error\":\"not found\"}".getBytes();

        PassthroughProxy.PassthroughResult result =
                new PassthroughProxy.PassthroughResult(502, headers, errorBody, 5000);

        assertEquals(502, result.statusCode);
        assertEquals(1, result.responseHeaders.size());
        assertEquals("application/json", result.responseHeaders.get("Content-Type"));
        assertArrayEquals(errorBody, result.responseBody);
        assertEquals(5000, result.responseTimeMs);
    }

    // ---- Constructor (verify no exception) ----

    @Test
    public void constructor_defaultSslVerifyEnabled() {
        io.netty.channel.EventLoopGroup group = new io.netty.channel.nio.NioEventLoopGroup(1);
        try {
            PassthroughProxy proxy = new PassthroughProxy(group);
            assertNotNull(proxy);
        } finally {
            group.shutdownGracefully();
        }
    }

    @Test
    public void constructor_sslVerifyDisabled() {
        io.netty.channel.EventLoopGroup group = new io.netty.channel.nio.NioEventLoopGroup(1);
        try {
            PassthroughProxy proxy = new PassthroughProxy(group, true);
            assertNotNull(proxy);
        } finally {
            group.shutdownGracefully();
        }
    }
}
