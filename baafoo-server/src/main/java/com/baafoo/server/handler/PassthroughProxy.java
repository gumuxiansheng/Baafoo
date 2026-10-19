package com.baafoo.server.handler;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.http.*;
import io.netty.handler.ssl.SslContext;
import io.netty.handler.ssl.SslContextBuilder;
import io.netty.handler.ssl.SslHandler;
import io.netty.handler.ssl.util.InsecureTrustManagerFactory;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;
import io.netty.util.concurrent.Promise;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Non-blocking HTTP passthrough proxy using Netty HttpClient.
 *
 * <p>Replaces the previous blocking HttpURLConnection implementation.
 * Uses Netty's event loop for async request forwarding, eliminating
 * the thread pool dependency and aligning with Netty's threading model.</p>
 */
public class PassthroughProxy {

    private static final Logger log = LoggerFactory.getLogger(PassthroughProxy.class);

    /**
     * Max aggregated response body size for the outbound passthrough pipeline.
     * Must match the inbound {@code HttpObjectAggregator} limit configured in
     * {@code BaafooServer} (10 MiB) — otherwise downstream responses larger
     * than 64 KiB would fail with {@code TooLongFrameException} and surface
     * to the client as 502 BAD_GATEWAY. Previously hardcoded to 65536, which
     * silently broke PASSTHROUGH/RECORD modes for any non-trivial response.
     */
    private static final int MAX_RESPONSE_BYTES = 30 * 1024 * 1024;

    static final Set<String> HOP_BY_HOP_HEADERS = new HashSet<String>();
    static {
        HOP_BY_HOP_HEADERS.add("connection");
        HOP_BY_HOP_HEADERS.add("keep-alive");
        HOP_BY_HOP_HEADERS.add("proxy-authenticate");
        HOP_BY_HOP_HEADERS.add("proxy-authorization");
        HOP_BY_HOP_HEADERS.add("te");
        HOP_BY_HOP_HEADERS.add("trailers");
        HOP_BY_HOP_HEADERS.add("transfer-encoding");
        HOP_BY_HOP_HEADERS.add("upgrade");
    }

    /**
     * Default overall timeout (seconds) for a single passthrough attempt.
     * Backstop so the client always gets a bounded response (502) instead of
     * waiting forever. Overridable via the {@code baafoo.passthrough.timeoutSec}
     * system property (e.g. {@code -Dbaafoo.passthrough.timeoutSec=60}).
     */
    public static final int DEFAULT_PASSTHROUGH_TIMEOUT_SEC = 30;

