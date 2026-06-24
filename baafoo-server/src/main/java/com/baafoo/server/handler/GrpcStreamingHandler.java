package com.baafoo.server.handler;

import com.baafoo.core.config.ServerConfig;
import com.baafoo.core.model.*;
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

import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * HTTP/2 handler for gRPC Streaming support.
 *
 * <p><b>Deprecated:</b> Use {@link GrpcUnifiedHandler} instead. This handler's
 * {@code determineStreamType} logic is flawed (cannot distinguish client streaming
 * from bidirectional). Retained for backward compatibility only.</p>
 *
 * <p>Supports all three gRPC streaming patterns:
 * <ul>
 *   <li><b>Server Streaming</b>: Client sends one request, server streams multiple responses</li>
 *   <li><b>Client Streaming</b>: Client streams requests, server sends one response</li>
 *   <li><b>Bidirectional Streaming</b>: Both client and server stream messages</li>
 * </ul></p>
 *
 * <p>Uses Netty HTTP/2 frame codec for proper stream management and gRPC framing
 * for message encoding/decoding.</p>
 */
@Deprecated
public class GrpcStreamingHandler extends ChannelInitializer<Channel> {

    private static final Logger log = LoggerFactory.getLogger(GrpcStreamingHandler.class);

    private static final String CONTENT_TYPE_GRPC = "application/grpc";
    private static final String GRPC_STATUS = "grpc-status";
    private static final String GRPC_MESSAGE = "grpc-message";
    private static final String TE_TRAILERS = "trailers";

    private final StorageService storage;
    private final MatchEngine matchEngine;
    private final ServerConfig config;
    private final AgentResolver agentResolver;

    // Track active streams: streamId -> GrpcStreamContext
    // D9 fix: HashMap instead of ConcurrentHashMap — Http2MultiplexHandler guarantees
    // per-channel single-threaded access, so ConcurrentHashMap overhead is unnecessary.
    private final Map<Integer, GrpcStreamContext> activeStreams = new HashMap<>();

    public GrpcStreamingHandler(StorageService storage, ServerConfig config) {
        this.storage = storage;
        this.config = config;
        this.matchEngine = new MatchEngine();
        this.agentResolver = new AgentResolver(storage, config);
    }

    @Override
    public void initChannel(Channel ch) throws Exception {
        Http2Connection connection = new DefaultHttp2Connection(false);

        Http2FrameCodecBuilder frameCodecBuilder = Http2FrameCodecBuilder.forServer()
                .initialSettings(Http2Settings.defaultSettings()
                        .maxConcurrentStreams(100)
                        .headerTableSize(65536)
                        .maxFrameSize(16384));

        ChannelPipeline pipeline = ch.pipeline();
        pipeline.addLast(frameCodecBuilder.build());
        pipeline.addLast(new Http2MultiplexHandler(new GrpcStreamHandler()));
    }

    /**
     * Inner handler for individual HTTP/2 streams.
     */
    private class GrpcStreamHandler extends ChannelInboundHandlerAdapter {

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (msg instanceof Http2HeadersFrame) {
                handleHeaders(ctx, (Http2HeadersFrame) msg);
            } else if (msg instanceof Http2DataFrame) {
                handleData(ctx, (Http2DataFrame) msg);
            } else if (msg instanceof Http2ResetFrame) {
                handleReset(ctx, (Http2ResetFrame) msg);
            } else if (msg instanceof Http2GoAwayFrame) {
                handleGoAway(ctx, (Http2GoAwayFrame) msg);
            } else {
                super.channelRead(ctx, msg);
            }
        }

        private void handleHeaders(ChannelHandlerContext ctx, Http2HeadersFrame frame) {
            int streamId = frame.stream().id();
            Http2Headers headers = frame.headers();
            Http2FrameStream frameStream = frame.stream();

            String path = headers.get(":path") != null ? headers.get(":path").toString() : "";
            String method = headers.get(":method") != null ? headers.get(":method").toString() : "POST";

            // Extract gRPC metadata headers (custom headers)
            Map<String, String> metadata = new HashMap<>();
            for (Map.Entry<CharSequence, CharSequence> entry : headers) {
                String key = entry.getKey().toString();
                if (!key.startsWith(":")) {
                    metadata.put(key, entry.getValue().toString());
                }
            }

            // Determine stream type and create context
            StreamType streamType = determineStreamType(method, frame.isEndStream());
            GrpcStreamContext streamContext = new GrpcStreamContext(ctx, frameStream, streamId, path, streamType, metadata);
            streamContext.headers = headers;
            activeStreams.put(streamId, streamContext);

            log.debug("gRPC stream opened: streamId={}, path={}, type={}, endStream={}",
                    streamId, path, streamType, frame.isEndStream());

            // For unary and server streaming, process immediately if client ended
            if (frame.isEndStream()) {
                if (streamType == StreamType.UNARY || streamType == StreamType.SERVER_STREAMING) {
                    processServerStreaming(streamContext);
                }
            }
        }

