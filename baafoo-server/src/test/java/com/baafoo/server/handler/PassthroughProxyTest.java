package com.baafoo.server.handler;

import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.List;

import io.netty.channel.EventLoop;
import io.netty.channel.nio.NioEventLoopGroup;

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

    // ---- forward() end-to-end behaviour against dummy backends ----
    //
    // Regression coverage for the intranet "no response, client times out"
    // failure modes fixed on 2026-10-19:
    //   1. backend closes the connection before responding -> promise must
    //      fail (channelInactive), not stay pending forever
    //   2. DNS resolution must not run on the Netty EventLoop
    //   3. overall timeout backstop must fire and complete the future

    /** Sockets held open by "silent" backends, closed in @After. */
    private static final List<Socket> HELD_SOCKETS = new CopyOnWriteArrayList<Socket>();

    @After
    public void closeHeldSockets() {
        for (Socket socket : HELD_SOCKETS) {
            try {
                socket.close();
            } catch (IOException e) {
                // ignore
            }
        }
        HELD_SOCKETS.clear();
    }

    /**
     * Start a dummy backend on 127.0.0.1.
     * modes: healthy | close | close-after-read | silent
     */
    private static ServerSocket startBackend(final String mode) throws IOException {
        final ServerSocket server = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        Thread acceptor = new Thread(new Runnable() {
            @Override
            public void run() {
                while (!server.isClosed()) {
                    try {
                        final Socket socket = server.accept();
                        if ("close".equals(mode)) {
                            socket.close();
                            continue;
                        }
                        if ("silent".equals(mode)) {
                            HELD_SOCKETS.add(socket);
                            continue;
                        }
                        HELD_SOCKETS.add(socket);
                        Thread reader = new Thread(new Runnable() {
                            @Override
                            public void run() {
                                try {
                                    InputStream in = socket.getInputStream();
                                    StringBuilder sb = new StringBuilder();
                                    int b;
                                    while ((b = in.read()) != -1) {
                                        sb.append((char) b);
                                        if (sb.length() >= 4
                                                && sb.substring(sb.length() - 4).equals("\r\n\r\n")) {
                                            break;
                                        }
                                        if (sb.length() > 65536) {
                                            break;
                                        }
                                    }
                                    if ("close-after-read".equals(mode)) {
                                        socket.close();
                                        return;
                                    }
                                    String body = "{\"ok\":true}";
                                    String response = "HTTP/1.1 200 OK\r\n"
                                            + "Content-Type: application/json\r\n"
                                            + "Content-Length: " + body.length() + "\r\n"
                                            + "Connection: close\r\n\r\n" + body;
                                    OutputStream out = socket.getOutputStream();
                                    out.write(response.getBytes("UTF-8"));
                                    out.flush();
                                    socket.close();
                                } catch (IOException e) {
                                    // backend went away — acceptable for the test
                                }
                            }
                        });
                        reader.setDaemon(true);
                        reader.start();
                    } catch (IOException e) {
                        return;
                    }
                }
            }
        });
        acceptor.setDaemon(true);
        acceptor.start();
        return server;
    }

    private static CompletableFuture<PassthroughProxy.PassthroughResult> forwardGet(
            PassthroughProxy proxy, String host, int port) {
        Map<String, String> headers = new HashMap<String, String>();
        headers.put("Host", host + ":" + port);
        return proxy.forward("GET", host, port, "/api/test",
                new HashMap<String, String>(), headers, null);
    }

    @Test
    public void forward_healthyBackend_returnsResponse() throws Exception {
        NioEventLoopGroup group = new NioEventLoopGroup(1);
        try {
            ServerSocket backend = startBackend("healthy");
            PassthroughProxy proxy = new PassthroughProxy(group);
            PassthroughProxy.PassthroughResult result =
                    forwardGet(proxy, "127.0.0.1", backend.getLocalPort())
                            .get(10, TimeUnit.SECONDS);
            assertEquals(200, result.statusCode);
            assertEquals("{\"ok\":true}", new String(result.responseBody, "UTF-8"));
        } finally {
            group.shutdownGracefully();
        }
    }

    @Test
    public void forward_backendClosesAfterRead_failsFast() throws Exception {
        // Regression: the backend read the request and closed without
        // responding. Before the channelInactive fix the promise (and the
        // client request) stayed pending forever -> client-side timeout.
        NioEventLoopGroup group = new NioEventLoopGroup(1);
        try {
            ServerSocket backend = startBackend("close-after-read");
            CompletableFuture<PassthroughProxy.PassthroughResult> future =
                    forwardGet(new PassthroughProxy(group), "127.0.0.1", backend.getLocalPort());
            try {
                future.get(10, TimeUnit.SECONDS);
                fail("expected exceptional completion when the backend closes before responding");
            } catch (ExecutionException expected) {
                assertTrue("unexpected cause: " + expected.getCause(),
                        expected.getCause() instanceof IOException);
            }
        } finally {
            group.shutdownGracefully();
        }
    }

    @Test
    public void forward_backendClosesImmediately_failsFast() throws Exception {
        // Backend accepts then resets the connection — must fail fast, not hang.
        NioEventLoopGroup group = new NioEventLoopGroup(1);
        try {
            ServerSocket backend = startBackend("close");
            CompletableFuture<PassthroughProxy.PassthroughResult> future =
                    forwardGet(new PassthroughProxy(group), "127.0.0.1", backend.getLocalPort());
            try {
                future.get(10, TimeUnit.SECONDS);
                fail("expected exceptional completion when the backend resets the connection");
            } catch (ExecutionException expected) {
                // any failure is fine — the point is it terminates
                assertNotNull(expected.getCause());
            }
        } finally {
            group.shutdownGracefully();
        }
    }

    @Test
    public void forward_silentBackend_failsAfterReadTimeout() throws Exception {
        // Backend never responds: ReadTimeoutHandler(10s) must still fail the
        // promise so the client receives a 502 instead of hanging.
        NioEventLoopGroup group = new NioEventLoopGroup(1);
        try {
            ServerSocket backend = startBackend("silent");
            PassthroughProxy proxy = new PassthroughProxy(group);
            long start = System.currentTimeMillis();
            try {
                forwardGet(proxy, "127.0.0.1", backend.getLocalPort()).get(20, TimeUnit.SECONDS);
                fail("expected read-timeout failure against a silent backend");
            } catch (ExecutionException expected) {
                assertTrue("unexpected cause: " + expected.getCause(),
                        expected.getCause() instanceof io.netty.handler.timeout.ReadTimeoutException);
            }
            long elapsed = System.currentTimeMillis() - start;
            assertTrue("failed too late: " + elapsed + "ms", elapsed < 19000);
        } finally {
            group.shutdownGracefully();
        }
    }

    @Test
    public void forward_unresolvableHost_doesNotBlockEventLoop() throws Exception {
        // Regression: DNS resolution must not run on the Netty EventLoop.
        // forward() must return promptly even while the lookup is still in
        // flight. With the previous blocking-dns behaviour the latch below
        // would only count down after the (slow) lookup finished — on a
        // network with a slow/timing-out DNS server this stalls every other
        // connection on the same EventLoop.
        NioEventLoopGroup group = new NioEventLoopGroup(2);
        try {
            final PassthroughProxy proxy = new PassthroughProxy(group);
            final String badHost = "baafoo-nonexistent-host.invalid";
            final EventLoop loop = group.next();
            final CountDownLatch forwardReturned = new CountDownLatch(1);
            loop.execute(new Runnable() {
                @Override
                public void run() {
                    forwardGet(proxy, badHost, 8080);
                    forwardReturned.countDown();
                }
            });
            assertTrue("forward() blocked the EventLoop in DNS resolution",
                    forwardReturned.await(3, TimeUnit.SECONDS));
        } finally {
            group.shutdownGracefully();
        }
    }

    @Test
    public void forward_overallTimeoutBackstop_firesWhenBackendStalls() throws Exception {
        // Silent backend accepts and never answers: the 10s ReadTimeout would
        // eventually fire, but the 2s overall backstop must fire first and
        // complete the future exceptionally (client gets a timely 502).
        NioEventLoopGroup group = new NioEventLoopGroup(1);
        try {
            ServerSocket backend = startBackend("silent");
            PassthroughProxy proxy = new PassthroughProxy(group, false, 2);
            long start = System.currentTimeMillis();
            try {
                forwardGet(proxy, "127.0.0.1", backend.getLocalPort()).get(10, TimeUnit.SECONDS);
                fail("expected timeout exception from the overall backstop");
            } catch (ExecutionException expected) {
                assertTrue("unexpected cause: " + expected.getCause(),
                        expected.getCause() instanceof PassthroughProxy.PassthroughTimeoutException);
            }
            long elapsed = System.currentTimeMillis() - start;
            assertTrue("backstop fired too late: " + elapsed + "ms", elapsed < 5000);
        } finally {
            group.shutdownGracefully();
        }
    }

    @Test
    public void forward_overallTimeoutBackstop_defaultIsThirtySeconds() {
        assertEquals(30, PassthroughProxy.DEFAULT_PASSTHROUGH_TIMEOUT_SEC);
    }
}
