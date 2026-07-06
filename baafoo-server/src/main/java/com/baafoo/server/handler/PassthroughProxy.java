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

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

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
    private static final int MAX_RESPONSE_BYTES = 10 * 1024 * 1024;

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

    private final EventLoopGroup eventLoopGroup;
    private final Bootstrap bootstrap;
    private volatile SslContext sslContext;
    private volatile SslContext insecureSslContext;
    private final boolean sslVerifyDisabled;

    public PassthroughProxy(EventLoopGroup eventLoopGroup) {
        this(eventLoopGroup, false);
    }

    public PassthroughProxy(EventLoopGroup eventLoopGroup, boolean sslVerifyDisabled) {
        this.eventLoopGroup = eventLoopGroup;
        this.sslVerifyDisabled = sslVerifyDisabled;
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

        CompletableFuture<PassthroughResult> future = new CompletableFuture<PassthroughResult>();

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

            // Connect and send
            ChannelFuture connectFuture = b.connect(host, targetPort);
            connectFuture.addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture cf) throws Exception {
                    if (cf.isSuccess()) {
                        cf.channel().writeAndFlush(request);
                    } else {
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

            promise.addListener(new GenericFutureListener<Future<PassthroughResult>>() {
                @Override
                public void operationComplete(Future<PassthroughResult> f) throws Exception {
                    if (f.isSuccess()) {
                        future.complete(f.get());
                    } else {
                        future.completeExceptionally(f.cause());
                    }
                }
            });

        } catch (Exception e) {
            future.completeExceptionally(e);
        }

        return future;
    }

    private String determineProtocol(String host, int port, Map<String, String> headers) {
        String forwardedProto = headers.get("X-Forwarded-Proto");
        if (forwardedProto != null && !forwardedProto.isEmpty()) {
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
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
            promise.setFailure(cause);
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
}
