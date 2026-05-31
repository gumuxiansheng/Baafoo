package com.baafoo.server.handler;

import com.baafoo.core.model.Environment;
import com.baafoo.core.model.EnvironmentMode;
import com.baafoo.core.model.RecordingEntry;
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

import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static io.netty.handler.codec.http.HttpResponseStatus.*;
import static io.netty.handler.codec.http.HttpVersion.HTTP_1_1;

/**
 * Netty handler for HTTP stub server (port 9000).
 *
 * <p>Matches incoming HTTP requests against stored rules and returns
 * pre-configured stub responses. Unmatched requests are passed through
 * (proxied to the real downstream) by default. This behavior can be
 * overridden via the {@code baafoo.stub.unmatched-default} config option.</p>
 */
public class HttpStubHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Logger log = LoggerFactory.getLogger(HttpStubHandler.class);

    private static final Set<String> HOP_BY_HOP_HEADERS = new HashSet<String>();
    static {
        HOP_BY_HOP_HEADERS.add("connection");
        HOP_BY_HOP_HEADERS.add("keep-alive");
        HOP_BY_HOP_HEADERS.add("proxy-authenticate");
        HOP_BY_HOP_HEADERS.add("proxy-authorization");
        HOP_BY_HOP_HEADERS.add("te");
        HOP_BY_HOP_HEADERS.add("trailers");
        HOP_BY_HOP_HEADERS.add("transfer-encoding");
        HOP_BY_HOP_HEADERS.add("upgrade");
        System.setProperty("sun.net.http.allowRestrictedHeaders", "true");
    }

    private static final ExecutorService PASSTHROUGH_EXECUTOR = Executors.newCachedThreadPool(
            runnable -> {
                Thread t = new Thread(runnable, "baafoo-passthrough");
                t.setDaemon(true);
                return t;
            });

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

        // Extract original host and port from Host header
        // Agent redirects httpbin.org:80 → 127.0.0.1:9000, but
        // HttpURLConnection still sends "Host: httpbin.org" (or "Host: httpbin.org:80")
        String host = null;
        int port = 80; // default HTTP port
        String hostHeader = headers.get("Host");
        if (hostHeader != null) {
            int colonIdx = hostHeader.lastIndexOf(':');
            if (colonIdx > 0 && hostHeader.indexOf(']') < 0) {
                // Has port suffix
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

        // Match against rules (filter by environments)
        List<Rule> rules = storage.listRules();
        String agentEnvironment = resolveAgentEnvironment(host, port);
        List<Rule> filteredRules = filterRulesByEnvironment(rules, agentEnvironment);
        MatchEngine.MatchResult result = matchEngine.match(
                filteredRules, "http", host, port, null,
                method, path, headers, queryParams, body);

        if (!result.isMatched()) {
            result = matchEngine.match(
                    filteredRules, "http", host, 0, null,
                    method, path, headers, queryParams, body);
        }

        if (result.isMatched()) {
            ResponseEntry entry = result.getResponse();

            if (isRecording()) {
                RecordingEntry rec = new RecordingEntry();
                rec.setRuleId(result.getRule().getId());
                rec.setProtocol("http");
                rec.setHost(host);
                rec.setPort(port);
                rec.setMethod(method);
                rec.setPath(path);
                rec.setRequestHeaders(headers);
                rec.setRequestBody(body);
                rec.setResponseStatusCode(entry.getStatusCode());
                rec.setResponseHeaders(entry.getHeaders() != null ? entry.getHeaders() : new HashMap<String, String>());
                rec.setResponseBody(entry.getBody());
                rec.setResponseTimeMs(entry.getDelayMs());
                storage.addRecording(rec);
            }

            sendStubResponse(ctx, entry, result.getRule().getId());
        } else {
            log.info("No Baafoo rule matched: {} {} — passthrough", method, path);
            sendPassthroughResponse(ctx, method, path, headers, queryParams, body, host, port);
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

    private void sendPassthroughResponse(ChannelHandlerContext ctx, String method, String path,
                                           Map<String, String> headers, Map<String, String> queryParams,
                                           String body, String host, int port) {
        if (!ctx.channel().isActive()) {
            log.warn("Channel already inactive, skipping passthrough for {} {}", method, path);
            return;
        }
        PASSTHROUGH_EXECUTOR.submit(() -> {
            HttpURLConnection conn = null;
            try {
                StringBuilder urlBuilder = new StringBuilder();
                urlBuilder.append("http://").append(host).append(":").append(port).append(path);
                if (!queryParams.isEmpty()) {
                    urlBuilder.append("?");
                    boolean first = true;
                    for (Map.Entry<String, String> entry : queryParams.entrySet()) {
                        if (!first) urlBuilder.append("&");
                        urlBuilder.append(entry.getKey()).append("=").append(entry.getValue());
                        first = false;
                    }
                }

                URL url = new URL(urlBuilder.toString());
                log.debug("Passthrough connecting to: {}", urlBuilder);
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod(method);
                conn.setConnectTimeout(5000);
                conn.setReadTimeout(30000);
                conn.setInstanceFollowRedirects(false);
                conn.setRequestProperty("Connection", "close");

                for (Map.Entry<String, String> entry : headers.entrySet()) {
                    String key = entry.getKey();
                    if ("Host".equalsIgnoreCase(key)) continue;
                    if (HOP_BY_HOP_HEADERS.contains(key.toLowerCase())) continue;
                    conn.setRequestProperty(key, entry.getValue());
                }

                if (body != null && !body.isEmpty() &&
                        ("POST".equalsIgnoreCase(method) || "PUT".equalsIgnoreCase(method) ||
                                "PATCH".equalsIgnoreCase(method))) {
                    conn.setDoOutput(true);
                    conn.getOutputStream().write(body.getBytes(StandardCharsets.UTF_8));
                    conn.getOutputStream().flush();
                    conn.getOutputStream().close();
                }

                int statusCode = conn.getResponseCode();
                log.debug("Passthrough got response: status={}, contentLength={}", statusCode, conn.getContentLength());
                HttpResponseStatus status = HttpResponseStatus.valueOf(statusCode);

                java.io.InputStream inputStream;
                if (statusCode >= 400) {
                    inputStream = conn.getErrorStream();
                } else {
                    inputStream = conn.getInputStream();
                }
                byte[] responseBytes;
                int contentLength = conn.getContentLength();
                log.debug("Passthrough reading body: contentLength={}", contentLength);
                if (contentLength > 0 && inputStream != null) {
                    responseBytes = new byte[contentLength];
                    int totalRead = 0;
                    while (totalRead < contentLength) {
                        int n = inputStream.read(responseBytes, totalRead, contentLength - totalRead);
                        if (n < 0) break;
                        totalRead += n;
                    }
                    if (totalRead < contentLength) {
                        byte[] trimmed = new byte[totalRead];
                        System.arraycopy(responseBytes, 0, trimmed, 0, totalRead);
                        responseBytes = trimmed;
                    }
                    log.debug("Passthrough read body with contentLength: {} bytes", totalRead);
                } else if (inputStream != null) {
                    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = inputStream.read(buffer)) != -1) {
                        baos.write(buffer, 0, bytesRead);
                    }
                    responseBytes = baos.toByteArray();
                    log.debug("Passthrough read body with fallback: {} bytes", responseBytes.length);
                } else {
                    responseBytes = new byte[0];
                    log.debug("Passthrough no body");
                }
                if (inputStream != null) {
                    inputStream.close();
                }

                if (!ctx.channel().isActive()) {
                    log.warn("Channel became inactive during passthrough for {} {}", method, path);
                    return;
                }

                log.debug("Passthrough writing response to client: {} bytes", responseBytes.length);

                FullHttpResponse response = new DefaultFullHttpResponse(
                        HTTP_1_1, status,
                        Unpooled.copiedBuffer(responseBytes));

                for (Map.Entry<String, List<String>> entry : conn.getHeaderFields().entrySet()) {
                    if (entry.getKey() == null || entry.getValue().isEmpty()) continue;
                    String lowerKey = entry.getKey().toLowerCase();
                    if (HOP_BY_HOP_HEADERS.contains(lowerKey)) continue;
                    response.headers().set(entry.getKey(), entry.getValue().get(0));
                }

                response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
                response.headers().set("X-Baafoo-Stub", "passthrough");
                response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");

                log.info("Passthrough response: {} {} status={}", method, path, statusCode);

                ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
            } catch (Exception e) {
                log.error("Passthrough error for {} {}: {}", method, path, e.getMessage());
                if (ctx.channel().isActive()) {
                    sendError(ctx, BAD_GATEWAY, "Passthrough error: " + e.getMessage());
                }
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
        });
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

    private boolean isRecording() {
        for (Environment env : storage.listEnvironments()) {
            if (env.getMode() == EnvironmentMode.RECORD || env.getMode() == EnvironmentMode.RECORD_AND_STUB) {
                return true;
            }
        }
        return false;
    }

    private String resolveAgentEnvironment(String host, int port) {
        for (StorageService.AgentRegistration agent : storage.listAgents()) {
            long onlineThreshold = System.currentTimeMillis() - 90000;
            if (agent.lastHeartbeat > onlineThreshold && agent.environment != null) {
                return agent.environment;
            }
        }
        return null;
    }

    private List<Rule> filterRulesByEnvironment(List<Rule> rules, String agentEnvironment) {
        List<Rule> filtered = new ArrayList<Rule>();
        for (Rule rule : rules) {
            if (!rule.isEnabled()) continue;
            List<String> envs = rule.getEnvironments();
            if (envs == null || envs.isEmpty()) continue;
            if (agentEnvironment != null && envs.contains(agentEnvironment)) {
                filtered.add(rule);
            }
        }
        return filtered;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("HttpStubHandler error: {}", cause.getMessage());
        ctx.close();
    }
}