        private void handleData(ChannelHandlerContext ctx, Http2DataFrame frame) {
            int streamId = frame.stream().id();
            ByteBuf data = frame.content();
            Http2FrameStream frameStream = frame.stream();

            GrpcStreamContext streamContext = activeStreams.get(streamId);
            if (streamContext == null) {
                log.warn("Data received for unknown stream: {}", streamId);
                ctx.writeAndFlush(new DefaultHttp2ResetFrame(0x0).stream(frameStream));
                data.release();
                return;
            }

            // Read gRPC message from data frame
            while (data.isReadable()) {
                if (data.readableBytes() < 5) {
                    // Incomplete gRPC frame header, accumulate
                    ByteBuf partial = data.readBytes(data.readableBytes());
                    streamContext.accumulateData(partial);
                    break;
                }

                // Parse gRPC frame header
                byte compressed = data.readByte();
                int length = (data.readByte() & 0xFF) << 24 |
                        (data.readByte() & 0xFF) << 16 |
                        (data.readByte() & 0xFF) << 8 |
                        (data.readByte() & 0xFF);

                if (data.readableBytes() < length) {
                    // Put back the header bytes we read
                    data.readerIndex(data.readerIndex() - 5);
                    ByteBuf partial = data.readBytes(data.readableBytes());
                    streamContext.accumulateData(partial);
                    break;
                }

                byte[] messageBytes = new byte[length];
                data.readBytes(messageBytes);

                // Add to accumulated messages
                streamContext.addMessage(messageBytes);

                // Calculate request body hex for matching
                String messageHex = bytesToHex(messageBytes);
                if (streamContext.accumulatedHex == null) {
                    streamContext.accumulatedHex = messageHex;
                } else {
                    streamContext.accumulatedHex += messageHex;
                }

                log.debug("gRPC message received: streamId={}, length={}, totalMessages={}",
                        streamId, length, streamContext.getMessageCount());
            }

            if (frame.isEndStream()) {
                streamContext.clientEnded = true;
                // Process client stream when client finishes sending
                if (streamContext.streamType == StreamType.CLIENT_STREAMING ||
                        streamContext.streamType == StreamType.BIDIRECTIONAL) {
                    processClientStream(streamContext);
                }
            }

            data.release();
        }

        private void handleReset(ChannelHandlerContext ctx, Http2ResetFrame frame) {
            int streamId = frame.stream().id();
            GrpcStreamContext streamContext = activeStreams.remove(streamId);
            if (streamContext != null) {
                streamContext.release(); // D4 fix: release accumulated buffer
                log.debug("Stream reset: streamId={}, errorCode={}", streamId, frame.errorCode());
            }
        }

