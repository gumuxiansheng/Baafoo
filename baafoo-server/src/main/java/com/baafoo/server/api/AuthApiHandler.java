package com.baafoo.server.api;

import com.baafoo.core.api.ApiResponse;
import com.baafoo.server.api.dto.AuthMeResponse;
import com.baafoo.server.api.dto.LoginResponse;
import com.baafoo.server.auth.AuthService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

class AuthApiHandler implements ResourceHandler {
    @Override
    public Object handle(String method, String path, String body, ApiContext ctx) throws Exception {
        if (path.equals("/__baafoo__/api/auth/me") && "GET".equals(method)) {
            List<String> permissions = new ArrayList<String>();
            permissions.add("read:all");
            if (AuthService.hasPermission(ctx.auth.getRole(), "rule", "create")) permissions.add("write:rule");
            if (AuthService.hasPermission(ctx.auth.getRole(), "scene", "create")) permissions.add("write:scene");
            if (AuthService.hasPermission(ctx.auth.getRole(), "environment", "create")) permissions.add("write:environment");
            if (AuthService.hasPermission(ctx.auth.getRole(), "recording", "delete")) permissions.add("write:recording");
            if (AuthService.hasPermission(ctx.auth.getRole(), "user", "create")) permissions.add("write:user");
            AuthMeResponse me = new AuthMeResponse()
                    .authenticated(true)
                    .role(ctx.auth.getRole())
                    .username(ctx.auth.getUsername())
                    .authMethod(ctx.auth.getMessage())
                    .permissions(permissions);
            return ApiResponse.ok(me);
        }

        if (path.equals("/__baafoo__/api/auth/login") && "POST".equals(method)) {
            Map<String, Object> reqBody = ctx.mapper.readValue(body, Map.class);
            String username = (String) reqBody.get("username");
            String password = (String) reqBody.get("password");
            Object expiresInObj = reqBody.get("expiresIn");
            Long expiresInMs = null;
            if (expiresInObj instanceof Number) {
                expiresInMs = ((Number) expiresInObj).longValue() * 1000;
            }
            if (username == null || username.isEmpty() || password == null || password.isEmpty()) {
                return ApiResponse.fail(400, "Username and password are required");
            }
            AuthService.LoginResult loginResult = ctx.authService.login(username, password, expiresInMs);
            if (!loginResult.isSuccess()) {
                return ApiResponse.fail(401, loginResult.getMessage());
            }
            LoginResponse result = new LoginResponse()
                    .token(loginResult.getToken())
                    .role(loginResult.getRole());
            return ApiResponse.ok(result);
        }

        return null;
    }
}
