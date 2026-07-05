package com.baafoo.server.handler;

import com.baafoo.core.config.ServerConfig;
import com.baafoo.core.model.EnvironmentMode;
import com.baafoo.core.model.RecordingEntry;
import com.baafoo.core.model.ResponseEntry;
import com.baafoo.core.model.Rule;
import com.baafoo.core.util.HexUtils;
import com.baafoo.core.util.MatchEngine;
import com.baafoo.core.util.TemplateEngine;
import com.baafoo.server.storage.StorageService;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Netty handler for gRPC stub server (port 9005).
 *
 * <p><b>Deprecated:</b> Use {@link GrpcUnifiedHandler} instead. This handler uses HTTP/1.1
 * which is incompatible with standard gRPC clients (HTTP/2 only). Retained for backward
 * compatibility only — will be removed in the next major release.</p>
 *
 * <p>gRPC over HTTP/1.1 stub handler. Matches incoming gRPC requests against
 * stored rules and returns pre-configured stub responses. The handler parses
 * gRPC message framing (1-byte compressed-flag + 4-byte big-endian length + message)
 * and constructs valid gRPC responses with proper framing and status trailers.</p>
 *
 * <p>Supported features:
 * <ul>
 *   <li>Unary gRPC calls (request-response)</li>
 *   <li>Service name and method name matching (grpcService, grpcMethod conditions)</li>
 *   <li>Path-based matching (full gRPC path /package.Service/Method)</li>
 *   <li>Header matching (gRPC metadata)</li>
 *   <li>Body matching (protobuf hex or base64 string)</li>
 *   <li>gRPC status code response (via grpc-status trailer)</li>
 *   <li>Response delay simulation</li>
 * </ul></p>
 *
 * <p>Note: This implementation uses HTTP/1.1 for simplicity. For full HTTP/2
 * support, use {@link GrpcUnifiedHandler}.</p>
 */
