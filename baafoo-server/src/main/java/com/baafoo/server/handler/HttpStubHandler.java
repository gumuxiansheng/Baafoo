package com.baafoo.server.handler;

import com.baafoo.core.config.ServerConfig;
import com.baafoo.core.model.EnvironmentMode;
import com.baafoo.core.model.RecordingEntry;
import com.baafoo.core.model.Rule;
import com.baafoo.core.util.MatchEngine;
import com.baafoo.server.storage.StorageService;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import io.netty.channel.EventLoopGroup;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Netty handler for HTTP stub server (port 9000).
 *
 * <p>Matches incoming HTTP requests against stored rules and returns
 * pre-configured stub responses. Unmatched requests are passed through
 * (proxied to the real downstream) by default. This behavior can be
 * overridden via the {@code baafoo.stub.unmatched-default} config option.</p>
 *
 * <p>Responsibilities are delegated to:
 * <ul>
 *   <li>{@link AgentResolver} — agent identity and environment resolution</li>
 *   <li>{@link PassthroughProxy} — async HTTP forwarding to downstream</li>
 *   <li>{@link StubResponseRenderer} — stub response rendering</li>
 *   <li>{@link RecordingHelper} — recording entry construction</li>
 * </ul></p>
 */
public class HttpStubHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Logger log = LoggerFactory.getLogger(HttpStubHandler.class);

    private final StorageService storage;
    private final MatchEngine matchEngine;
    private final ServerConfig config;
    private final AgentResolver agentResolver;
    private final PassthroughProxy passthroughProxy;

    public HttpStubHandler(StorageService storage, ServerConfig config, EventLoopGroup workerGroup) {
        this.storage = storage;
        this.config = config;
        this.matchEngine = new MatchEngine();
        this.agentResolver = new AgentResolver(storage);
        this.passthroughProxy = new PassthroughProxy(workerGroup);
    }

    /**
     * Compatibility constructor for testing (no async passthrough support).
     */
    HttpStubHandler(StorageService storage, ServerConfig config) {
        this.storage = storage;
        this.config = config;
        this.matchEngine = new MatchEngine();
        this.agentResolver = new AgentResolver(storage);
        this.passthroughProxy = null;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        String method = request.method().name();
        String uri = request.uri();
        String path = extractPath(uri);
        Map<String, String> queryParams = parseQueryParams(uri);
        Map<String, String> headers = extractHeaders(request);
        String body = request.content().toString(StandardCharsets.UTF_8);

        // Extract original host and port from Host header
        String host = null;
        int port = 80;
        String hostHeader = headers.get("Host");
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

        // Resolve agent info (single pass over agent list)
        AgentResolver.AgentInfo agentInfo = agentResolver.resolveAll(ctx);
        String agentEnvironment = agentInfo.environment;
        String agentId = agentInfo.agentId;
        String agentIp = agentInfo.agentIp;

        // Match against rules — only enabled rules for this agent's environment.
        // The agent redirects ALL traffic to a matched host:port regardless of
        // per-rule conditions, so the stub server must filter by environment here
        // to prevent rules from other environments from matching.
        List<Rule> rules = agentResolver.filterRulesByEnvironment(
                storage.listRules(), agentEnvironment);
        MatchEngine.MatchResult result = matchEngine.match(
                rules, "http", host, port, null,
                method, path, headers, queryParams, body);

        if (!result.isMatched()) {
            result = matchEngine.match(
                    rules, "http", host, 0, null,
                    method, path, headers, queryParams, body);
        }

        if (result.isMatched()) {
            EnvironmentMode currentMode = agentResolver.resolveEnvironmentMode(agentEnvironment);

            if (currentMode == EnvironmentMode.PASSTHROUGH || currentMode == EnvironmentMode.RECORD) {
                log.info("Mode {} — passthrough for matched rule: {} {}", currentMode.getValue(), method, path);
                handlePassthroughAndRecord(ctx, method, host, port, path, queryParams, headers, body,
                        result, agentEnvironment, agentId, agentIp);
            } else {
                if (currentMode == EnvironmentMode.RECORD_AND_STUB) {
                    RecordingEntry rec = RecordingHelper.buildFromStub(result, "http", host, port, method, path, headers, body);
                    rec.setAgentId(agentId);
                    rec.setAgentIp(agentIp);
                    storage.addRecording(rec);
                }
                StubResponseRenderer.sendStubResponse(ctx, result.getResponse(), result.getRule().getId(),
                        method, path, host, headers, queryParams, body);
            }
        } else {
            String unmatchedDefault = config.getUnmatchedDefault();
            if ("404".equalsIgnoreCase(unmatchedDefault)) {
                log.info("No Baafoo rule matched: {} {} — returning 404", method, path);
                StubResponseRenderer.send404Response(ctx, method, path);
            } else {
                log.info("No Baafoo rule matched: {} {} — passthrough", method, path);
                handlePassthrough(ctx, method, host, port, path, queryParams, headers, body);
            }
        }
    }

    private void handlePassthroughAndRecord(ChannelHandlerContext ctx, String method, String host, int port,
                                             String path, Map<String, String> queryParams,
                                             Map<String, String> headers, String requestBody,
                                             MatchEngine.MatchResult matchResult, String agentEnvironment,
                                             String agentId, String agentIp) {
        if (passthroughProxy == null) {
            StubResponseRenderer.sendError(ctx, HttpResponseStatus.BAD_GATEWAY, "Passthrough not available");
            return;
        }
        long startTime = System.currentTimeMillis();

        passthroughProxy.forward(method, host, port, path, queryParams, headers, requestBody)
                .whenComplete((result, error) -> {
                        if (error != null) {
                            log.error("Passthrough+record error: {}", error.getMessage());
                            if (agentEnvironment != null) {
                                RecordingEntry recording = RecordingHelper.buildError(
                                        matchResult.getRule() != null ? matchResult.getRule().getId() : null,
                                        agentEnvironment, host, port, method, path, headers, requestBody,
                                        error.getMessage(), System.currentTimeMillis() - startTime,
                                        agentId, agentIp);
                                storage.addRecording(recording);
                            }
                            ctx.executor().execute(() ->
                                    StubResponseRenderer.sendError(ctx, HttpResponseStatus.BAD_GATEWAY,
                                            "Passthrough failed: " + error.getMessage()));
                            return;
                        }

                        // Build Netty response from downstream result
                        FullHttpResponse response = new DefaultFullHttpResponse(
                                HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(result.statusCode),
                                Unpooled.copiedBuffer(result.responseBody));
                        for (Map.Entry<String, String> entry : result.responseHeaders.entrySet()) {
                            if (!PassthroughProxy.HOP_BY_HOP_HEADERS.contains(entry.getKey().toLowerCase())) {
                                response.headers().set(entry.getKey(), entry.getValue());
                            }
                        }
                        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, result.responseBody.length);
                        response.headers().set("X-Baafoo-Stub", "passthrough");

                        // Record the passthrough response
                        if (agentEnvironment != null) {
                            java.nio.charset.Charset recordCharset = StubResponseRenderer.parseCharsetFromContentType(
                                    result.responseHeaders.get("Content-Type"));
                            String responseBodyStr = new String(result.responseBody, recordCharset);
                            RecordingEntry recording = RecordingHelper.buildFromPassthrough(
                                    matchResult.getRule() != null ? matchResult.getRule().getId() : null,
                                    agentEnvironment, host, port, method, path, headers, requestBody,
                                    result.statusCode, result.responseHeaders, responseBodyStr,
                                    result.responseTimeMs, agentId, agentIp);
                            storage.addRecording(recording);
                        }

                        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
                });
    }

    private void handlePassthrough(ChannelHandlerContext ctx, String method, String host, int port,
                                    String path, Map<String, String> queryParams,
                                    Map<String, String> headers, String requestBody) {
        if (passthroughProxy == null) {
            StubResponseRenderer.sendError(ctx, HttpResponseStatus.BAD_GATEWAY, "Passthrough not available");
            return;
        }
        passthroughProxy.forward(method, host, port, path, queryParams, headers, requestBody)
                .whenComplete((result, error) -> {
                        if (error != null) {
                            log.error("Passthrough error: {}", error.getMessage());
                            ctx.executor().execute(() ->
                                    StubResponseRenderer.sendError(ctx, HttpResponseStatus.BAD_GATEWAY,
                                            "Passthrough failed: " + error.getMessage()));
                            return;
                        }

                        FullHttpResponse response = new DefaultFullHttpResponse(
                                HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(result.statusCode),
                                Unpooled.copiedBuffer(result.responseBody));
                        for (Map.Entry<String, String> entry : result.responseHeaders.entrySet()) {
                            if (!PassthroughProxy.HOP_BY_HOP_HEADERS.contains(entry.getKey().toLowerCase())) {
                                response.headers().set(entry.getKey(), entry.getValue());
                            }
                        }
                        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, result.responseBody.length);
                        response.headers().set("X-Baafoo-Stub", "passthrough");
                        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
                });
    }

    private String extractPath(String uri) {
        int queryIdx = uri.indexOf('?');
        return queryIdx >= 0 ? uri.substring(0, queryIdx) : uri;
    }

    private Map<String, String> parseQueryParams(String uri) {
        Map<String, String> params = new java.util.HashMap<>();
        int queryIdx = uri.indexOf('?');
        if (queryIdx < 0) return params;

        String query = uri.substring(queryIdx + 1);
        for (String pair : query.split("&")) {
            int eqIdx = pair.indexOf('=');
            if (eqIdx > 0) {
                params.put(pair.substring(0, eqIdx), pair.substring(eqIdx + 1));
            }
        }
        return params;
    }

    private Map<String, String> extractHeaders(HttpRequest request) {
        Map<String, String> headers = new java.util.HashMap<>();
        for (Map.Entry<String, String> entry : request.headers()) {
            headers.put(entry.getKey(), entry.getValue());
        }
        return headers;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("HttpStubHandler error: {}", cause.getMessage());
        ctx.close();
    }
}
