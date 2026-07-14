package com.baafoo.server.handler;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.baafoo.core.model.RecordingEntry;
import com.baafoo.core.model.Rule;
import com.baafoo.core.util.GrpcCodecUtils;
import com.baafoo.core.util.HexUtils;
import com.baafoo.server.storage.StorageService;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http2.DefaultHttp2DataFrame;
import io.netty.handler.codec.http2.DefaultHttp2Headers;
import io.netty.handler.codec.http2.DefaultHttp2HeadersFrame;
import io.netty.handler.codec.http2.Http2DataFrame;
import io.netty.handler.codec.http2.Http2FrameCodec;
import io.netty.handler.codec.http2.Http2FrameCodecBuilder;
import io.netty.handler.codec.http2.Http2FrameStream;
import io.netty.handler.codec.http2.Http2HeadersFrame;
import io.netty.handler.codec.http2.Http2MultiplexHandler;
import io.netty.handler.codec.http2.Http2Settings;
import io.netty.handler.codec.http2.Http2StreamChannel;
import io.netty.handler.codec.http2.Http2StreamChannelBootstrap;
import io.netty.handler.timeout.ReadTimeoutHandler;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.GenericFutureListener;

/**
 * Non-blocking gRPC passthrough forwarder using Netty HTTP/2 client.
 *
 * <p>Forwards gRPC requests (Unary + all streaming types) to a real backend
 * over HTTP/2 (h2c prior-knowledge, no TLS). The response is piped back to
 * the client frame-by-frame, supporting both single-message (Unary) and
 * multi-message (Server/Client/Bidi streaming) RPCs.</p>
 *
 * <p><b>Stream creation</b>: Uses {@link Http2StreamChannelBootstrap} to open
 * outbound HTTP/2 streams. This is the public API for stream creation;
 * {@code Http2FrameCodec.newStream()} is package-private and cannot be used
 * from this package. {@link Http2MultiplexHandler} must be in the pipeline
 * for the bootstrap to function.</p>
 *
 * <p><b>Handler split</b>: The connection-level handler
 * ({@link GrpcBackendHandler}) opens the stream, sends the request, and
 * handles connection lifecycle (inactive, timeout). The per-stream handler
 * ({@link GrpcStreamHandler}) receives response frames and forwards them to
 * the client. Both run on the same EventLoop (stream channels inherit the
 * parent's EventLoop), so shared state needs no locking.</p>
 *
 * <p><b>Connection model</b>: one outbound TCP connection per forwarded
 * request. Connection pooling is intentionally avoided to keep the
 * implementation simple — gRPC passthrough is primarily used for RECORD
 * sessions where the overhead of per-request connections is acceptable.</p>
 *
 * <p><b>RECORD mode</b>: When {@code record=true}, the request hex and
 * response hex are accumulated and saved to {@link StorageService} as a
 * {@link RecordingEntry} with {@code responseSource=PASSTHROUGH}.</p>
 */
public class GrpcPassthroughForwarder {

    private static final Logger log = LoggerFactory.getLogger(GrpcPassthroughForwarder.class);

    private static final String CONTENT_TYPE_GRPC = "application/grpc";
    private static final String GRPC_STATUS = "grpc-status";
    private static final String GRPC_MESSAGE = "grpc-message";
    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_SECONDS = 30;

    private final EventLoopGroup eventLoopGroup;

    public GrpcPassthroughForwarder(EventLoopGroup eventLoopGroup) {
        this.eventLoopGroup = eventLoopGroup;
    }

    /**
     * Context bundle for a single gRPC forwarding request.
     *
     * <p>Carries all the information needed to forward the request to the
     * backend and pipe the response back to the client, including recording
     * metadata for RECORD mode.</p>
     */
    public static class ForwardContext {
        public final ChannelHandlerContext clientCtx;
        public final Http2FrameStream clientStream;
        public final String targetHost;
        public final int targetPort;
        public final String path;
        public final Map<String, String> requestMetadata;
        public final List<byte[]> requestMessages;
        public final String requestHex;
        public final boolean record;
        public final Rule rule;
        public final String agentId;
        public final String agentIp;
        public final String agentEnvironment;
        public final StorageService storage;

        public ForwardContext(ChannelHandlerContext clientCtx, Http2FrameStream clientStream,
                               String targetHost, int targetPort, String path,
                               Map<String, String> requestMetadata, List<byte[]> requestMessages,
                               String requestHex, boolean record, Rule rule,
                               String agentId, String agentIp, String agentEnvironment,
                               StorageService storage) {
            this.clientCtx = clientCtx;
            this.clientStream = clientStream;
            this.targetHost = targetHost;
            this.targetPort = targetPort;
            this.path = path;
            this.requestMetadata = requestMetadata;
            this.requestMessages = requestMessages;
            this.requestHex = requestHex;
            this.record = record;
            this.rule = rule;
            this.agentId = agentId;
            this.agentIp = agentIp;
            this.agentEnvironment = agentEnvironment;
            this.storage = storage;
        }
    }

