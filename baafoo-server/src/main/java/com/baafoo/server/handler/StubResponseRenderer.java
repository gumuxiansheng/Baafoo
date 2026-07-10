package com.baafoo.server.handler;

import com.baafoo.core.model.ResponseEntry;
import com.baafoo.core.model.Rule;
import com.baafoo.core.util.TemplateEngine;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.http.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static io.netty.handler.codec.http.HttpResponseStatus.INTERNAL_SERVER_ERROR;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

/**
 * Renders stub responses and error responses.
 *
 * <p>Extracted from HttpStubHandler to separate response rendering
 * from request handling logic.</p>
 */
public class StubResponseRenderer {

    private static final Logger log = LoggerFactory.getLogger(StubResponseRenderer.class);
    private static final com.fasterxml.jackson.databind.ObjectMapper MAPPER =
            new com.fasterxml.jackson.databind.ObjectMapper();

    /** Configured CORS origins; null/empty means fall back to "*". */
    private static volatile java.util.List<String> corsOrigins;

    /**
     * Set the CORS allowed origins from {@link com.baafoo.core.config.ServerConfig#getCorsOrigins()}.
     * Called once during server startup so stub responses respect the configured CORS policy.
     */
    public static void setCorsOrigins(java.util.List<String> origins) {
        corsOrigins = origins;
    }

    /**
     * Resolve the Access-Control-Allow-Origin header value from config.
     * M13: When multiple origins are configured, must echo the requesting
     * Origin header back (browsers reject comma-joined values in ACAO).
     * If no origins configured, fall back to "*".
     */
    private static String resolveCorsOrigin(String requestOrigin) {
        if (corsOrigins == null || corsOrigins.isEmpty()) {
            return "*";
        }
        if (corsOrigins.size() == 1) {
            return corsOrigins.get(0);
        }
        // Multiple origins: echo the request Origin if it's in the allowlist
        if (requestOrigin != null && corsOrigins.contains(requestOrigin)) {
            return requestOrigin;
        }
        // Not in allowlist — return the first configured origin (or null to deny)
        return corsOrigins.get(0);
    }

    public static void sendStubResponse(ChannelHandlerContext ctx, ResponseEntry entry, String ruleId,
                                         String method, String path, String host,
                                         Map<String, String> headers, Map<String, String> queryParams,
                                         String requestBody, String environment) {
        sendStubResponse(ctx, entry, ruleId, method, path, host, headers, queryParams,
                requestBody, environment, null, 0);
    }

    /**
     * Render and send a stub response, applying the rule's {@code fakerSeed} (if any)
     * to make Faker output deterministic for that rule.
     *
     * @param fakerSeed optional seed from {@link Rule#getFakerSeed()}; null means no seed.
     */
    public static void sendStubResponse(ChannelHandlerContext ctx, ResponseEntry entry, String ruleId,
                                         String method, String path, String host,
                                         Map<String, String> headers, Map<String, String> queryParams,
                                         String requestBody, String environment, Long fakerSeed) {
        sendStubResponse(ctx, entry, ruleId, method, path, host, headers, queryParams,
                requestBody, environment, fakerSeed, 0, 0);
    }

    /**
     * Render and send a stub response with full context.
     *
     * @param fakerSeed     optional seed from {@link Rule#getFakerSeed()}; null means no seed.
     * @param requestCount  per-rule request count (1-based) for {@code {{requestCount}}}
     *                      template variable substitution (PRD §3 R-S2 AC-13).
     */
    public static void sendStubResponse(ChannelHandlerContext ctx, ResponseEntry entry, String ruleId,
                                         String method, String path, String host,
                                         Map<String, String> headers, Map<String, String> queryParams,
                                         String requestBody, String environment, Long fakerSeed,
                                         int requestCount) {
        sendStubResponse(ctx, entry, ruleId, method, path, host, headers, queryParams,
                requestBody, environment, fakerSeed, requestCount, 0);
    }

