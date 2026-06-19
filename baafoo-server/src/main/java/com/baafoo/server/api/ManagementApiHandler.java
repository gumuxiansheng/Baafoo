package com.baafoo.server.api;

import com.baafoo.core.api.ApiResponse;
import com.baafoo.core.config.ServerConfig;
import com.baafoo.core.util.ChaosManager;
import com.baafoo.server.auth.AuthService;
import com.baafoo.server.storage.StorageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;

public class ManagementApiHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Logger log = LoggerFactory.getLogger(ManagementApiHandler.class);
    private static final String API_PREFIX = "/__baafoo__/api/";

    private final StorageService storage;
    private final AuthService authService;
    private final ObjectMapper mapper;
    private final List<ResourceHandler> handlers;
    private final ChaosManager chaosManager;
    private final ServerConfig config;

    public ManagementApiHandler(StorageService storage, AuthService authService) {
        this(storage, authService, new ChaosManager(), null);
    }

    public ManagementApiHandler(StorageService storage, AuthService authService,
                                 ChaosManager chaosManager) {
        this(storage, authService, chaosManager, null);
    }

    public ManagementApiHandler(StorageService storage, AuthService authService,
                                 ServerConfig config) {
        this(storage, authService, new ChaosManager(), config);
    }

    public ManagementApiHandler(StorageService storage, AuthService authService,
                                 ChaosManager chaosManager, ServerConfig config) {
        this.storage = storage;
        this.authService = authService;
        this.mapper = new ObjectMapper();
        this.chaosManager = chaosManager != null ? chaosManager : new ChaosManager();
        this.config = config;
        this.handlers = Arrays.asList(
                new AuthApiHandler(),
                new UserApiHandler(),
                new RuleApiHandler(),
                new EnvironmentApiHandler(),
                new AgentApiHandler(),
                new SceneApiHandler(),
                new MqRelationshipApiHandler(),
                new RecordingApiHandler(),
                new StatusApiHandler(),
                new ChaosApiHandler(this.chaosManager)
        );
    }

    /**
     * Get the Chaos manager instance (for programmatic profile registration).
     */
    public ChaosManager getChaosManager() {
        return chaosManager;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        String uri = request.uri();
        String method = request.method().name();

        try {
            String path = ApiUtils.extractPath(uri);

            if (path.startsWith(API_PREFIX)) {
                Object result = handleApiRequest(path, method, request, ctx, uri);
                if (result instanceof RawJsonResponse) {
                    sendRawJson(ctx, 200, (RawJsonResponse) result);
                } else {
                    sendJson(ctx, 200, result);
                }
            } else {
                ctx.fireChannelRead(request.retain());
            }
        } catch (ApiException e) {
            sendJson(ctx, e.getStatusCode(), ApiResponse.fail(e.getStatusCode(), e.getMessage()));
        } catch (Exception e) {
            log.error("API error: {}", e.getMessage(), e);
            sendJson(ctx, 500, ApiResponse.internalError(e.getMessage()));
        }
    }

    private Object handleApiRequest(String path, String method, FullHttpRequest request, ChannelHandlerContext ctx, String uri) throws Exception {
        if ("OPTIONS".equals(method)) {
            return ApiResponse.ok("OK", null);
        }

        String remoteAddr = resolveRemoteAddr(ctx, request);
        AuthService.AuthResult auth = authenticate(request, remoteAddr);

        if (!auth.isSuccess() && !path.startsWith(API_PREFIX + "auth/")) {
            throw new ApiException(401, "Authentication failed: " + auth.getMessage());
        }

        ApiContext apiCtx = new ApiContext(storage, authService, mapper, uri, auth, remoteAddr);
        String body = request.content().toString(StandardCharsets.UTF_8);

        for (ResourceHandler handler : handlers) {
            Object result = handler.handle(method, path, body, apiCtx);
            if (result != null) return result;
        }

        throw new ApiException(404, "API endpoint not found: " + method + " " + path);
    }

    private String resolveRemoteAddr(ChannelHandlerContext ctx, FullHttpRequest request) {
        String forwarded = request.headers().get("X-Forwarded-For");
        if (forwarded != null && !forwarded.isEmpty()) {
            return forwarded.split(",")[0].trim();
        }
        if (ctx.channel().remoteAddress() != null) {
            String addr = ctx.channel().remoteAddress().toString();
            if (addr.startsWith("/")) addr = addr.substring(1);
            int colonIdx = addr.indexOf(':');
            return colonIdx > 0 ? addr.substring(0, colonIdx) : addr;
        }
        return "unknown";
    }

    private AuthService.AuthResult authenticate(FullHttpRequest request, String remoteAddr) {
        String authHeader = request.headers().get("Authorization");
        String apiKeyHeader = request.headers().get("X-Api-Key");
        return authService.authenticate(authHeader, apiKeyHeader, remoteAddr);
    }

    private void sendJson(ChannelHandlerContext ctx, int statusCode, Object data) {
        try {
            String json = mapper.writeValueAsString(data);
            FullHttpResponse response = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(statusCode),
                    Unpooled.copiedBuffer(json, StandardCharsets.UTF_8));
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
            applyCorsHeaders(response);
            response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, PUT, DELETE, OPTIONS");
            response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "Content-Type, Authorization, X-Api-Key");

            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        } catch (Exception e) {
            log.error("Error serializing response: {}", e.getMessage());
            ctx.close();
        }
    }

    /**
     * Apply the Access-Control-Allow-Origin header from the configured corsOrigins.
     * Falls back to "*" when no config is available or CORS is not explicitly configured.
     */
    private void applyCorsHeaders(FullHttpResponse response) {
        String origin = "*";
        if (config != null && config.getCorsOrigins() != null && !config.getCorsOrigins().isEmpty()) {
            // Join multiple origins or use the first; "*" is replaced by the configured list.
            List<String> origins = config.getCorsOrigins();
            origin = origins.size() == 1 ? origins.get(0) : String.join(", ", origins);
        }
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, origin);
    }

    private void sendRawJson(ChannelHandlerContext ctx, int statusCode, RawJsonResponse raw) {
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(statusCode),
                Unpooled.copiedBuffer(raw.getJson(), StandardCharsets.UTF_8));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, raw.getContentType() + "; charset=UTF-8");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
        applyCorsHeaders(response);
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, PUT, DELETE, OPTIONS");
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "Content-Type, Authorization, X-Api-Key");
        response.headers().set("Content-Disposition", "attachment; filename=\"baafoo-export.har\"");

        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("ManagementApiHandler error: {}", cause.getMessage());
        ctx.close();
    }

    public static class ApiException extends RuntimeException {
        private final int statusCode;
        private final String error;
        private final String requiredRole;
        private final String yourRole;

        public ApiException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
            this.error = null;
            this.requiredRole = null;
            this.yourRole = null;
        }

        public ApiException(int statusCode, String error, String requiredRole, String yourRole) {
            super(error);
            this.statusCode = statusCode;
            this.error = error;
            this.requiredRole = requiredRole;
            this.yourRole = yourRole;
        }

        public int getStatusCode() { return statusCode; }
        public String getError() { return error; }
        public String getRequiredRole() { return requiredRole; }
        public String getYourRole() { return yourRole; }
    }
}