        private void handleGoAway(ChannelHandlerContext ctx, Http2GoAwayFrame frame) {
            log.debug("gRPC GOAWAY received: errorCode={}", frame.errorCode());
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) throws Exception {
            // D4 fix: release all accumulated buffers to prevent memory leaks
            for (GrpcStreamContext streamContext : activeStreams.values()) {
                streamContext.release();
            }
            activeStreams.clear();
            super.channelInactive(ctx);
        }
    }

    // ==================== Stream Processing ====================

    @SuppressWarnings("unused")
    private StreamType determineStreamType(String method, boolean endStream) {
        // gRPC unary: single request, single response (endStream on headers)
        if (endStream) {
            return StreamType.UNARY;
        }
        // If we receive data frames, it's a streaming call
        // Server streaming: headers with POST, data follows
        // Client streaming: data without endStream on headers
        // Bidirectional: similar to client streaming
        return StreamType.SERVER_STREAMING;
    }

    private void processServerStreaming(GrpcStreamContext streamContext) {
        // For unary and server streaming, match rules and send responses
        List<Rule> allRules = storage.listRules();
        String agentEnv = resolveEnvironment(streamContext);

        List<Rule> rules = agentResolver.filterRulesByEnvironment(allRules, agentEnv);

        MatchEngine.MatchResult result = matchEngine.match(
                rules, "grpc", null, 0, null,
                "POST", streamContext.path,
                streamContext.metadata, null,
                streamContext.accumulatedHex);

        if (!result.isMatched()) {
            result = matchEngine.match(
                    rules, "grpc", null, 0, null,
                    "POST", streamContext.path,
                    streamContext.metadata, null,
                    streamContext.accumulatedHex);
        }

        if (result.isMatched()) {
            ResponseEntry response = result.getResponse();
            sendStreamResponse(streamContext, response, result.getRule().getId(),
                    result.getRule().getFakerSeed(), result.getRequestCount());
        } else {
            sendGrpcError(streamContext, 5, "No matching rule found");
        }
    }

    private void processClientStream(GrpcStreamContext streamContext) {
        // Process accumulated client messages
        List<Rule> allRules = storage.listRules();
        String agentEnv = resolveEnvironment(streamContext);

        List<Rule> rules = agentResolver.filterRulesByEnvironment(allRules, agentEnv);

        // Match against accumulated messages
        MatchEngine.MatchResult result = matchEngine.match(
                rules, "grpc", null, 0, null,
                "POST", streamContext.path,
                streamContext.metadata, null,
                streamContext.accumulatedHex);

        if (!result.isMatched()) {
            result = matchEngine.match(
                    rules, "grpc", null, 0, null,
                    "POST", streamContext.path,
                    streamContext.metadata, null,
                    streamContext.accumulatedHex);
        }

        if (result.isMatched()) {
            ResponseEntry response = result.getResponse();
            if (streamContext.streamType == StreamType.BIDIRECTIONAL) {
                // For bidirectional, send responses interleaved
                sendStreamResponse(streamContext, response, result.getRule().getId(),
                        result.getRule().getFakerSeed(), result.getRequestCount());
            } else {
                // For client streaming, send single response
                sendUnaryResponse(streamContext, response, result.getRule().getId(),
                        result.getRule().getFakerSeed(), result.getRequestCount());
            }
        } else {
            // D8 fix: no more echo for bidirectional — unified error response
            sendGrpcError(streamContext, 5, "No matching rule found");
        }
    }

    @SuppressWarnings("unused")
    private void echoBidirectionalMessages(GrpcStreamContext streamContext) {
        // D8 fix: deprecated — no longer used. Kept for reference; will be removed next release.
        // Previously echoed all received messages for unmatched bidirectional streams.
        // Now unified: unmatched streams return NOT_FOUND(5) regardless of stream type.
        for (byte[] msg : streamContext.messages) {
            sendGrpcMessage(streamContext, msg);
        }
        sendGrpcTrailers(streamContext, 0, "");
    }

    // ==================== Response Sending ====================

    private void sendStreamResponse(GrpcStreamContext streamContext, ResponseEntry entry,
                                   String ruleId, Long fakerSeed, int requestCount) {
        try {
            String rawBody = entry.getBody() != null ? entry.getBody() : "";

            // Handle streaming responses (comma or newline separated messages)
            String[] messages;
            if (rawBody.contains(",")) {
                messages = rawBody.split(",");
            } else if (rawBody.contains("\n")) {
                messages = rawBody.split("\n");
            } else {
                messages = new String[]{rawBody};
            }

            TemplateEngine.RequestContext templateCtx = new TemplateEngine.RequestContext(
                    "POST", streamContext.path, null, streamContext.metadata, null,
                    streamContext.accumulatedHex, resolveEnvironment(streamContext));
            templateCtx.setRequestCount(requestCount);

            // Send each message with delay
            long delayMs = entry.getDelayMs();
            int totalMessages = messages.length;

            for (int i = 0; i < totalMessages; i++) {
                final int messageIndex = i;
                String messageBody = messages[i].trim();

                if (messageBody.isEmpty()) continue;

                // Render template if present
                if (messageBody.contains("{{")) {
                    messageBody = TemplateEngine.render(messageBody, templateCtx, fakerSeed);
                }

                byte[] responseBytes;
                if (messageBody.startsWith("0x") || messageBody.startsWith("0X")) {
                    responseBytes = hexToBytes(messageBody.substring(2));
                } else if (isHexString(messageBody)) {
                    responseBytes = hexToBytes(messageBody);
                } else {
                    responseBytes = messageBody.getBytes(StandardCharsets.UTF_8);
                }

                final byte[] finalBytes = responseBytes;
                final boolean isLast = (messageIndex == totalMessages - 1);

                long sendTime = delayMs * (messageIndex + 1);

                streamContext.ctx.executor().schedule(new Runnable() {
                    @Override
                    public void run() {
                        sendGrpcMessage(streamContext, finalBytes);

                        if (isLast) {
                            // Send trailers (gRPC status)
                            int grpcStatus = getGrpcStatus(entry);
                            String grpcMessage = getGrpcMessage(entry);
                            sendGrpcTrailers(streamContext, grpcStatus, grpcMessage);
                        }
                    }
                }, sendTime, TimeUnit.MILLISECONDS);
            }

            log.debug("gRPC streaming response queued: streamId={}, messages={}, rule={}",
                    streamContext.streamId, totalMessages, ruleId);

        } catch (Exception e) {
            log.error("Error sending streaming response: {}", e.getMessage());
            sendGrpcError(streamContext, 13, "Internal error: " + e.getMessage());
        }
    }

    private void sendUnaryResponse(GrpcStreamContext streamContext, ResponseEntry entry,
                                   String ruleId, Long fakerSeed, int requestCount) {
        try {
            String rawBody = entry.getBody() != null ? entry.getBody() : "";

            String responseBody = rawBody;
            if (rawBody.contains("{{")) {
                TemplateEngine.RequestContext templateCtx = new TemplateEngine.RequestContext(
                        "POST", streamContext.path, null, streamContext.metadata, null,
                        streamContext.accumulatedHex, resolveEnvironment(streamContext));
                templateCtx.setRequestCount(requestCount);
                responseBody = TemplateEngine.render(rawBody, templateCtx, fakerSeed);
            }

            byte[] responseBytes;
            if (responseBody.startsWith("0x") || responseBody.startsWith("0X")) {
                responseBytes = hexToBytes(responseBody.substring(2));
            } else if (isHexString(responseBody)) {
                responseBytes = hexToBytes(responseBody);
            } else {
                responseBytes = responseBody.getBytes(StandardCharsets.UTF_8);
            }

            // Send response data
            sendGrpcMessage(streamContext, responseBytes);

            // Send trailers
            int grpcStatus = getGrpcStatus(entry);
            String grpcMessage = getGrpcMessage(entry);
            sendGrpcTrailers(streamContext, grpcStatus, grpcMessage);

            log.debug("gRPC unary response sent: streamId={}, size={}, rule={}",
                    streamContext.streamId, responseBytes.length, ruleId);

        } catch (Exception e) {
            log.error("Error sending unary response: {}", e.getMessage());
            sendGrpcError(streamContext, 13, "Internal error: " + e.getMessage());
        }
    }

    private void sendGrpcMessage(GrpcStreamContext streamContext, byte[] message) {
        if (streamContext.ctx == null || !streamContext.ctx.channel().isActive()) {
            return;
        }

        ByteBuf frame = Unpooled.buffer(5 + message.length);
        frame.writeByte(0); // No compression
        frame.writeInt(message.length);
        frame.writeBytes(message);

        DefaultHttp2DataFrame dataFrame = new DefaultHttp2DataFrame(Unpooled.copiedBuffer(frame), false);
        dataFrame.stream(streamContext.frameStream);
        streamContext.ctx.write(dataFrame);
        streamContext.ctx.flush();
    }

    private void sendGrpcTrailers(GrpcStreamContext streamContext, int status, String message) {
        if (streamContext.ctx == null || !streamContext.ctx.channel().isActive()) {
            return;
        }

        DefaultHttp2Headers trailers = new DefaultHttp2Headers();
        trailers.status("200");
        trailers.add(HttpHeaderNames.CONTENT_TYPE, CONTENT_TYPE_GRPC);
        trailers.add(TE_TRAILERS, "trailers");
        trailers.add(GRPC_STATUS, String.valueOf(status));
        if (message != null && !message.isEmpty()) {
            trailers.add(GRPC_MESSAGE, message);
        }

        DefaultHttp2HeadersFrame headersFrame = new DefaultHttp2HeadersFrame(trailers, true);
        headersFrame.stream(streamContext.frameStream);
        streamContext.ctx.writeAndFlush(headersFrame);
    }

    private void sendGrpcError(GrpcStreamContext streamContext, int status, String message) {
        try {
            if (streamContext.ctx == null || !streamContext.ctx.channel().isActive()) {
                return;
            }

            DefaultHttp2Headers trailers = new DefaultHttp2Headers();
            trailers.status("200");
            trailers.add(HttpHeaderNames.CONTENT_TYPE, CONTENT_TYPE_GRPC);
            trailers.add(GRPC_STATUS, String.valueOf(status));
            if (message != null && !message.isEmpty()) {
                trailers.add(GRPC_MESSAGE, message);
            }

            DefaultHttp2HeadersFrame headersFrame = new DefaultHttp2HeadersFrame(trailers, true);
            headersFrame.stream(streamContext.frameStream);
            streamContext.ctx.writeAndFlush(headersFrame);

            log.debug("gRPC error sent: streamId={}, status={}", streamContext.streamId, status);
        } catch (Exception e) {
            log.error("Error sending gRPC error: {}", e.getMessage());
        }
    }

    // ==================== Helper Methods ====================

    private String resolveEnvironment(GrpcStreamContext streamContext) {
        AgentResolver.AgentInfo agentInfo = agentResolver.resolveAll(streamContext.ctx);
        return agentInfo != null ? agentInfo.environment : "default";
    }

    private int getGrpcStatus(ResponseEntry entry) {
        if (entry.getHeaders() != null) {
            String statusStr = entry.getHeaders().get("grpc-status");
            if (statusStr == null) statusStr = entry.getHeaders().get("Grpc-Status");
            if (statusStr == null) statusStr = entry.getHeaders().get("x-grpc-status");
            if (statusStr != null) {
                try {
                    return Integer.parseInt(statusStr);
                } catch (NumberFormatException e) {
                    log.warn("Invalid grpc-status value: {}", statusStr);
                }
            }
        }
        return 0; // OK
    }

    private String getGrpcMessage(ResponseEntry entry) {
        if (entry.getHeaders() != null) {
            String msgStr = entry.getHeaders().get("grpc-message");
            if (msgStr == null) msgStr = entry.getHeaders().get("Grpc-Message");
            if (msgStr == null) msgStr = entry.getHeaders().get("x-grpc-message");
            return msgStr != null ? msgStr : "";
        }
        return "";
    }

    private static String bytesToHex(byte[] bytes) {
        if (bytes == null) return "";
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private static byte[] hexToBytes(String hex) {
        if (hex == null || hex.isEmpty()) return new byte[0];
        String clean = hex.replaceAll("\\s", "");
        if (clean.length() % 2 != 0) {
            clean = "0" + clean;
        }
        byte[] result = new byte[clean.length() / 2];
        for (int i = 0; i < result.length; i++) {
            int high = Character.digit(clean.charAt(i * 2), 16);
            int low = Character.digit(clean.charAt(i * 2 + 1), 16);
            result[i] = (byte) ((high << 4) | low);
        }
        return result;
    }

    private static boolean isHexString(String s) {
        if (s == null || s.isEmpty()) return false;
        if (s.length() % 2 != 0) return false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
                return false;
            }
        }
        return true;
    }

    // ==================== Inner Classes ====================

    enum StreamType {
        UNARY,           // Single request, single response
        SERVER_STREAMING,// Single request, multiple responses
        CLIENT_STREAMING,// Multiple requests, single response
        BIDIRECTIONAL    // Multiple requests, multiple responses
    }

    static class GrpcStreamContext {
        final ChannelHandlerContext ctx;
        final Http2FrameStream frameStream;
        final int streamId;
        final String path;
        final StreamType streamType;
        final Map<String, String> metadata;
        final List<byte[]> messages;
        Http2Headers headers;
        ByteBuf accumulatedBuffer;
        String accumulatedHex;
        boolean clientEnded;

        GrpcStreamContext(ChannelHandlerContext ctx, Http2FrameStream frameStream, int streamId, String path,
                          StreamType streamType, Map<String, String> metadata) {
            this.ctx = ctx;
            this.frameStream = frameStream;
            this.streamId = streamId;
            this.path = path;
            this.streamType = streamType;
            this.metadata = metadata;
            this.messages = new ArrayList<>();
            this.accumulatedBuffer = Unpooled.buffer();
        }

        void addMessage(byte[] message) {
            messages.add(message);
        }

        int getMessageCount() {
            return messages.size();
        }

        void accumulateData(ByteBuf data) {
            if (accumulatedBuffer == null) {
                accumulatedBuffer = Unpooled.buffer();
            }
            try {
                accumulatedBuffer.writeBytes(data);
            } finally {
                // D4 fix: release the incoming ByteBuf after copying its contents
                data.release();
            }
        }

        /**
         * Release accumulated buffer to prevent memory leaks.
         */
        void release() {
            if (accumulatedBuffer != null) {
                accumulatedBuffer.release();
                accumulatedBuffer = null;
            }
        }
    }
}
