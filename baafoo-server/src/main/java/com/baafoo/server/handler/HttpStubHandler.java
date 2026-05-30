package com.baafoo.server.handler;

import com.baafoo.core.model.Rule;
import com.baafoo.core.model.ResponseEntry;
import com.baafoo.core.util.MatchEngine;
import com.baafoo.server.storage.StorageService;
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

import static io.netty.handler.codec.http.HttpResponseStatus.*;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

/**
 * Netty handler for HTTP stub server (port 9000).
 *
 * <p>Matches incoming HTTP requests against stored rules and returns
 * pre-configured stub responses. Unmatched requests return 404.</p>
 */
public class HttpStubHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Logger log = LoggerFactory.getLogger(HttpStubHandler.class);

    private final StorageService storage;
    private final MatchEngine matchEngine;

    public HttpStubHandler(StorageService storage) {
        this.storage = storage;
        this.matchEngine = new MatchEngine();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        String method = request.method().name();
        String uri = request.uri();
        String path = extractPath(uri);
        Map<String, String> queryParams = parseQueryParams(uri);
        Map<String, String> headers = extractHeaders(request);
        String body = request.content().toString(StandardCharsets.UTF_8);

        // Match against rules
        List<Rule> rules = storage.listRules();
        MatchEngine.MatchResult result = matchEngine.match(
                rules, "http", "127.0.0.1", 9000, null,
                method, path, headers, queryParams, body);

        if (result.isMatched()) {
            ResponseEntry entry = result.getResponse();
            sendStubResponse(ctx, entry, result.getRule().getId());
        } else {
            // Unmatched = 404 (safety design)
            sendError(ctx, NOT_FOUND, "No Baafoo rule matched: " + method + " " + path);
        }
    }

    private void sendStubResponse(ChannelHandlerContext ctx, ResponseEntry entry, String ruleId) {
        try {
            // Apply delay if configured
            if (entry.getDelayMs() > 0) {
                Thread.sleep(entry.getDelayMs());
            }

            int statusCode = entry.getStatusCode();
            String responseBody = entry.getBody() != null ? entry.getBody() : "";
            HttpResponseStatus status = HttpResponseStatus.valueOf(statusCode);

            FullHttpResponse response = new DefaultFullHttpResponse(
                    HTTP_1_1, status,
                    Unpooled.copiedBuffer(responseBody, StandardCharsets.UTF_8));

            // Set headers
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
            response.headers().set("X-Baafoo-Rule-Id", ruleId);
            response.headers().set("X-Baafoo-Stub", "true");

            if (entry.getHeaders() != null) {
                for (Map.Entry<String, String> h : entry.getHeaders().entrySet()) {
                    response.headers().set(h.getKey(), h.getValue());
                }
            }

            // CORS for Web Console
            response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");

            log.debug("Stub response: {} {} body={}bytes", statusCode,
                    entry.getName(), responseBody.length());

            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        } catch (Exception e) {
            log.error("Error sending stub response: {}", e.getMessage());
            sendError(ctx, INTERNAL_SERVER_ERROR, "Stub response error: " + e.getMessage());
        }
    }

    private void sendError(ChannelHandlerContext ctx, HttpResponseStatus status, String message) {
        String json = "{\"error\":\"" + message + "\",\"stubbed\":false}";
        FullHttpResponse response = new DefaultFullHttpResponse(
                HTTP_1_1, status,
                Unpooled.copiedBuffer(json, StandardCharsets.UTF_8));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        response.headers().set("X-Baafoo-Stub", "unmatched");

        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    private String extractPath(String uri) {
        int queryIdx = uri.indexOf('?');
        return queryIdx >= 0 ? uri.substring(0, queryIdx) : uri;
    }

    private Map<String, String> parseQueryParams(String uri) {
        Map<String, String> params = new HashMap<String, String>();
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
        Map<String, String> headers = new HashMap<String, String>();
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
