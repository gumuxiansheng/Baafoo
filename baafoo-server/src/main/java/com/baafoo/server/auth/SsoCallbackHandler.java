package com.baafoo.server.auth;

import com.baafoo.core.config.ServerConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

/**
 * Netty ChannelHandler that processes SSO callback requests at /sso/callback.
 *
 * <p>Authorization Code flow (P0-1.1 rectification):
 * <ol>
 *   <li>Ehre redirects browser here with {@code ?code=...}</li>
 *   <li>This handler POSTs to Ehre {@code /api/sso/token} to exchange code for JWT</li>
 *   <li>JWT is set as a cookie — never appears in URL</li>
 * </ol></p>
 *
 * <p>This handler must be placed <em>before</em> {@link AuthFilter} in the
 * Netty pipeline so that the callback path is reachable without
 * authentication.</p>
 */
public class SsoCallbackHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Logger log = LoggerFactory.getLogger(SsoCallbackHandler.class);
    private static final ObjectMapper MAPPER = com.baafoo.core.util.JsonUtils.MAPPER;

    private static final String CALLBACK_PATH = "/sso/callback";
    private static final String COOKIE_NAME = "baafoo_token";
    private static final String HOME_REDIRECT = "/";
    private static final String PROJECT_CODE = "BAAFOO";
    private static final int COOKIE_MAX_AGE = 86400; // 24h, aligned with token expiration

    private final AuthService authService;
    private final ServerConfig config;
    private final HttpClient httpClient;

    public SsoCallbackHandler(AuthService authService, ServerConfig config) {
        this.authService = authService;
        this.config = config;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(5))
                .build();
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

        // Extract code from query string (NOT token — Authorization Code mode)
        String code = extractQueryParam(uri, "code");
        if (code == null || code.isEmpty()) {
            log.warn("SSO callback received without code parameter");
            sendRedirect(ctx, HOME_REDIRECT);
            return;
        }

        // Exchange code for token via server-to-server POST to Ehre
        String token;
        try {
            token = exchangeCodeForToken(code);
        } catch (Exception e) {
            log.error("SSO code exchange failed: {}", e.getMessage());
            sendRedirect(ctx, config.getAuth().getSso().getLoginUrl());
            return;
        }

        if (token == null || token.isEmpty()) {
            log.warn("SSO token exchange returned empty token");
            sendRedirect(ctx, config.getAuth().getSso().getLoginUrl());
            return;
        }

        // Validate the JWT token using AuthService
        AuthService.AuthResult authResult = authService.authenticate("Bearer " + token, null, "sso-callback");
        if (!authResult.isSuccess()) {
            log.warn("SSO callback token validation failed: {}", authResult.getMessage());
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
                + "; Max-Age=" + COOKIE_MAX_AGE;
        response.headers().set(HttpHeaderNames.SET_COOKIE, cookieValue);

        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    /**
     * Exchange auth code for JWT token via POST to Ehre /api/sso/token.
     */
    private String exchangeCodeForToken(String code) throws Exception {
        String ssoBaseUrl = config.getAuth().getSso().getBaseUrl();
        String tokenEndpoint = ssoBaseUrl + "/api/sso/token";

        String jsonBody = MAPPER.writeValueAsString(Map.of(
                "code", code,
                "projectCode", PROJECT_CODE
        ));

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(URI.create(tokenEndpoint))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .timeout(Duration.ofSeconds(10))
                .build();

        HttpResponse<String> resp = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());

        if (resp.statusCode() != 200) {
            log.warn("SSO token exchange returned status {}: {}", resp.statusCode(), resp.body());
            return null;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> body = MAPPER.readValue(resp.body(), Map.class);
        return (String) body.get("token");
    }

    private static String extractPath(String uri) {
        int queryIdx = uri.indexOf('?');
        return queryIdx >= 0 ? uri.substring(0, queryIdx) : uri;
    }

    private static String extractQueryParam(String uri, String paramName) {
        // Generic query param extraction (replaces the old token-only regex)
        String query = uri.contains("?") ? uri.substring(uri.indexOf('?') + 1) : "";
        for (String pair : query.split("&")) {
            String[] kv = pair.split("=", 2);
            if (kv.length == 2 && kv[0].equals(paramName)) {
                try {
                    return java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8.name());
                } catch (Exception e) {
                    return kv[1];
                }
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
