package com.baafoo.server.auth;

import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.baafoo.core.config.ServerConfig;

import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Netty ChannelHandler that processes SSO callback requests at /sso/callback.
 *
 * <p>When the Ehre SSO portal authenticates a user, it redirects back to
 * {@code /sso/callback?token=<jwt>}. This handler extracts the token from
 * the query string, validates it via {@link AuthService}, sets a cookie
 * ({@code baafoo_token}) on the response, and redirects the browser to
 * the Baafoo home page.</p>
 *
 * <p>This handler must be placed <em>before</em> {@link AuthFilter} in the
 * Netty pipeline so that the callback path is reachable without
 * authentication.</p>
 */
public class SsoCallbackHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Logger log = LoggerFactory.getLogger(SsoCallbackHandler.class);

    private static final String CALLBACK_PATH = "/sso/callback";
    private static final String COOKIE_NAME = "baafoo_token";
    private static final String HOME_REDIRECT = "/";
    private static final Pattern TOKEN_PARAM = Pattern.compile("[?&]token=([^&]+)");

    private final AuthService authService;
    private final ServerConfig config;

    public SsoCallbackHandler(AuthService authService, ServerConfig config) {
        this.authService = authService;
        this.config = config;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        String uri = request.uri();
        String path = extractPath(uri);

        // Only handle the SSO callback path; pass everything else through
        if (!CALLBACK_PATH.equals(path)) {
            ctx.fireChannelRead(request.retain());
            return;
        }

        String method = request.method().name();
        if (!"GET".equals(method)) {
            sendRedirect(ctx, HOME_REDIRECT);
            return;
        }

        // Extract token from query string
        String token = extractQueryParam(uri, "token");
        if (token == null || token.isEmpty()) {
            log.warn("SSO callback received without token parameter");
            sendRedirect(ctx, HOME_REDIRECT);
            return;
        }

        // Validate the JWT token using AuthService
        // We pass the token as a Bearer header to reuse AuthService.authenticate
        AuthService.AuthResult authResult = authService.authenticate("Bearer " + token, null, "sso-callback");
        if (!authResult.isSuccess()) {
            log.warn("SSO callback token validation failed: {}", authResult.getMessage());
            // Redirect to SSO login again
            sendRedirect(ctx, config.getAuth().getSso().getLoginUrl());
            return;
        }

        log.info("SSO callback: successfully authenticated user '{}', role '{}'",
                authResult.getUsername(), authResult.getRole());

        // Set cookie and redirect to home page
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.FOUND);
        response.headers().set(HttpHeaderNames.LOCATION, HOME_REDIRECT);
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);

        // Build Set-Cookie header
        boolean secure = getSsoSecure(config);
        String cookieValue = COOKIE_NAME + "=" + token
                + "; Path=/"
                + "; HttpOnly"
                + (secure ? "; Secure" : "")
                + "; SameSite=Lax"
                + "; Max-Age=" + (4 * 60 * 60); // 4 hours, matching default token expiry
        response.headers().set(HttpHeaderNames.SET_COOKIE, cookieValue);

        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    private static String extractPath(String uri) {
        int queryIdx = uri.indexOf('?');
        return queryIdx >= 0 ? uri.substring(0, queryIdx) : uri;
    }

    private static String extractQueryParam(String uri, String paramName) {
        Matcher matcher = TOKEN_PARAM.matcher(uri);
        if (matcher.find()) {
            try {
                return java.net.URLDecoder.decode(matcher.group(1), StandardCharsets.UTF_8.name());
            } catch (Exception e) {
                return matcher.group(1);
            }
        }
        return null;
    }

    private void sendRedirect(ChannelHandlerContext ctx, String location) {
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.FOUND);
        response.headers().set(HttpHeaderNames.LOCATION, location);
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, 0);
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    private Boolean getSsoSecure(ServerConfig config) {
        if (config == null || config.getAuth() == null || config.getAuth().getSso() == null) {
            return false;
        }
        return config.getAuth().getSso().isSecure();
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.error("SsoCallbackHandler error: {}", cause.getMessage());
        ctx.close();
    }
}