@Deprecated
public class GrpcStubHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Logger log = LoggerFactory.getLogger(GrpcStubHandler.class);

    private static final String CONTENT_TYPE_GRPC = "application/grpc";
    private static final String GRPC_STATUS = "grpc-status";
    private static final String GRPC_MESSAGE = "grpc-message";

    private final StorageService storage;
    private final MatchEngine matchEngine;
    private final ServerConfig config;
    private final AgentResolver agentResolver;

    public GrpcStubHandler(StorageService storage, ServerConfig config) {
        this.storage = storage;
        this.config = config;
        this.matchEngine = new MatchEngine();
        this.agentResolver = new AgentResolver(storage, config);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        String path = request.uri();
        Map<String, String> headers = extractHeaders(request);
        String contentType = headers.get("content-type");
        if (contentType == null) {
            contentType = headers.get("Content-Type");
        }

        if (contentType == null || !contentType.startsWith(CONTENT_TYPE_GRPC)) {
            log.warn("Non-gRPC request received on gRPC port: content-type={}, path={}", contentType, path);
            sendError(ctx, "Invalid request: not a gRPC request");
            return;
        }

        ByteBuf content = request.content();
        byte[] requestBytes = new byte[content.readableBytes()];
        content.getBytes(content.readerIndex(), requestBytes);

        String requestBodyHex = bytesToHex(requestBytes);

        String host = null;
        int port = 0;
        String hostHeader = headers.get("host");
        if (hostHeader == null) {
            hostHeader = headers.get("Host");
        }
        if (hostHeader != null) {
            int colonIdx = hostHeader.lastIndexOf(':');
            if (colonIdx > 0 && hostHeader.indexOf(']') < 0) {
                try {
                    port = Integer.parseInt(hostHeader.substring(colonIdx + 1));
                    host = hostHeader.substring(0, colonIdx);
                } catch (NumberFormatException e) {
                    host = hostHeader;
                }
            } else {
                host = hostHeader;
            }
        }

        AgentResolver.AgentInfo agentInfo = agentResolver.resolveAll(ctx);
        String agentEnvironment = agentInfo.environment;
        String agentId = agentInfo.agentId;
        String agentIp = agentInfo.agentIp;

        List<Rule> allRules = storage.listRules();
        List<Rule> rules = agentResolver.filterRulesByEnvironment(allRules, agentEnvironment);

        MatchEngine.MatchResult result = matchEngine.match(
                rules, "grpc", host, port, null,
                "POST", path, headers, null, requestBodyHex);

        if (!result.isMatched()) {
            result = matchEngine.match(
                    rules, "grpc", host, 0, null,
                    "POST", path, headers, null, requestBodyHex);
        }

        if (result.isMatched()) {
            EnvironmentMode currentMode = agentResolver.resolveEnvironmentMode(agentEnvironment);

            if (currentMode == EnvironmentMode.PASSTHROUGH || currentMode == EnvironmentMode.RECORD) {
                log.info("Mode {} — gRPC passthrough for: {}", currentMode.getValue(), path);
                // D7 fix: use UNIMPLEMENTED(12) instead of UNAVAILABLE(14)
                sendGrpcError(ctx, 12, "Passthrough not supported for gRPC stub");
            } else {
                if (currentMode == EnvironmentMode.RECORD_AND_STUB || currentMode == EnvironmentMode.RECORD_ALL) {
                    RecordingEntry rec = RecordingHelper.buildFromStub(
                            result, "grpc", host, port, "POST", path, headers, requestBodyHex);
                    rec.setAgentId(agentId);
                    rec.setAgentIp(agentIp);
                    rec.setEnvironmentId(agentEnvironment);
                    storage.addRecording(rec);
                }
                sendGrpcStubResponse(ctx, result.getResponse(), result.getRule().getId(),
                        path, headers, requestBodyHex, agentEnvironment,
                        result.getRule().getFakerSeed(), result.getRequestCount());
            }
        } else {
            EnvironmentMode currentMode = agentResolver.resolveEnvironmentMode(agentEnvironment);
            if (currentMode == EnvironmentMode.RECORD_ALL) {
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
    }

    /**
     * Send a gRPC stub response with proper message framing and status trailers.
     *
     * <p>gRPC response format (HTTP/1.1 compatible):
     * <ol>
     *   <li>Response headers: 200 OK, content-type: application/grpc</li>
     *   <li>Response body: gRPC message frames (compressed-flag + length + message)</li>
     *   <li>Trailers: grpc-status, grpc-message</li>
     * </ol></p>
     */
    private void sendGrpcStubResponse(ChannelHandlerContext ctx, ResponseEntry entry, String ruleId,
                                       String path, Map<String, String> headers, String requestBodyHex,
                                       String environment, Long fakerSeed, int requestCount) {
        try {
            String rawBody = entry.getBody() != null ? entry.getBody() : "";

            String responseBody = rawBody;
            if (rawBody.contains("{{")) {
                TemplateEngine.RequestContext templateCtx = new TemplateEngine.RequestContext(
                        "POST", path, null, headers, null, requestBodyHex, environment);
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

            byte[] grpcFrame = buildGrpcFrame(responseBytes);

            int grpcStatusCode = 0;
            String grpcStatusMessage = "";
            if (entry.getHeaders() != null) {
                String statusStr = entry.getHeaders().get("grpc-status");
                if (statusStr == null) statusStr = entry.getHeaders().get("Grpc-Status");
                if (statusStr == null) statusStr = entry.getHeaders().get("x-grpc-status");
                if (statusStr != null) {
                    try {
                        grpcStatusCode = Integer.parseInt(statusStr);
                    } catch (NumberFormatException e) {
                        log.warn("Invalid grpc-status value: {}", statusStr);
                    }
                }
                String msgStr = entry.getHeaders().get("grpc-message");
                if (msgStr == null) msgStr = entry.getHeaders().get("Grpc-Message");
                if (msgStr == null) msgStr = entry.getHeaders().get("x-grpc-message");
                if (msgStr != null) {
                    grpcStatusMessage = msgStr;
                }
            }

            FullHttpResponse response = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
                    Unpooled.copiedBuffer(grpcFrame));

            response.headers().set(HttpHeaderNames.CONTENT_TYPE, CONTENT_TYPE_GRPC);
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, grpcFrame.length);
            response.headers().set("X-Baafoo-Rule-Id", ruleId);
            response.headers().set("X-Baafoo-Stub", "true");

            if (entry.getHeaders() != null) {
                for (Map.Entry<String, String> h : entry.getHeaders().entrySet()) {
                    String key = h.getKey();
                    if (!"grpc-status".equalsIgnoreCase(key)
                            && !"grpc-message".equalsIgnoreCase(key)
                            && !"content-type".equalsIgnoreCase(key)
                            && !"content-length".equalsIgnoreCase(key)) {
                        response.headers().set(key, h.getValue());
                    }
                }
            }

            response.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
            response.headers().set(GRPC_STATUS, String.valueOf(grpcStatusCode));
            if (grpcStatusMessage != null && !grpcStatusMessage.isEmpty()) {
                response.headers().set(GRPC_MESSAGE, grpcStatusMessage);
            }

            log.debug("gRPC stub response: path={}, status={}, body={}bytes",
                    path, grpcStatusCode, responseBytes.length);

            long delayMs = entry.getDelayMs();
            if (delayMs > 0) {
                ctx.executor().schedule(new Runnable() {
                    @Override
                    public void run() {
                        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
                    }
                }, delayMs, TimeUnit.MILLISECONDS);
            } else {
                ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
            }
        } catch (Exception e) {
            log.error("Error sending gRPC stub response: {}", e.getMessage());
            sendGrpcError(ctx, 13, "Internal error: " + e.getMessage());
        }
    }

    /**
     * Build a gRPC message frame.
     *
     * <p>gRPC message format:
     * <pre>
     * +----------------+-------------------+-----------+
     * | Compressed(1B) | Length(4B, big-endian) | Message(N) |
     * +----------------+-------------------+-----------+
     * </pre></p>
     */
    private byte[] buildGrpcFrame(byte[] message) {
        byte[] frame = new byte[5 + message.length];
        frame[0] = 0;
        frame[1] = (byte) ((message.length >> 24) & 0xFF);
        frame[2] = (byte) ((message.length >> 16) & 0xFF);
        frame[3] = (byte) ((message.length >> 8) & 0xFF);
        frame[4] = (byte) (message.length & 0xFF);
        System.arraycopy(message, 0, frame, 5, message.length);
        return frame;
    }

    /**
     * Parse a gRPC message frame and extract the message bytes.
     *
     * @param frame the full gRPC frame bytes
     * @return the message bytes, or null if the frame is too short
     */
    private byte[] parseGrpcFrame(byte[] frame) {
        if (frame == null || frame.length < 5) return null;
        int length = ((frame[1] & 0xFF) << 24)
                | ((frame[2] & 0xFF) << 16)
                | ((frame[3] & 0xFF) << 8)
                | (frame[4] & 0xFF);
        if (length < 0 || frame.length < 5 + length) return null;
        byte[] message = new byte[length];
        System.arraycopy(frame, 5, message, 0, length);
        return message;
    }

    /**
     * Send a gRPC error response with the given status code and message.
     *
     * @param ctx    channel context
     * @param status gRPC status code (0 = OK, 5 = NOT_FOUND, 12 = UNIMPLEMENTED, etc.)
     * @param msg    error message
     */
    private void sendGrpcError(ChannelHandlerContext ctx, int status, String msg) {
        try {
            byte[] emptyFrame = buildGrpcFrame(new byte[0]);
            FullHttpResponse response = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1, HttpResponseStatus.OK,
                    Unpooled.copiedBuffer(emptyFrame));
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, CONTENT_TYPE_GRPC);
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, emptyFrame.length);
            response.headers().set(HttpHeaderNames.TRANSFER_ENCODING, HttpHeaderValues.CHUNKED);
            response.headers().set(GRPC_STATUS, String.valueOf(status));
            if (msg != null && !msg.isEmpty()) {
                response.headers().set(GRPC_MESSAGE, msg);
            }
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        } catch (Exception e) {
            log.error("Error sending gRPC error response: {}", e.getMessage());
            ctx.close();
        }
    }

    private void sendError(ChannelHandlerContext ctx, String message) {
        sendGrpcError(ctx, 13, message);
    }

    private Map<String, String> extractHeaders(HttpRequest request) {
        Map<String, String> headers = new HashMap<String, String>();
        for (Map.Entry<String, String> entry : request.headers()) {
            headers.put(entry.getKey(), entry.getValue());
        }
        return headers;
    }

    private static String bytesToHex(byte[] bytes) {
        // Lookup-table conversion — avoids String.format on hot path (High 7).
        return HexUtils.bytesToHex(bytes);
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

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("GrpcStubHandler error: {}", cause.getMessage());
        ctx.close();
    }
}
