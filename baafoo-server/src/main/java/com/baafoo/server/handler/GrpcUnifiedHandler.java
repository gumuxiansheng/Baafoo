package com.baafoo.server.handler;

import com.baafoo.core.config.ServerConfig;
import com.baafoo.core.model.*;
import com.baafoo.core.util.GrpcCodecUtils;
import com.baafoo.core.util.MatchEngine;
import com.baafoo.core.util.TemplateEngine;
import com.baafoo.server.storage.StorageService;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http2.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * Unified HTTP/2 gRPC handler.
 *
 * <p>Handles all four gRPC call types over a single HTTP/2 connection:
 * <ul>
 *   <li><b>Unary</b> — 1 request → 1 response</li>
 *   <li><b>Server Streaming</b> — 1 request → N responses</li>
 *   <li><b>Client Streaming</b> — N requests → 1 response</li>
 *   <li><b>Bidirectional Streaming</b> — N requests → M responses</li>
 * </ul>
 * </p>
 *
 * <p>Replaces both {@link GrpcStubHandler} (HTTP/1.1) and {@link GrpcStreamingHandler} (HTTP/2 streaming).
 * Uses {@link Http2FrameCodec} + {@link Http2MultiplexHandler} for proper HTTP/2 stream management.</p>
 *
 * <p><b>Stream type detection</b>: gRPC stream types are defined by the protobuf service definition,
 * not by HTTP/2 frame semantics. This handler does not attempt to distinguish stream types at
 * HEADERS time. Instead, it accumulates request messages until the client signals end-of-stream,
 * then matches rules and sends responses. The number of response messages is determined by the
 * rule's response body configuration (comma/newline-separated values for streaming).</p>
 *
 * <p><b>ByteBuf safety</b>: All incoming ByteBuf references are released in {@code try/finally}
 * blocks. Per-stream state is isolated via {@code Http2MultiplexHandler} which creates a new
 * handler instance per HTTP/2 stream — no shared mutable state, no concurrency concern.</p>
 */
public class GrpcUnifiedHandler extends ChannelInitializer<Channel> {

    private static final Logger log = LoggerFactory.getLogger(GrpcUnifiedHandler.class);

    private static final String CONTENT_TYPE_GRPC = "application/grpc";
    private static final String GRPC_STATUS = "grpc-status";
    private static final String GRPC_MESSAGE = "grpc-message";

    private final StorageService storage;
    private final ServerConfig config;
    private final AgentResolver agentResolver;
    private final MatchEngine matchEngine;

    public GrpcUnifiedHandler(StorageService storage, ServerConfig config) {
        this.storage = storage;
        this.config = config;
        this.agentResolver = new AgentResolver(storage, config);
        this.matchEngine = new MatchEngine();
    }

    @Override
    protected void initChannel(Channel ch) {
        Http2FrameCodec codec = Http2FrameCodecBuilder.forServer()
                .initialSettings(Http2Settings.defaultSettings()
                        .maxConcurrentStreams(100)
                        .headerTableSize(65536)
                        .maxFrameSize(16384))
                .build();

        ch.pipeline().addLast(codec);
        ch.pipeline().addLast(new Http2MultiplexHandler(new GrpcStreamChildHandler(
                storage, config, agentResolver, matchEngine)));
    }

    /**
     * Per-stream handler. {@link Http2MultiplexHandler} creates a new instance
     * for each HTTP/2 stream, so all per-stream state is naturally isolated.
     * No shared mutable state — no concurrency concern.
     */
    static class GrpcStreamChildHandler extends ChannelInboundHandlerAdapter {

        private final StorageService storage;
        private final ServerConfig config;
        private final AgentResolver agentResolver;
        private final MatchEngine matchEngine;

        // Per-stream state
        private String path;
        private Map<String, String> metadata;
        private final List<byte[]> requestMessages = new ArrayList<>();
        private String accumulatedHex = "";
        private Http2FrameStream frameStream;
        private boolean clientEnded = false;

