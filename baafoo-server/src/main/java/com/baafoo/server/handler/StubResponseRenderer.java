package com.baafoo.server.handler;

import com.baafoo.core.model.ResponseEntry;
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

    public static void sendStubResponse(ChannelHandlerContext ctx, ResponseEntry entry, String ruleId,
                                         String method, String path, String host,
                                         Map<String, String> headers, Map<String, String> queryParams,
                                         String requestBody) {
        try {
            int statusCode = entry.getStatusCode();
            String rawBody = entry.getBody() != null ? entry.getBody() : "";

            // Render template variables ({{request.*}}, {{faker.*}})
            String responseBody = rawBody;
            if (rawBody.contains("{{")) {
                TemplateEngine.RequestContext templateCtx = new TemplateEngine.RequestContext(
                        method, path, host, headers, queryParams, requestBody);
                responseBody = TemplateEngine.render(rawBody, templateCtx);
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

            // CORS for Web Console
            response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");

            log.debug("Stub response: {} {} body={}bytes", statusCode,
                    entry.getName(), responseBody.length());

            // Use scheduled executor for delay instead of blocking the event loop
            if (entry.getDelayMs() > 0) {
                ctx.executor().schedule(new Runnable() {
                    @Override
                    public void run() {
                        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
                    }
                }, entry.getDelayMs(), TimeUnit.MILLISECONDS);
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
            FullHttpResponse response = new DefaultFullHttpResponse(
                    HTTP_1_1, HttpResponseStatus.NOT_FOUND,
                    Unpooled.copiedBuffer(body.getBytes(StandardCharsets.UTF_8)));
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.getBytes(StandardCharsets.UTF_8).length);
            ctx.writeAndFlush(response);
        } catch (Exception e) {
            log.error("Error serializing 404 response: {}", e.getMessage());
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
            response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
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