    /**
     * Forward a gRPC request to the real backend and pipe the response back
     * to the client.
     *
     * <p>This method is non-blocking. The response is delivered asynchronously
     * via writes to {@code ctx.clientCtx}. If the backend is unreachable,
     * a gRPC UNAVAILABLE(14) error is sent to the client.</p>
     *
     * @param ctx the forwarding context
     */
    public void forward(ForwardContext ctx) {
        Bootstrap b = new Bootstrap();
        b.group(eventLoopGroup)
                .channel(NioSocketChannel.class)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, CONNECT_TIMEOUT_MS)
                .option(ChannelOption.SO_KEEPALIVE, true);

        b.handler(new ChannelInitializer<Channel>() {
            @Override
            protected void initChannel(Channel ch) {
                ch.pipeline().addLast(new ReadTimeoutHandler(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS));
                Http2FrameCodec codec = Http2FrameCodecBuilder.forClient()
                        .initialSettings(Http2Settings.defaultSettings())
                        .build();
                ch.pipeline().addLast(codec);
                // Http2MultiplexHandler is required for Http2StreamChannelBootstrap
                // to open outbound streams. The inbound handler is unused (gRPC
                // clients do not expect server-initiated streams).
                ch.pipeline().addLast(new Http2MultiplexHandler(new ChannelInitializer<Channel>() {
                    @Override
                    protected void initChannel(Channel ch) {
                        // No-op: unexpected inbound stream from server
                    }
                }));
                ch.pipeline().addLast(new GrpcBackendHandler(ctx));
            }
        });