        GrpcStreamChildHandler(StorageService storage, ServerConfig config,
                               AgentResolver agentResolver, MatchEngine matchEngine) {
            this.storage = storage;
            this.config = config;
            this.agentResolver = agentResolver;
            this.matchEngine = matchEngine;
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (msg instanceof Http2HeadersFrame) {
                onHeaders(ctx, (Http2HeadersFrame) msg);
            } else if (msg instanceof Http2DataFrame) {
                onData(ctx, (Http2DataFrame) msg);
            } else if (msg instanceof Http2ResetFrame) {
                onReset(ctx, (Http2ResetFrame) msg);
            } else if (msg instanceof Http2GoAwayFrame) {
                log.debug("gRPC GOAWAY received");
            } else {
                ctx.fireChannelRead(msg);
            }
        }

        private void onHeaders(ChannelHandlerContext ctx, Http2HeadersFrame frame) {
            this.frameStream = frame.stream();
            this.path = frame.headers().get(":path") != null
                    ? frame.headers().get(":path").toString() : "";
            this.metadata = new HashMap<>();

            for (Map.Entry<CharSequence, CharSequence> entry : frame.headers()) {
                String key = entry.getKey().toString();
                if (!key.startsWith(":")) {
                    metadata.put(key, entry.getValue().toString());
                }
            }

            log.debug("gRPC stream opened: id={}, path={}, endStream={}",
                    frameStream.id(), path, frame.isEndStream());

            if (frame.isEndStream()) {
                // No body — process immediately (rare for gRPC, but handle it)
                clientEnded = true;
                processAndRespond(ctx);
            }
        }

        private void onData(ChannelHandlerContext ctx, Http2DataFrame frame) {
            ByteBuf data = frame.content();
            try {
                // Read all available bytes and parse complete gRPC frames
                byte[] allBytes = new byte[data.readableBytes()];
                data.readBytes(allBytes);
                
                List<byte[]> messages = GrpcCodecUtils.parseGrpcFrames(allBytes);
                for (byte[] msg : messages) {
                    requestMessages.add(msg);
                    accumulatedHex += GrpcCodecUtils.bytesToHex(msg);
                }
                
                // Check for incomplete trailing data
                int consumed = 0;
                for (byte[] msg : messages) {
                    consumed += 5 + msg.length;
                }
                if (consumed < allBytes.length) {
                    // Partial frame header remaining — accumulate as hex
                    byte[] remaining = new byte[allBytes.length - consumed];
                    System.arraycopy(allBytes, consumed, remaining, 0, remaining.length);
                    accumulatedHex += GrpcCodecUtils.bytesToHex(remaining);
                }
            } finally {
                data.release();
            }

            if (frame.isEndStream()) {
                clientEnded = true;
                processAndRespond(ctx);
            }
        }

        private void onReset(ChannelHandlerContext ctx, Http2ResetFrame frame) {
            log.debug("gRPC stream reset: id={}, code={}", frameStream.id(), frame.errorCode());
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            // Per-stream handler — nothing to clean up (no shared state)
            ctx.fireChannelInactive();
        }

        // ==================== Request Processing ====================

