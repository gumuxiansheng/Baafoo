package com.baafoo.server.auth;

import com.baafoo.core.api.ApiResponse;
import com.baafoo.core.config.ServerConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;

/**
 * Auth filter that checks permissions on all /__baafoo__/api/* requests.
 * Placed before ManagementApiHandler in the Netty pipeline.
 *
 * <p>Skips permission checks for:
 * <ul>
 *   <li>Non-API paths (passed through)</li>
 *   <li>OPTIONS requests (CORS preflight)</li>
 *   <li>Auth endpoints (/api/auth/*) — needed for login</li>
 * </ul></p>
 *
 * <p>For all other API requests, authenticates the user and checks
 * permission based on the inferred resource and HTTP method.</p>
 */
public class AuthFilter extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Logger log = LoggerFactory.getLogger(AuthFilter.class);
    private static final String API_PREFIX = "/__baafoo__/api/";
    private static final String AUTH_PREFIX = "/__baafoo__/api/auth/";

    private final AuthService authService;
    private final ServerConfig config;
    private final ObjectMapper mapper = new ObjectMapper();

    public AuthFilter(AuthService authService, ServerConfig config) {
        this.authService = authService;
        this.config = config;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        String uri = request.uri();
        String path = extractPath(uri);

        // Pass through non-API paths
        if (!path.startsWith(API_PREFIX)) {
            ctx.fireChannelRead(request.retain());
            return;
        }

        String method = request.method().name();

        // Allow CORS preflight
        if ("OPTIONS".equals(method)) {
            ctx.fireChannelRead(request.retain());
            return;
        }

        // Allow auth endpoints without permission check (needed for login)
        if (path.startsWith(AUTH_PREFIX)) {
            ctx.fireChannelRead(request.retain());
            return;
        }

        // Authenticate
        String remoteAddr = resolveRemoteAddr(ctx, request);
        String authHeader = request.headers().get("Authorization");
        String apiKeyHeader = request.headers().get("X-Api-Key");
        AuthService.AuthResult auth = authService.authenticate(authHeader, apiKeyHeader, remoteAddr);

        if (!auth.isSuccess()) {
            sendJson(ctx, 401, ApiResponse.fail(401, "Authentication failed: " + auth.getMessage()));
            return;
        }

        // Check permission using the simplified checkPermission method
        if (!AuthService.checkPermission(auth.getRole(), method, path)) {
            String resource = AuthService.inferResourceFromPath(path);
            String requiredRole = getRequiredRoleForAction(resource, methodToAction(method));
            sendJson(ctx, 403, ApiResponse.fail(403, "Permission denied: requires " + requiredRole + " role, you have " + auth.getRole()));
            return;
        }

        // Set auth info in headers for downstream handlers
        request.headers().set("X-Baafoo-Auth-Role", auth.getRole() != null ? auth.getRole() : "");
        request.headers().set("X-Baafoo-Auth-User", auth.getUsername() != null ? auth.getUsername() : "");

        ctx.fireChannelRead(request.retain());
    }

    private static String extractPath(String uri) {
        int queryIdx = uri.indexOf('?');
        return queryIdx >= 0 ? uri.substring(0, queryIdx) : uri;
    }

    private String methodToAction(String method) {
        switch (method.toUpperCase()) {
            case "POST": return "create";
            case "PUT":
            case "PATCH": return "update";
            case "DELETE": return "delete";
            default: return "read";
        }
    }

    private static String getRequiredRoleForAction(String resource, String action) {
        if ("read".equals(action)) return "guest";
        if ("rule".equals(resource)) return "developer";
        if ("scene".equals(resource)) return "tester";
        if ("environment".equals(resource)) return "admin";
        if ("recording".equals(resource)) return "tester";
        if ("user".equals(resource)) return "admin";
        return "admin";
    }

    private String resolveRemoteAddr(ChannelHandlerContext ctx, FullHttpRequest request) {
        // Get the direct connection address first
        String directAddr = "unknown";
        if (ctx.channel().remoteAddress() != null) {
            String addr = ctx.channel().remoteAddress().toString();
            if (addr.startsWith("/")) addr = addr.substring(1);
            int colonIdx = addr.indexOf(':');
            directAddr = colonIdx > 0 ? addr.substring(0, colonIdx) : addr;
        }

        // Only trust X-Forwarded-For when the direct connection is from a trusted
        // proxy. Trusted proxies may be specified as exact IPs or CIDR ranges.
        String forwarded = request.headers().get("X-Forwarded-For");
        if (forwarded != null && !forwarded.isEmpty() && isTrustedProxy(directAddr)) {
            return forwarded.split(",")[0].trim();
        }

        return directAddr;
    }

    private boolean isTrustedProxy(String addr) {
        if (config.getAuth() == null || config.getAuth().getTrustedProxies() == null) {
            return false;
        }
        for (String entry : config.getAuth().getTrustedProxies()) {
            if (entry == null || entry.isEmpty()) continue;
            if (entry.equals(addr)) return true;
            if (entry.contains("/")) {
                if (isInCidr(addr, entry)) return true;
            }
        }
        return false;
    }

    private static boolean isInCidr(String addr, String cidr) {
        try {
            String[] parts = cidr.split("/");
            if (parts.length != 2) return false;
            byte[] addrBytes = java.net.InetAddress.getByName(addr).getAddress();
            int prefixLen = Integer.parseInt(parts[1]);
            byte[] cidrBytes = java.net.InetAddress.getByName(parts[0]).getAddress();
            if (addrBytes.length != cidrBytes.length) return false;
            int fullBytes = prefixLen / 8;
            int leftoverBits = prefixLen % 8;
            for (int i = 0; i < fullBytes; i++) {
                if (addrBytes[i] != cidrBytes[i]) return false;
            }
            if (leftoverBits > 0 && fullBytes < addrBytes.length) {
                int mask = 0xFF << (8 - leftoverBits);
                if ((addrBytes[fullBytes] & mask) != (cidrBytes[fullBytes] & mask)) return false;
            }
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void sendJson(ChannelHandlerContext ctx, int statusCode, Object data) {
        try {
            String json = mapper.writeValueAsString(data);
            FullHttpResponse response = new DefaultFullHttpResponse(
                    HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(statusCode),
                    Unpooled.copiedBuffer(json, StandardCharsets.UTF_8));
            response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
            response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
            response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
            response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, PUT, DELETE, OPTIONS");
            response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "Content-Type, Authorization, X-Api-Key");

            ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
        } catch (Exception e) {
            log.error("Error serializing auth filter response: {}", e.getMessage());
            ctx.close();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("AuthFilter error: {}", cause.getMessage());
        ctx.close();
    }
}