    /**
     * Shared executor for outbound connect setup, <b>including DNS resolution</b>.
     *
     * <p>Netty's default {@code AddressResolver} performs a blocking
     * {@code InetAddress} lookup on the thread that calls {@code connect()}.
     * {@code HttpStubHandler} invokes {@link #forward} from a Netty EventLoop,
     * so resolving an unresolvable or slow-to-resolve backend hostname — a
     * common intranet situation where the server cannot resolve app-side
     * service names — would freeze that EventLoop for the entire DNS timeout,
     * stalling every other connection served by it (agent heartbeats and
     * other stubs share the same worker group). Note that
     * {@code CONNECT_TIMEOUT_MILLIS} does NOT cover the DNS phase.</p>
     *
     * <p>Must be static/shared: a {@code PassthroughProxy} instance is created
     * per inbound connection by {@code HttpStubHandler}.</p>
     */
    private static final ExecutorService CONNECT_EXECUTOR = Executors.newFixedThreadPool(8, new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "baafoo-passthrough-connect");
            t.setDaemon(true);
            return t;
        }
    });

    /** Shared scheduler for the overall passthrough timeout backstop. */
    private static final ScheduledExecutorService TIMEOUT_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(new ThreadFactory() {
                @Override
                public Thread newThread(Runnable r) {
                    Thread t = new Thread(r, "baafoo-passthrough-timeout");
                    t.setDaemon(true);
                    return t;
                }
            });

    private final int passthroughTimeoutSec;

    private final EventLoopGroup eventLoopGroup;
    private final Bootstrap bootstrap;
    private volatile SslContext sslContext;
    private volatile SslContext insecureSslContext;
    private final boolean sslVerifyDisabled;

    public PassthroughProxy(EventLoopGroup eventLoopGroup) {
        this(eventLoopGroup, false);
    }

    public PassthroughProxy(EventLoopGroup eventLoopGroup, boolean sslVerifyDisabled) {
        this(eventLoopGroup, sslVerifyDisabled,
                Integer.getInteger("baafoo.passthrough.timeoutSec", DEFAULT_PASSTHROUGH_TIMEOUT_SEC));
    }

    public PassthroughProxy(EventLoopGroup eventLoopGroup, boolean sslVerifyDisabled, int passthroughTimeoutSec) {
        this.eventLoopGroup = eventLoopGroup;
        this.sslVerifyDisabled = sslVerifyDisabled;
        this.passthroughTimeoutSec = passthroughTimeoutSec > 0
                ? passthroughTimeoutSec : DEFAULT_PASSTHROUGH_TIMEOUT_SEC;
        if (sslVerifyDisabled) {
            log.warn("PassthroughProxy initialized with SSL verification DISABLED — this is insecure for production use");
        }
        this.bootstrap = new Bootstrap()
                .group(eventLoopGroup)
                .channel(io.netty.channel.socket.nio.NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, 5000)
                .option(ChannelOption.SO_KEEPALIVE, true);
    }

    private SslContext getSslContext() throws Exception {
        if (sslVerifyDisabled) {
            return getInsecureSslContext();
        }
        if (sslContext == null) {
            synchronized (this) {
                if (sslContext == null) {
                    sslContext = SslContextBuilder.forClient().build();
                }
            }
        }
        return sslContext;
    }

    private SslContext getInsecureSslContext() throws Exception {
        if (insecureSslContext == null) {
            synchronized (this) {
                if (insecureSslContext == null) {
                    log.warn("SECURITY WARNING: SSL certificate verification is DISABLED. " +
                            "This is insecure and should only be used in development/testing environments.");
                    insecureSslContext = SslContextBuilder.forClient()
                            .trustManager(InsecureTrustManagerFactory.INSTANCE)
                            .build();
                }
            }
        }
        return insecureSslContext;
    }

    /**
     * Forward an HTTP request to the downstream server asynchronously.
     *
     * @return CompletableFuture that completes with the downstream response
     */
    public CompletableFuture<PassthroughResult> forward(String method, String host, int port,
                                                         String path, Map<String, String> queryParams,
                                                         Map<String, String> headers, String requestBody) {
        String protocol = determineProtocol(host, port, headers);
        boolean isHttps = "https".equals(protocol);
        int targetPort = port > 0 ? port : (isHttps ? 443 : 80);

        final CompletableFuture<PassthroughResult> future = new CompletableFuture<PassthroughResult>();
        // Backend channel reference so the timeout backstop can release it.
        final AtomicReference<Channel> backendChannelRef = new AtomicReference<Channel>();

        // Backstop: never leave the caller (and ultimately the client) waiting
        // forever — e.g. when connect setup is queued behind stuck DNS lookups
        // on CONNECT_EXECUTOR, or any unforeseen stall slips past the 5s connect
        // timeout and the 10s read timeout.
        final ScheduledFuture<?> timeoutTask = TIMEOUT_SCHEDULER.schedule(new Runnable() {
            @Override
            public void run() {
                if (future.completeExceptionally(new PassthroughTimeoutException(
                        "Passthrough to " + host + ":" + targetPort
                                + " timed out after " + passthroughTimeoutSec + "s"))) {
                    Channel backend = backendChannelRef.get();
                    if (backend != null) {
                        backend.close();
                    }
                }
            }
        }, passthroughTimeoutSec, TimeUnit.SECONDS);

        try {
            Promise<PassthroughResult> promise = eventLoopGroup.next().newPromise();
            Bootstrap b = bootstrap.clone();

            b.handler(new ChannelInitializer<Channel>() {
                @Override
                protected void initChannel(Channel ch) throws Exception {
                    ChannelPipeline p = ch.pipeline();
                    if (isHttps) {
                        if (sslVerifyDisabled) {
                            log.warn("SSL certificate verification is DISABLED for {}:{} - only use in test environments", host, targetPort);
                        }
                        p.addLast(new SslHandler(getSslContext().newEngine(ch.alloc(), host, targetPort)));
                    }
                    p.addLast(new HttpClientCodec());
                    p.addLast(new HttpObjectAggregator(MAX_RESPONSE_BYTES));
                    p.addLast(new ReadTimeoutHandler(10, TimeUnit.SECONDS));
                    p.addLast(new PassthroughResponseHandler(promise));
                }
            });

            // Build URL
            StringBuilder urlBuilder = new StringBuilder();
            urlBuilder.append(path != null ? path : "/");
            if (queryParams != null && !queryParams.isEmpty()) {
                urlBuilder.append("?");
                boolean first = true;
                for (Map.Entry<String, String> entry : queryParams.entrySet()) {
                    if (!first) urlBuilder.append("&");
                    try {
                        urlBuilder.append(java.net.URLEncoder.encode(entry.getKey(), "UTF-8"))
                                .append("=").append(java.net.URLEncoder.encode(entry.getValue(), "UTF-8"));
                    } catch (java.io.UnsupportedEncodingException e) {
                        // UTF-8 is always supported
                        urlBuilder.append(entry.getKey()).append("=").append(entry.getValue());
                    }
                    first = false;
                }
            }

            // Build request
            FullHttpRequest request = new DefaultFullHttpRequest(
                    HttpVersion.HTTP_1_1, HttpMethod.valueOf(method),
                    urlBuilder.toString());

            // Set headers
            request.headers().set(HttpHeaderNames.HOST, host + ":" + targetPort);
            for (Map.Entry<String, String> entry : headers.entrySet()) {
                String key = entry.getKey();
                if (!HOP_BY_HOP_HEADERS.contains(key.toLowerCase()) &&
                    !"host".equalsIgnoreCase(key)) {
                    request.headers().set(key, entry.getValue());
                }
            }

            // Set body
            if (requestBody != null && !requestBody.isEmpty() &&
                ("POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method))) {
                byte[] bodyBytes = requestBody.getBytes(StandardCharsets.UTF_8);
                request.content().writeBytes(bodyBytes);
                request.headers().set(HttpHeaderNames.CONTENT_LENGTH, bodyBytes.length);
            } else {
                request.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
            }

            // Connect and send. The whole connect setup (including DNS
            // resolution, which Netty performs synchronously on the calling
            // thread) runs on CONNECT_EXECUTOR to keep it off the Netty
            // EventLoop — see the field's javadoc.
            final FullHttpRequest sendRequest = request;
            CONNECT_EXECUTOR.execute(new Runnable() {
                @Override
                public void run() {
                    if (future.isDone()) {
                        // Already timed out while queued — nothing to send.
                        sendRequest.release();
                        return;
                    }
                    try {
                        ChannelFuture connectFuture = b.connect(host, targetPort);
                        connectFuture.addListener(new ChannelFutureListener() {
                            @Override
                            public void operationComplete(ChannelFuture cf) throws Exception {
                                if (cf.isSuccess()) {
                                    backendChannelRef.set(cf.channel());
                                    // Propagate write failures — otherwise a failed
                                    // write would leave the promise (and the client
                                    // request) pending forever.
                                    ChannelFuture writeFuture = cf.channel().writeAndFlush(sendRequest);
                                    writeFuture.addListener(new ChannelFutureListener() {
                                        @Override
                                        public void operationComplete(ChannelFuture wf) {
                                            if (!wf.isSuccess() && !promise.isDone()) {
                                                promise.setFailure(wf.cause());
                                            }
                                        }
                                    });
                                } else {
                                    // release request ByteBuf on connect failure to prevent leak
                                    sendRequest.release();
                                    Throwable cause = cf.cause();
                                    if (cause == null) {
                                        // Connection failed without explicit cause - likely network issue
                                        cause = new Exception("Connection to " + host + ":" + targetPort + " failed (no additional details)");
                                    }
                                    log.error("Passthrough connection failed: {}:{} - {}", host, targetPort, cause.getMessage());
                                    promise.setFailure(cause);
                                }
                            }
                        });
                    } catch (Throwable t) {
                        if (!promise.isDone()) {
                            sendRequest.release();
                            promise.setFailure(t);
                        }
                    }
                }
            });

            promise.addListener(new GenericFutureListener<Future<PassthroughResult>>() {
                @Override
                public void operationComplete(Future<PassthroughResult> f) throws Exception {
                    timeoutTask.cancel(false);
                    if (f.isSuccess()) {
                        future.complete(f.get());
                    } else {
                        future.completeExceptionally(f.cause());
                    }
                }
            });

        } catch (Exception e) {
            timeoutTask.cancel(false);
            future.completeExceptionally(e);
        }

        return future;
    }

    private String determineProtocol(String host, int port, Map<String, String> headers) {
        // H-8: only honour X-Forwarded-Proto when its value is exactly "http"
        // or "https". The previous implementation returned any client-supplied
        // string verbatim, allowing attackers to inject arbitrary scheme
        // values that could later be reflected into logs, redirect URLs, or
        // downstream requests. Trusted-proxy gating would be stricter but
        // requires a config reference this class does not currently hold; the
        // allow-list is the minimum-security fix.
        String forwardedProto = headers.get("X-Forwarded-Proto");
        if ("http".equals(forwardedProto) || "https".equals(forwardedProto)) {
            return forwardedProto;
        }
        if (port == 443) {
            return "https";
        }
        return "http";
    }

    /**
     * Netty ChannelHandler that collects the downstream response and completes the promise.
     */
    private static class PassthroughResponseHandler extends SimpleChannelInboundHandler<FullHttpResponse> {

        private final Promise<PassthroughResult> promise;
        private final long startTime;

        PassthroughResponseHandler(Promise<PassthroughResult> promise) {
            this.promise = promise;
            this.startTime = System.currentTimeMillis();
        }

        @Override
        protected void channelRead0(ChannelHandlerContext ctx, FullHttpResponse response) throws Exception {
            int statusCode = response.status().code();

            // Extract response headers
            Map<String, String> responseHeaders = new HashMap<String, String>();
            for (Map.Entry<String, String> entry : response.headers()) {
                if (entry.getKey() != null && entry.getValue() != null) {
                    responseHeaders.put(entry.getKey(), entry.getValue());
                }
            }

            // Extract response body
            byte[] responseBody = new byte[response.content().readableBytes()];
            response.content().readBytes(responseBody);

            long responseTimeMs = System.currentTimeMillis() - startTime;
            promise.setSuccess(new PassthroughResult(statusCode, responseHeaders, responseBody, responseTimeMs));
            ctx.close();
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            // The backend closed the connection before a complete response
            // arrived (it crashed mid-request, a middlebox dropped the
            // connection, or the target is not an HTTP service). Fail the
            // promise so the client gets a timely 502 instead of waiting
            // forever. ReadTimeoutHandler cannot cover this: IdleStateHandler
            // cancels its task on channelInactive, so no exception is raised.
            if (!promise.isDone()) {
                log.warn("Passthrough backend closed connection before responding: {}",
                        ctx.channel().remoteAddress());
                promise.setFailure(new IOException("Backend closed connection before responding"));
            }
            ctx.fireChannelInactive();
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            if (!promise.isDone()) {
                promise.setFailure(cause);
            }
            ctx.close();
        }
    }

    /**
     * Result of a passthrough HTTP request.
     */
    public static class PassthroughResult {
        public final int statusCode;
        public final Map<String, String> responseHeaders;
        public final byte[] responseBody;
        public final long responseTimeMs;

        PassthroughResult(int statusCode, Map<String, String> responseHeaders, byte[] responseBody, long responseTimeMs) {
            this.statusCode = statusCode;
            this.responseHeaders = responseHeaders;
            this.responseBody = responseBody;
            this.responseTimeMs = responseTimeMs;
        }
    }

    /**
     * Raised when a passthrough attempt exceeds the overall timeout backstop
     * (default 30s, see {@link #DEFAULT_PASSTHROUGH_TIMEOUT_SEC}). Surfaces to
     * the client as a 502 BAD_GATEWAY.
     */
    public static class PassthroughTimeoutException extends IOException {
        public PassthroughTimeoutException(String message) {
            super(message);
        }
    }
}