        ChannelFuture connectFuture = b.connect(ctx.targetHost, ctx.targetPort);
        connectFuture.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture future) {
                if (!future.isSuccess()) {
                    log.error("gRPC passthrough connect failed: {}:{} - {}",
                            ctx.targetHost, ctx.targetPort,
                            future.cause() != null ? future.cause().getMessage() : "unknown");
                    sendErrorToClient(ctx, 14, "Backend unavailable");
                } else {
                    // Close the backend connection if the client disconnects
                    // while we are waiting for the response.
                    Channel backendChannel = future.channel();
                    ctx.clientCtx.channel().closeFuture().addListener(new ChannelFutureListener() {
                        @Override
                        public void operationComplete(ChannelFuture cf) {
                            if (backendChannel.isActive()) {
                                backendChannel.close();
                            }
                        }
                    });
                }
            }
        });
    }

    /**
     * Send a gRPC error response to the client. Safe to call from any thread —
     * the write is scheduled on the client's EventLoop.
     */
    private static void sendErrorToClient(ForwardContext ctx, int status, String message) {
        try {
            ctx.clientCtx.executor().execute(new Runnable() {
                @Override
                public void run() {
                    if (!ctx.clientCtx.channel().isActive()) return;
                    DefaultHttp2Headers headers = new DefaultHttp2Headers();
                    headers.status("200");
                    headers.add(HttpHeaderNames.CONTENT_TYPE, CONTENT_TYPE_GRPC);
                    headers.add(GRPC_STATUS, String.valueOf(status));
                    if (message != null && !message.isEmpty()) {
                        headers.add(GRPC_MESSAGE, message);
                    }
                    DefaultHttp2HeadersFrame frame = new DefaultHttp2HeadersFrame(headers, true);
                    frame.stream(ctx.clientStream);
                    ctx.clientCtx.writeAndFlush(frame);
                }
            });
        } catch (Exception e) {
            log.debug("Failed to send gRPC error to client (executor rejected): {}", e.getMessage());
        }
    }

    /**
     * Connection-level handler for the backend HTTP/2 connection.
     *
     * <p>On {@code channelActive}, opens a new outbound HTTP/2 stream via
     * {@link Http2StreamChannelBootstrap} and sends the request (HEADERS +
     * DATA frames). Handles connection-level lifecycle events (inactive,
     * exception/timeout). Response frames are handled by
     * {@link GrpcStreamHandler} on the stream channel.</p>
     *
     * <p>This handler is single-use: it forwards exactly one request and
     * then closes the backend connection.</p>
     */
    static class GrpcBackendHandler extends ChannelInboundHandlerAdapter {

        private final ForwardContext fwdCtx;
        private final long startTime;

        private ChannelHandlerContext parentCtx;
        private Http2StreamChannel streamChannel;

        // Response state — updated by GrpcStreamHandler. Both handlers run on
        // the same EventLoop (stream channels inherit the parent's EventLoop),
        // so no locking is needed.
        boolean responseHeadersSent = false;
        boolean finished = false;
        final List<byte[]> responseMessages = new java.util.ArrayList<byte[]>();
        final StringBuilder responseHex = new StringBuilder();
        int grpcStatus = 0;
        String grpcMessage = "";

        GrpcBackendHandler(ForwardContext fwdCtx) {
            this.fwdCtx = fwdCtx;
            this.startTime = System.currentTimeMillis();
        }

        @Override
        public void channelActive(ChannelHandlerContext ctx) {
            this.parentCtx = ctx;

            // Open a new outbound HTTP/2 stream using the public
            // Http2StreamChannelBootstrap API. Http2FrameCodec.newStream()
            // is package-private in Netty 4.1.x and cannot be used here.
            Http2StreamChannelBootstrap bootstrap = new Http2StreamChannelBootstrap(ctx.channel());
            bootstrap.handler(new ChannelInitializer<Channel>() {
                @Override
                protected void initChannel(Channel ch) {
                    ch.pipeline().addLast(new GrpcStreamHandler(fwdCtx, GrpcBackendHandler.this));
                }
            });

            Future<Http2StreamChannel> streamFuture = bootstrap.open();
            streamFuture.addListener(new GenericFutureListener<Future<Http2StreamChannel>>() {
                @Override
                public void operationComplete(Future<Http2StreamChannel> future) {
                    if (!future.isSuccess()) {
                        log.error("Failed to open HTTP/2 stream for gRPC passthrough: {} - {}",
                                fwdCtx.path,
                                future.cause() != null ? future.cause().getMessage() : "unknown");
                        finishWithError(13, "Failed to open HTTP/2 stream");
                        return;
                    }
                    streamChannel = future.getNow();
                    sendRequest();
                }
            });
        }

        private void sendRequest() {
            // Build request headers
            DefaultHttp2Headers headers = new DefaultHttp2Headers();
            headers.method("POST");
            headers.path(fwdCtx.path);
            headers.scheme("http");
            headers.authority(fwdCtx.targetHost + ":" + fwdCtx.targetPort);
            headers.add(HttpHeaderNames.CONTENT_TYPE, CONTENT_TYPE_GRPC);
            headers.add("te", "trailers");

            // Copy relevant metadata from the original request (skip pseudo-headers
            // and headers we have already set)
            if (fwdCtx.requestMetadata != null) {
                for (Map.Entry<String, String> entry : fwdCtx.requestMetadata.entrySet()) {
                    String key = entry.getKey();
                    if (key == null) continue;
                    String lowerKey = key.toLowerCase();
                    if (lowerKey.startsWith(":")) continue;
                    if (lowerKey.equals("te")) continue;
                    if (lowerKey.equals("content-type")) continue;
                    if (lowerKey.equals("host")) continue;
                    headers.add(key, entry.getValue());
                }
            }

            // Write HEADERS frame to the stream channel. No need to call
            // .stream() — Http2MultiplexHandler attaches the correct stream.
            DefaultHttp2HeadersFrame headersFrame = new DefaultHttp2HeadersFrame(headers, false);
            streamChannel.write(headersFrame);

            // Send request data frames (one gRPC frame per DATA frame)
            if (fwdCtx.requestMessages == null || fwdCtx.requestMessages.isEmpty()) {
                // No body — send END_STREAM with empty data
                DefaultHttp2DataFrame endFrame = new DefaultHttp2DataFrame(Unpooled.EMPTY_BUFFER, true);
                streamChannel.writeAndFlush(endFrame);
            } else {
                for (int i = 0; i < fwdCtx.requestMessages.size(); i++) {
                    byte[] msg = fwdCtx.requestMessages.get(i);
                    byte[] frame = GrpcCodecUtils.buildGrpcFrame(msg);
                    boolean isLast = (i == fwdCtx.requestMessages.size() - 1);
                    ByteBuf buf = Unpooled.wrappedBuffer(frame);
                    DefaultHttp2DataFrame dataFrame = new DefaultHttp2DataFrame(buf, isLast);
                    streamChannel.write(dataFrame);
                }
                streamChannel.flush();
            }

            log.debug("gRPC passthrough request sent: {} -> {}:{}",
                    fwdCtx.path, fwdCtx.targetHost, fwdCtx.targetPort);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            if (!finished) {
                log.warn("gRPC passthrough backend connection closed before response: path={}", fwdCtx.path);
                finishWithError(14, "Backend connection closed before response");
            }
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.error("gRPC passthrough backend error: path={}, error={}",
                    fwdCtx.path, cause.getMessage(), cause);
            if (!finished) {
                if (cause instanceof io.netty.handler.timeout.ReadTimeoutException) {
                    finishWithError(14, "Backend read timeout");
                } else {
                    finishWithError(13, "Backend error: " + cause.getMessage());
                }
            } else {
                ctx.close();
            }
        }

        /**
         * Complete the forwarding normally: save recording (if RECORD mode)
         * and close the backend connection. Idempotent.
         */
        void finishAndClose() {
            if (finished) return;
            finished = true;

            // Save recording if RECORD mode
            if (fwdCtx.record) {
                saveRecording();
            }

            if (parentCtx != null) {
                parentCtx.close();
            }

            log.debug("gRPC passthrough complete: path={}, grpcStatus={}, messages={}, elapsed={}ms",
                    fwdCtx.path, grpcStatus, responseMessages.size(),
                    System.currentTimeMillis() - startTime);
        }

        /**
         * Complete with a gRPC error sent to the client. Idempotent.
         */
        void finishWithError(int status, String message) {
            if (finished) return;
            finished = true;
            sendErrorToClient(fwdCtx, status, message);
            if (parentCtx != null) {
                parentCtx.close();
            }
        }

        /**
         * Save the recording entry for this passthrough request/response.
         */
        private void saveRecording() {
            try {
                long elapsed = System.currentTimeMillis() - startTime;
                RecordingEntry rec = new RecordingEntry();
                rec.setRuleId(fwdCtx.rule != null ? fwdCtx.rule.getId() : null);
                rec.setEnvironmentId(fwdCtx.agentEnvironment);
                rec.setProtocol("grpc");
                rec.setHost(fwdCtx.targetHost);
                rec.setPort(fwdCtx.targetPort);
                rec.setMethod("POST");
                rec.setPath(fwdCtx.path);
                rec.setRequestHeaders(fwdCtx.requestMetadata);
                rec.setRequestBody(fwdCtx.requestHex);
                rec.setResponseStatusCode(200);
                rec.setResponseBody(responseHex.toString());
                rec.setResponseTimeMs(elapsed);
                rec.setAgentId(fwdCtx.agentId);
                rec.setAgentIp(fwdCtx.agentIp);
                rec.setDirection("request");
                rec.setResponseSource("PASSTHROUGH");
                rec.setGrpcService(GrpcCodecUtils.extractGrpcService(fwdCtx.path));
                rec.setGrpcMethod(GrpcCodecUtils.extractGrpcMethod(fwdCtx.path));
                rec.setGrpcStatus(grpcStatus);
                rec.setGrpcContentType(CONTENT_TYPE_GRPC);
                fwdCtx.storage.addRecording(rec);
            } catch (Exception e) {
                log.warn("Failed to save gRPC passthrough recording: path={}, error={}",
                        fwdCtx.path, e.getMessage());
            }
        }
    }

    /**
     * Per-stream handler for the outbound HTTP/2 stream. Receives response
     * frames from the backend and forwards them to the client.
     *
     * <p>Frames arriving here are already routed by {@link Http2MultiplexHandler}
     * to this stream channel, so no {@code frame.stream()} filtering is needed.
     * Writes to the client still set {@code .stream(clientStream)} because the
     * client-side handler (GrpcStreamChildHandler) operates on the server's
     * HTTP/2 connection where stream identification is required.</p>
     */
    static class GrpcStreamHandler extends ChannelInboundHandlerAdapter {

        private final ForwardContext fwdCtx;
        private final GrpcBackendHandler backend;

        GrpcStreamHandler(ForwardContext fwdCtx, GrpcBackendHandler backend) {
            this.fwdCtx = fwdCtx;
            this.backend = backend;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (backend.finished) {
                // Discard any late frames after completion
                if (msg instanceof Http2DataFrame) {
                    ((Http2DataFrame) msg).release();
                }
                return;
            }
            if (msg instanceof Http2HeadersFrame) {
                handleResponseHeaders((Http2HeadersFrame) msg);
            } else if (msg instanceof Http2DataFrame) {
                handleResponseData((Http2DataFrame) msg);
            } else {
                ctx.fireChannelRead(msg);
            }
        }

        private void handleResponseHeaders(Http2HeadersFrame frame) {
            // Extract grpc-status / grpc-message if present
            CharSequence statusSeq = frame.headers().get(GRPC_STATUS);
            if (statusSeq != null) {
                try {
                    backend.grpcStatus = Integer.parseInt(statusSeq.toString());
                } catch (NumberFormatException e) {
                    /* ignore invalid value */
                }
            }
            CharSequence msgSeq = frame.headers().get(GRPC_MESSAGE);
            if (msgSeq != null) backend.grpcMessage = msgSeq.toString();

            if (!backend.responseHeadersSent) {
                backend.responseHeadersSent = true;

                if (frame.isEndStream()) {
                    // Trailers-only response (e.g., gRPC error with no body).
                    // Forward as a single HEADERS frame with END_STREAM=true.
                    forwardHeadersToClient(frame.headers(), true);
                    backend.finishAndClose();
                } else {
                    // Normal response headers — forward without END_STREAM
                    forwardHeadersToClient(frame.headers(), false);
                }
            } else {
                // Second HEADERS frame — trailers
                if (frame.isEndStream()) {
                    // Send trailers to client
                    forwardTrailersToClient();
                    backend.finishAndClose();
                }
            }
        }

        /**
         * Forward response HEADERS to the client. If {@code endStream=true},
         * grpc-status and grpc-message are included (trailers-only response).
         */
        private void forwardHeadersToClient(io.netty.handler.codec.http2.Http2Headers backendHeaders,
                                              boolean endStream) {
            if (!fwdCtx.clientCtx.channel().isActive()) return;

            DefaultHttp2Headers clientHeaders = new DefaultHttp2Headers();
            CharSequence status = backendHeaders.status();
            clientHeaders.status(status != null ? status : "200");

            for (Map.Entry<CharSequence, CharSequence> entry : backendHeaders) {
                String key = entry.getKey().toString();
                if (key.startsWith(":")) continue;
                if (!endStream) {
                    // For non-trailers headers, grpc-status/grpc-message go in trailers
                    if (key.equalsIgnoreCase(GRPC_STATUS) || key.equalsIgnoreCase(GRPC_MESSAGE)) continue;
                }
                clientHeaders.add(entry.getKey(), entry.getValue());
            }
            if (!clientHeaders.contains(HttpHeaderNames.CONTENT_TYPE)) {
                clientHeaders.add(HttpHeaderNames.CONTENT_TYPE, CONTENT_TYPE_GRPC);
            }

            DefaultHttp2HeadersFrame clientFrame = new DefaultHttp2HeadersFrame(clientHeaders, endStream);
            clientFrame.stream(fwdCtx.clientStream);
            fwdCtx.clientCtx.writeAndFlush(clientFrame);
        }

        /**
         * Send trailers (HEADERS with END_STREAM=true) to the client.
         */
        private void forwardTrailersToClient() {
            if (!fwdCtx.clientCtx.channel().isActive()) return;

            DefaultHttp2Headers trailers = new DefaultHttp2Headers();
            trailers.status("200");
            trailers.add(HttpHeaderNames.CONTENT_TYPE, CONTENT_TYPE_GRPC);
            trailers.add(GRPC_STATUS, String.valueOf(backend.grpcStatus));
            if (backend.grpcMessage != null && !backend.grpcMessage.isEmpty()) {
                trailers.add(GRPC_MESSAGE, backend.grpcMessage);
            }
            DefaultHttp2HeadersFrame trailersFrame = new DefaultHttp2HeadersFrame(trailers, true);
            trailersFrame.stream(fwdCtx.clientStream);
            fwdCtx.clientCtx.writeAndFlush(trailersFrame);
        }

        private void handleResponseData(Http2DataFrame frame) {
            ByteBuf data = frame.content();
            try {
                byte[] bytes = new byte[data.readableBytes()];
                data.readBytes(bytes);

                // Parse gRPC messages for recording
                List<byte[]> messages = GrpcCodecUtils.parseGrpcFrames(bytes);
                for (byte[] msg : messages) {
                    backend.responseMessages.add(msg);
                    HexUtils.appendHex(backend.responseHex, msg);
                }

                // Forward data frame to client
                if (fwdCtx.clientCtx.channel().isActive() && bytes.length > 0) {
                    ByteBuf forwardBuf = Unpooled.wrappedBuffer(bytes);
                    DefaultHttp2DataFrame clientFrame = new DefaultHttp2DataFrame(forwardBuf, false);
                    clientFrame.stream(fwdCtx.clientStream);
                    fwdCtx.clientCtx.writeAndFlush(clientFrame);
                }
            } finally {
                frame.release();
            }

            if (frame.isEndStream()) {
                // Data frame with END_STREAM — send trailers and finish
                forwardTrailersToClient();
                backend.finishAndClose();
            }
        }
    }
}