    /**
     * Render and send a stub response with full context, including fault-injection delay.
     *
     * @param fakerSeed     optional seed from {@link Rule#getFakerSeed()}; null means no seed.
     * @param requestCount  per-rule request count (1-based) for {@code {{requestCount}}}
     *                      template variable substitution (PRD §3 R-S2 AC-13).
     * @param faultDelayMs  additional delay from fault injection (PRD §4 R-S12 DELAY);
     *                      added on top of {@link ResponseEntry#getDelayMs()}.
     */
    public static void sendStubResponse(ChannelHandlerContext ctx, ResponseEntry entry, String ruleId,
                                         String method, String path, String host,
                                         Map<String, String> headers, Map<String, String> queryParams,
                                         String requestBody, String environment, Long fakerSeed,
                                         int requestCount, long faultDelayMs) {
        try {
            int statusCode = entry.getStatusCode();
            String rawBody = entry.getBody() != null ? entry.getBody() : "";

            // Render template variables ({{request.*}}, {{faker.*}}, {{requestCount}})
            String responseBody = rawBody;
            if (rawBody.contains("{{")) {
                TemplateEngine.RequestContext templateCtx = new TemplateEngine.RequestContext(
                        method, path, host, headers, queryParams, requestBody, environment);
                templateCtx.setRequestCount(requestCount);
                responseBody = TemplateEngine.render(rawBody, templateCtx, fakerSeed);
            }
            HttpResponseStatus status = HttpResponseStatus.valueOf(statusCode);

            // Resolve charset from entry (default UTF-8)
            String charsetName = entry.getCharset() != null && !entry.getCharset().isEmpty() ? entry.getCharset() : "UTF-8";
            Charset charset = Charset.forName(charsetName);

            FullHttpResponse response = new DefaultFullHttpResponse(
                    HTTP_1_1, status,
                    Unpooled.copiedBuffer(responseBody, charset));

            // Set headers
            String contentType = "application/json; charset=" + charsetName;
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, contentType);
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
            response.headers().set("X-Baafoo-Rule-Id", ruleId);
            response.headers().set("X-Baafoo-Stub", "true");

            if (entry.getHeaders() != null) {
                for (Map.Entry<String, String> h : entry.getHeaders().entrySet()) {
                    response.headers().set(h.getKey(), h.getValue());
                }
            }

            // CORS for Web Console — respects ServerConfig.corsOrigins when set
            response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, resolveCorsOrigin(headers != null ? headers.get("Origin") : null));

            log.debug("Stub response: {} {} body={}bytes", statusCode,
                    entry.getName(), responseBody.length());