        private void processAndRespond(ChannelHandlerContext ctx) {
            try {
                // 1. Resolve agent environment
                AgentResolver.AgentInfo agentInfo = agentResolver.resolveAll(ctx);
                String agentEnv = agentInfo.environment;

                List<Rule> allRules = storage.listRules();
                List<Rule> rules = agentResolver.filterRulesByEnvironment(allRules, agentEnv);

                // 2. Match rule with port=0 fallback (gRPC handler doesn't know the original port)
                MatchEngine.MatchResult result = matchEngine.matchWithFallback(
                        rules, "grpc", null, 0, null,
                        "POST", path, null, metadata, null, accumulatedHex);

                if (result.isMatched()) {
                    EnvironmentMode mode = agentResolver.resolveEnvironmentMode(agentEnv);

                    if (mode == EnvironmentMode.PASSTHROUGH || mode == EnvironmentMode.RECORD) {
                        // D7 fix: use UNIMPLEMENTED(12) instead of UNAVAILABLE(14)
                        log.info("Mode {} — gRPC passthrough for: {}", mode.getValue(), path);
                        sendGrpcError(ctx, 12, "Passthrough not supported for gRPC stub");
                        return;
                    }

                    // Record if needed
                    if (mode == EnvironmentMode.RECORD_AND_STUB || mode == EnvironmentMode.RECORD_ALL) {
                        RecordingEntry rec = RecordingHelper.buildFromStub(
                                result, "grpc", null, 0, "POST", path, metadata, accumulatedHex);
                        rec.setAgentId(agentInfo.agentId);
                        rec.setAgentIp(agentInfo.agentIp);
                        rec.setEnvironmentId(agentEnv);
                        // Populate gRPC structured fields
                        rec.setGrpcService(GrpcCodecUtils.extractGrpcService(path));
                        rec.setGrpcMethod(GrpcCodecUtils.extractGrpcMethod(path));
                        ResponseEntry respEntry = result.getResponse();
                        if (respEntry != null) {
                            rec.setGrpcStatus(GrpcResponseBuilder.getGrpcStatus(respEntry));
                        }
                        String ct = metadata != null ? metadata.get("content-type") : null;
                        if (ct == null && metadata != null) ct = metadata.get("Content-Type");
                        rec.setGrpcContentType(ct != null ? ct : "application/grpc");
                        storage.addRecording(rec);
                    }

                    // Send stub response
                    sendStubResponse(ctx, result, agentInfo);
                } else {
                    // No matching rule
                    EnvironmentMode mode = agentResolver.resolveEnvironmentMode(agentEnv);
                    if (mode == EnvironmentMode.RECORD_ALL) {
                        log.info("RECORD_ALL — unmatched gRPC: {}", path);
                        sendGrpcError(ctx, 12, "No matching rule found (RECORD_ALL mode)");
                        return;
                    }

                    String unmatchedDefault = config.getUnmatchedDefault();
                    if ("404".equalsIgnoreCase(unmatchedDefault)) {
                        log.info("No Baafoo gRPC rule matched: {} — returning NOT_FOUND", path);
                        sendGrpcError(ctx, 5, "No matching rule found");
                    } else {
                        log.info("No Baafoo gRPC rule matched: {} — passthrough mode, returning UNIMPLEMENTED", path);
                        sendGrpcError(ctx, 12, "No matching rule found");
                    }
                }
            } catch (Exception e) {
                log.error("Error processing gRPC request: path={}, error={}", path, e.getMessage(), e);
                sendGrpcError(ctx, 13, "Internal error: " + e.getMessage());
            }
        }

        // ==================== Response Sending ====================

        private void sendStubResponse(ChannelHandlerContext ctx, MatchEngine.MatchResult result,
                                       AgentResolver.AgentInfo agentInfo) {
            ResponseEntry entry = result.getResponse();
            Long fakerSeed = result.getRule().getFakerSeed();
            int requestCount = result.getRequestCount();

            TemplateEngine.RequestContext templateCtx = new TemplateEngine.RequestContext(
                    "POST", path, null, metadata, null, accumulatedHex, agentInfo.environment);
            templateCtx.setRequestCount(requestCount);

            // Build response messages (handles multi-message streaming)
            List<byte[]> responseMessages = GrpcResponseBuilder.buildResponseMessages(
                    entry, templateCtx, fakerSeed);

            int grpcStatus = GrpcResponseBuilder.getGrpcStatus(entry);
            String grpcMessage = GrpcResponseBuilder.getGrpcMessage(entry);

            // Send response headers first
            sendResponseHeaders(ctx);

            // Send each message as a DATA frame
            long delayMs = entry.getDelayMs();
            for (int i = 0; i < responseMessages.size(); i++) {
                final boolean isLast = (i == responseMessages.size() - 1);
                final byte[] msgBytes = responseMessages.get(i);

                if (delayMs > 0 && i > 0) {
                    // Delay between streaming messages
                    final ChannelHandlerContext finalCtx = ctx;
                    ctx.executor().schedule(() -> {
                        sendGrpcMessage(finalCtx, msgBytes);
                        if (isLast) sendGrpcTrailers(finalCtx, grpcStatus, grpcMessage);
                    }, delayMs * i, TimeUnit.MILLISECONDS);
                } else {
                    sendGrpcMessage(ctx, msgBytes);
                    if (isLast) sendGrpcTrailers(ctx, grpcStatus, grpcMessage);
                }
            }

            // If no messages at all, just send trailers
            if (responseMessages.isEmpty()) {
                sendGrpcTrailers(ctx, grpcStatus, grpcMessage);
            }

            log.debug("gRPC stub response: path={}, messages={}, status={}, rule={}",
                    path, responseMessages.size(), grpcStatus, result.getRule().getId());
        }

