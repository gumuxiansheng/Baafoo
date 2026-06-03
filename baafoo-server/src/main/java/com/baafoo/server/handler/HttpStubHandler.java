package com.baafoo.server.handler;

import com.baafoo.core.config.ServerConfig;
import com.baafoo.core.model.Environment;
import com.baafoo.core.model.EnvironmentMode;
import com.baafoo.core.model.RecordingEntry;
import com.baafoo.core.model.Rule;
import com.baafoo.core.model.ResponseEntry;
import com.baafoo.core.util.MatchEngine;
import com.baafoo.core.util.TemplateEngine;
import com.baafoo.server.storage.StorageService;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import io.netty.util.concurrent.ScheduledFuture;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.OutputStream;
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
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import javax.net.ssl.HttpsURLConnection;

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

    private static final ExecutorService PASSTHROUGH_EXECUTOR = new ThreadPoolExecutor(
            4, 64, 60L, TimeUnit.SECONDS,
            new LinkedBlockingQueue<Runnable>(1000),
            runnable -> {
                Thread t = new Thread(runnable, "baafoo-passthrough");
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.CallerRunsPolicy());

    private final StorageService storage;
    private final MatchEngine matchEngine;
    private final ServerConfig config;

    public HttpStubHandler(StorageService storage, ServerConfig config) {
        this.storage = storage;
        this.config = config;
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
        String agentId = resolveAgentId(agentEnvironment);
        String agentIp = resolveAgentIp(agentEnvironment);
        if (agentIp == null) {
            String channelIp = resolveAgentIpFromChannel(ctx);
            // Only use channel IP if it's not loopback and we have no registered IP
            if (channelIp != null && !"127.0.0.1".equals(channelIp) && !"0:0:0:0:0:0:0:1".equals(channelIp)) {
                agentIp = channelIp;
            } else if (channelIp != null && agentIp == null) {
                // Last resort: use channel IP even if loopback
                agentIp = channelIp;
            }
        }
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
            EnvironmentMode currentMode = resolveEnvironmentMode(agentEnvironment);

            if (currentMode == EnvironmentMode.PASSTHROUGH || currentMode == EnvironmentMode.RECORD) {
                log.info("Mode {} — passthrough for matched rule: {} {}", currentMode.getValue(), method, path);
                sendPassthroughAndRecord(ctx, method, host, port, path, queryParams, headers, body,
                        result, agentEnvironment, agentId, agentIp);
            } else {
                if (currentMode == EnvironmentMode.RECORD_AND_STUB) {
                    RecordingEntry rec = buildRecordingFromStub(result, host, port, method, path, headers, body);
                    rec.setAgentId(agentId);
                    rec.setAgentIp(agentIp);
                    storage.addRecording(rec);
                }
                sendStubResponse(ctx, result.getResponse(), result.getRule().getId(),
                        method, path, host, headers, queryParams, body);
            }
        } else {
            String unmatchedDefault = config.getUnmatchedDefault();
            if ("404".equalsIgnoreCase(unmatchedDefault)) {
                log.info("No Baafoo rule matched: {} {} — returning 404", method, path);
                send404Response(ctx, method, path);
            } else {
                log.info("No Baafoo rule matched: {} {} — passthrough", method, path);
                sendPassthroughResponse(ctx, method, host, port, path, queryParams, headers, body);
            }
        }
    }

    private void send404Response(ChannelHandlerContext ctx, String method, String path) {
        try {
            java.util.Map<String, Object> errorMap = new java.util.HashMap<String, Object>();
            errorMap.put("error", "No matching rule found");
            errorMap.put("path", path);
            String body = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(errorMap);
            FullHttpResponse response = new DefaultFullHttpResponse(
                    HTTP_1_1, NOT_FOUND,
                    Unpooled.copiedBuffer(body.getBytes(StandardCharsets.UTF_8)));
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, body.getBytes(StandardCharsets.UTF_8).length);
            ctx.writeAndFlush(response);
        } catch (Exception e) {
            log.error("Error serializing 404 response: {}", e.getMessage());
            ctx.close();
        }
    }

    private EnvironmentMode resolveEnvironmentMode(String agentEnvironment) {
        if (agentEnvironment != null) {
            for (Environment env : storage.listEnvironments()) {
                if (agentEnvironment.equals(env.getName())) {
                    return env.getMode();
                }
            }
        }
        for (Environment env : storage.listEnvironments()) {
            return env.getMode();
        }
        return EnvironmentMode.STUB;
    }

    private RecordingEntry buildRecordingFromStub(MatchEngine.MatchResult result, String host, int port,
                                                   String method, String path,
                                                   Map<String, String> headers, String body) {
        ResponseEntry entry = result.getResponse();
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
        return rec;
    }

    private void sendPassthroughAndRecord(ChannelHandlerContext ctx, String method, String host, int port,
                                           String path, Map<String, String> queryParams,
                                           Map<String, String> headers, String requestBody,
                                           MatchEngine.MatchResult matchResult, String agentEnvironment,
                                           String agentId, String agentIp) {
        PASSTHROUGH_EXECUTOR.submit(() -> {
            long startTime = System.currentTimeMillis();
            try {
                PassthroughResult result = doPassthrough(method, host, port, path, queryParams, headers, requestBody);

                FullHttpResponse response = new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(result.statusCode),
                        Unpooled.copiedBuffer(result.responseBody));
                for (Map.Entry<String, String> entry : result.responseHeaders.entrySet()) {
                    if (!HOP_BY_HOP_HEADERS.contains(entry.getKey().toLowerCase())) {
                        response.headers().set(entry.getKey(), entry.getValue());
                    }
                }
                response.headers().set(HttpHeaderNames.CONTENT_LENGTH, result.responseBody.length);
                response.headers().set("X-Baafoo-Stub", "passthrough");
                ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);

                if (agentEnvironment != null) {
                    RecordingEntry recording = new RecordingEntry();
                    recording.setRuleId(matchResult.getRule() != null ? matchResult.getRule().getId() : null);
                    recording.setEnvironmentId(agentEnvironment);
                    recording.setProtocol("http");
                    recording.setHost(host);
                    recording.setPort(port);
                    recording.setMethod(method);
                    recording.setPath(path);
                    recording.setRequestHeaders(headers);
                    recording.setRequestBody(requestBody);
                    recording.setResponseStatusCode(result.statusCode);
                    recording.setResponseHeaders(result.responseHeaders);
                    // Detect charset from downstream Content-Type, fallback UTF-8
                    java.nio.charset.Charset recordCharset = parseCharsetFromContentType(result.responseHeaders.get("Content-Type"));
                    recording.setResponseBody(new String(result.responseBody, recordCharset));
                    recording.setResponseTimeMs(result.responseTimeMs);
                    recording.setAgentId(agentId);
                    recording.setAgentIp(agentIp);
                    storage.addRecording(recording);
                }
            } catch (Exception e) {
                log.error("Passthrough+record error: {}", e.getMessage());
                if (agentEnvironment != null) {
                    RecordingEntry recording = new RecordingEntry();
                    recording.setRuleId(matchResult.getRule() != null ? matchResult.getRule().getId() : null);
                    recording.setEnvironmentId(agentEnvironment);
                    recording.setProtocol("http");
                    recording.setHost(host);
                    recording.setPort(port);
                    recording.setMethod(method);
                    recording.setPath(path);
                    recording.setRequestHeaders(headers);
                    recording.setRequestBody(requestBody);
                    recording.setResponseStatusCode(502);
                    recording.setResponseBody("Passthrough failed: " + e.getMessage());
                    recording.setResponseTimeMs(System.currentTimeMillis() - startTime);
                    recording.setAgentId(agentId);
                    recording.setAgentIp(agentIp);
                    storage.addRecording(recording);
                }
                sendError(ctx, HttpResponseStatus.BAD_GATEWAY, "Passthrough failed: " + e.getMessage());
            }
        });
    }

    private void sendStubResponse(ChannelHandlerContext ctx, ResponseEntry entry, String ruleId,
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
            java.nio.charset.Charset charset = java.nio.charset.Charset.forName(charsetName);

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
                ctx.executor().schedule(() ->
                    ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE),
                    entry.getDelayMs(), TimeUnit.MILLISECONDS);
            } else {
                ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
            }
        } catch (Exception e) {
            log.error("Error sending stub response: {}", e.getMessage());
            sendError(ctx, INTERNAL_SERVER_ERROR, "Stub response error: " + e.getMessage());
        }
    }

    private void sendPassthroughResponse(ChannelHandlerContext ctx, String method, String host, int port,
                                          String path, Map<String, String> queryParams,
                                          Map<String, String> headers, String requestBody) {
        PASSTHROUGH_EXECUTOR.submit(() -> {
            try {
                PassthroughResult result = doPassthrough(method, host, port, path, queryParams, headers, requestBody);

                FullHttpResponse response = new DefaultFullHttpResponse(
                        HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(result.statusCode),
                        Unpooled.copiedBuffer(result.responseBody));
                for (Map.Entry<String, String> entry : result.responseHeaders.entrySet()) {
                    if (!HOP_BY_HOP_HEADERS.contains(entry.getKey().toLowerCase())) {
                        response.headers().set(entry.getKey(), entry.getValue());
                    }
                }
                response.headers().set(HttpHeaderNames.CONTENT_LENGTH, result.responseBody.length);
                response.headers().set("X-Baafoo-Stub", "passthrough");
                ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
            } catch (Exception e) {
                log.error("Passthrough error: {}", e.getMessage());
                sendError(ctx, HttpResponseStatus.BAD_GATEWAY, "Passthrough failed: " + e.getMessage());
            }
        });
    }

    private static class PassthroughResult {
        final int statusCode;
        final Map<String, String> responseHeaders;
        final byte[] responseBody;
        final long responseTimeMs;

        PassthroughResult(int statusCode, Map<String, String> responseHeaders, byte[] responseBody, long responseTimeMs) {
            this.statusCode = statusCode;
            this.responseHeaders = responseHeaders;
            this.responseBody = responseBody;
            this.responseTimeMs = responseTimeMs;
        }
    }

    private PassthroughResult doPassthrough(String method, String host, int port, String path,
                                             Map<String, String> queryParams, Map<String, String> headers,
                                             String requestBody) throws Exception {
        HttpURLConnection conn = null;
        try {
            long startTime = System.currentTimeMillis();

            String protocol = determineProtocol(host, port, headers);
            StringBuilder urlBuilder = new StringBuilder();
            urlBuilder.append(protocol).append("://").append(host).append(":").append(port).append(path);
            if (queryParams != null && !queryParams.isEmpty()) {
                urlBuilder.append("?");
                boolean first = true;
                for (Map.Entry<String, String> entry : queryParams.entrySet()) {
                    if (!first) urlBuilder.append("&");
                    urlBuilder.append(entry.getKey()).append("=").append(entry.getValue());
                    first = false;
                }
            }

            URL url = new URL(urlBuilder.toString());
            if ("https".equals(protocol)) {
                conn = (HttpsURLConnection) url.openConnection();
            } else {
                conn = (HttpURLConnection) url.openConnection();
            }
            conn.setRequestMethod(method);
            conn.setInstanceFollowRedirects(false);
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(10000);

            for (Map.Entry<String, String> entry : headers.entrySet()) {
                String key = entry.getKey();
                if (!HOP_BY_HOP_HEADERS.contains(key.toLowerCase()) &&
                    !"host".equalsIgnoreCase(key)) {
                    conn.setRequestProperty(key, entry.getValue());
                }
            }

            if (requestBody != null && !requestBody.isEmpty() &&
                ("POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method))) {
                conn.setDoOutput(true);
                try (OutputStream os = conn.getOutputStream()) {
                    os.write(requestBody.getBytes(StandardCharsets.UTF_8));
                    os.flush();
                }
            }

            int statusCode = conn.getResponseCode();
            HttpResponseStatus status = HttpResponseStatus.valueOf(statusCode);

            byte[] responseBytes;
            java.io.InputStream inputStream = null;
            try {
                if (statusCode >= 400) {
                    inputStream = conn.getErrorStream();
                } else {
                    inputStream = conn.getInputStream();
                }
            } catch (java.io.FileNotFoundException e) {
            }
            if (inputStream == null) {
                responseBytes = new byte[0];
            } else {
                try {
                    int contentLength = conn.getContentLength();
                    if (contentLength > 0) {
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
                    } else {
                        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                        byte[] buffer = new byte[8192];
                        int bytesRead;
                        while ((bytesRead = inputStream.read(buffer)) != -1) {
                            baos.write(buffer, 0, bytesRead);
                        }
                        responseBytes = baos.toByteArray();
                    }
                } finally {
                    inputStream.close();
                }
            }

            Map<String, String> responseHeaders = new HashMap<String, String>();
            for (Map.Entry<String, List<String>> entry : conn.getHeaderFields().entrySet()) {
                if (entry.getKey() != null && !entry.getValue().isEmpty()) {
                    responseHeaders.put(entry.getKey(), entry.getValue().get(0));
                }
            }

            long responseTimeMs = System.currentTimeMillis() - startTime;
            return new PassthroughResult(statusCode, responseHeaders, responseBytes, responseTimeMs);

        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private void sendError(ChannelHandlerContext ctx, HttpResponseStatus status, String message) {
        try {
            java.util.Map<String, Object> errorMap = new java.util.HashMap<String, Object>();
            errorMap.put("error", message);
            errorMap.put("stubbed", false);
            String json = new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(errorMap);
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

    private String resolveAgentEnvironment(String host, int port) {
        // 1. Try to match by host:port from registered agents' environments
        //    (agents register the environment they belong to)
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
            if (envs == null || envs.isEmpty()) {
                filtered.add(rule);
                continue;
            }
            if (agentEnvironment != null && envs.contains(agentEnvironment)) {
                filtered.add(rule);
            }
        }
        return filtered;
    }

    private String resolveAgentId(String agentEnvironment) {
        if (agentEnvironment == null) return null;
        for (StorageService.AgentRegistration agent : storage.listAgents()) {
            long onlineThreshold = System.currentTimeMillis() - 90000;
            if (agent.lastHeartbeat > onlineThreshold
                    && agentEnvironment.equals(agent.environment)
                    && agent.agentId != null
                    && !agent.agentId.isEmpty()) {
                return agent.agentId;
            }
        }
        return null;
    }

    private String resolveAgentIp(String agentEnvironment) {
        // 1. Try to get IP from agent registration (agentIp field, reported by agent via resolveLocalIp())
        if (agentEnvironment != null) {
            for (StorageService.AgentRegistration agent : storage.listAgents()) {
                long onlineThreshold = System.currentTimeMillis() - 90000;
                if (agent.lastHeartbeat > onlineThreshold
                        && agentEnvironment.equals(agent.environment)
                        && agent.agentIp != null
                        && !agent.agentIp.isEmpty()
                        && !"127.0.0.1".equals(agent.agentIp)) {
                    return agent.agentIp;
                }
            }
        }
        // 2. Fallback: any online agent with a non-loopback IP
        for (StorageService.AgentRegistration agent : storage.listAgents()) {
            long onlineThreshold = System.currentTimeMillis() - 90000;
            if (agent.lastHeartbeat > onlineThreshold
                    && agent.agentIp != null
                    && !agent.agentIp.isEmpty()
                    && !"127.0.0.1".equals(agent.agentIp)) {
                return agent.agentIp;
            }
        }
        // 3. Fallback: use registered IP even if it's 127.0.0.1
        for (StorageService.AgentRegistration agent : storage.listAgents()) {
            long onlineThreshold = System.currentTimeMillis() - 90000;
            if (agent.lastHeartbeat > onlineThreshold
                    && agent.agentIp != null
                    && !agent.agentIp.isEmpty()) {
                return agent.agentIp;
            }
        }
        return null;
    }

    private String resolveAgentIpFromChannel(ChannelHandlerContext ctx) {
        // 1. Check X-Forwarded-For header (if behind a proxy)
        // This won't help for direct agent connections but is good practice
        // 2. Fall back to channel remote address
        if (ctx.channel().remoteAddress() != null) {
            String addr = ctx.channel().remoteAddress().toString();
            if (addr.startsWith("/")) addr = addr.substring(1);
            int colonIdx = addr.indexOf(':');
            return colonIdx > 0 ? addr.substring(0, colonIdx) : addr;
        }
        return null;
    }

    /**
     * Parse charset from Content-Type header value.
     * E.g. "text/html; charset=GBK" → GBK, "application/json" → UTF-8
     */
    private static java.nio.charset.Charset parseCharsetFromContentType(String contentType) {
        if (contentType != null) {
            String lower = contentType.toLowerCase();
            int idx = lower.indexOf("charset=");
            if (idx >= 0) {
                String cs = contentType.substring(idx + 8).trim();
                // Strip trailing semicolon or space
                int semi = cs.indexOf(';');
                if (semi > 0) cs = cs.substring(0, semi).trim();
                try {
                    return java.nio.charset.Charset.forName(cs);
                } catch (Exception e) {
                    log.warn("Unknown charset '{}', falling back to UTF-8", cs);
                }
            }
        }
        return StandardCharsets.UTF_8;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("HttpStubHandler error: {}", cause.getMessage());
        ctx.close();
    }
}