            // Use scheduled executor for delay instead of blocking the event loop.
            // Total delay = entry delay + fault injection delay (PRD §4 R-S12 DELAY).
            long totalDelayMs = entry.getDelayMs() + faultDelayMs;
            if (totalDelayMs > 0) {
                ctx.executor().schedule(new Runnable() {
                    @Override
                    public void run() {
                        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
                    }
                }, totalDelayMs, TimeUnit.MILLISECONDS);
            } else {
                ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
            }
        } catch (Exception e) {
            log.error("Error sending stub response: {}", e.getMessage());
            sendError(ctx, INTERNAL_SERVER_ERROR, "Stub response error: " + e.getMessage());
        }
    }

    public static void send404Response(ChannelHandlerContext ctx, String method, String path) {
        try {
            Map<String, Object> errorMap = new HashMap<String, Object>();
            errorMap.put("error", "No matching rule found");
            errorMap.put("path", path);
            String body = MAPPER.writeValueAsString(errorMap);
            byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
            FullHttpResponse response = new DefaultFullHttpResponse(
                    HTTP_1_1, HttpResponseStatus.NOT_FOUND,
                    Unpooled.copiedBuffer(bodyBytes));
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, bodyBytes.length);
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        } catch (Exception e) {
            log.error("Error serializing 404 response: {}", e.getMessage());
            ctx.close();
        }
    }

    /**
     * Send a fault-injection HTTP error response (PRD §4 R-S12 HTTP_ERROR).
     *
     * <p>Returns the given status code with a simple JSON error body and an
     * {@code X-Baafoo-Fault} header indicating the fault type. The response is
     * sent immediately (no delay) — DELAY faults are handled separately by
     * scheduling the normal response.</p>
     *
     * @param ctx         the channel context
     * @param statusCode  the HTTP status code to return (e.g. 503, 504)
     * @param faultType   the fault type string for the {@code X-Baafoo-Fault} header
     * @param ruleId      the rule ID for the {@code X-Baafoo-Rule-Id} header
     */
    public static void sendFaultErrorResponse(ChannelHandlerContext ctx, int statusCode,
                                                String faultType, String ruleId) {
        try {
            Map<String, Object> errorMap = new HashMap<String, Object>();
            errorMap.put("error", "Fault injected");
            errorMap.put("faultType", faultType);
            errorMap.put("statusCode", statusCode);
            String json = MAPPER.writeValueAsString(errorMap);
            // Guard against non-standard HTTP status codes that Netty doesn't recognize.
            // HttpResponseStatus.valueOf() throws IllegalArgumentException for unknown codes,
            // which would cause the client to receive a connection close instead of any response.
            HttpResponseStatus faultStatus;
            try {
                faultStatus = HttpResponseStatus.valueOf(statusCode);
            } catch (IllegalArgumentException e) {
                log.warn("Non-standard fault status code {}, falling back to 500", statusCode);
                faultStatus = INTERNAL_SERVER_ERROR;
            }
            FullHttpResponse response = new DefaultFullHttpResponse(
                    HTTP_1_1, faultStatus,
                    Unpooled.copiedBuffer(json, StandardCharsets.UTF_8));
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
            response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, resolveCorsOrigin(null));
            response.headers().set("X-Baafoo-Stub", "true");
            response.headers().set("X-Baafoo-Rule-Id", ruleId);
            response.headers().set("X-Baafoo-Fault", faultType);
            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        } catch (Exception e) {
            log.error("Error sending fault error response: {}", e.getMessage());
            ctx.close();
        }
    }

    public static void sendError(ChannelHandlerContext ctx, HttpResponseStatus status, String message) {
        try {
            Map<String, Object> errorMap = new HashMap<String, Object>();
            errorMap.put("error", message);
            errorMap.put("stubbed", false);
            String json = MAPPER.writeValueAsString(errorMap);
            FullHttpResponse response = new DefaultFullHttpResponse(
                    HTTP_1_1, status,
                    Unpooled.copiedBuffer(json, StandardCharsets.UTF_8));
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
            response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, resolveCorsOrigin(null));
            response.headers().set("X-Baafoo-Stub", "unmatched");

            ctx.writeAndFlush(response).addListener(
                    (ChannelFutureListener) future -> {
                        if (!future.isSuccess()) {
                            log.error("Failed to send error response: {}", future.cause().getMessage());
                        }
                        future.channel().close();
                    });
        } catch (Exception e) {
            log.error("Error serializing error response: {}", e.getMessage());
            ctx.close();
        }
    }

    /**
     * Parse charset from Content-Type header value.
     * E.g. "text/html; charset=GBK" → GBK, "application/json" → UTF-8
     */
    public static Charset parseCharsetFromContentType(String contentType) {
        if (contentType != null) {
            String lower = contentType.toLowerCase();
            int idx = lower.indexOf("charset=");
            if (idx >= 0) {
                String cs = contentType.substring(idx + 8).trim();
                // Strip trailing semicolon or space
                int semi = cs.indexOf(';');
                if (semi > 0) cs = cs.substring(0, semi).trim();
                try {
                    return Charset.forName(cs);
                } catch (Exception e) {
                    log.warn("Unknown charset '{}', falling back to UTF-8", cs);
                }
            }
        }
        return StandardCharsets.UTF_8;
    }
}