        private void sendResponseHeaders(ChannelHandlerContext ctx) {
            DefaultHttp2Headers headers = new DefaultHttp2Headers();
            headers.status("200");
            headers.add(HttpHeaderNames.CONTENT_TYPE, CONTENT_TYPE_GRPC);
            DefaultHttp2HeadersFrame headersFrame = new DefaultHttp2HeadersFrame(headers, false);
            headersFrame.stream(frameStream);
            ctx.write(headersFrame);
            ctx.flush();
        }

        private void sendGrpcMessage(ChannelHandlerContext ctx, byte[] message) {
            if (!ctx.channel().isActive()) return;

            byte[] frame = GrpcCodecUtils.buildGrpcFrame(message);
            ByteBuf buf = Unpooled.wrappedBuffer(frame);
            DefaultHttp2DataFrame dataFrame = new DefaultHttp2DataFrame(buf, false);
            dataFrame.stream(frameStream);
            ctx.write(dataFrame);
            ctx.flush();
        }

        private void sendGrpcTrailers(ChannelHandlerContext ctx, int status, String message) {
            if (!ctx.channel().isActive()) return;

            DefaultHttp2Headers trailers = new DefaultHttp2Headers();
            trailers.status("200");
            trailers.add(HttpHeaderNames.CONTENT_TYPE, CONTENT_TYPE_GRPC);
            trailers.add(GRPC_STATUS, String.valueOf(status));
            if (message != null && !message.isEmpty()) {
                trailers.add(GRPC_MESSAGE, message);
            }

            DefaultHttp2HeadersFrame headersFrame = new DefaultHttp2HeadersFrame(trailers, true);
            headersFrame.stream(frameStream);
            ctx.writeAndFlush(headersFrame);
        }

        private void sendGrpcError(ChannelHandlerContext ctx, int status, String message) {
            if (!ctx.channel().isActive()) return;

            // Send headers
            DefaultHttp2Headers headers = new DefaultHttp2Headers();
            headers.status("200");
            headers.add(HttpHeaderNames.CONTENT_TYPE, CONTENT_TYPE_GRPC);
            headers.add(GRPC_STATUS, String.valueOf(status));
            if (message != null && !message.isEmpty()) {
                headers.add(GRPC_MESSAGE, message);
            }

            DefaultHttp2HeadersFrame headersFrame = new DefaultHttp2HeadersFrame(headers, true);
            headersFrame.stream(frameStream);
            ctx.writeAndFlush(headersFrame);

            log.debug("gRPC error sent: id={}, status={}", frameStream.id(), status);
        }

        @Override
        public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
            log.error("GrpcUnifiedHandler error: stream={}, path={}, error={}",
                    frameStream != null ? frameStream.id() : -1, path, cause.getMessage());
            try {
                sendGrpcError(ctx, 13, "Internal error: " + cause.getMessage());
            } catch (Exception e) {
                ctx.close();
            }
        }
    }
}
