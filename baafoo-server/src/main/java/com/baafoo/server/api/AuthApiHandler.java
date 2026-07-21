package com.baafoo.server.api;

import com.baafoo.core.api.ApiResponse;
import com.baafoo.core.config.ServerConfig;
import com.baafoo.server.api.dto.AuthMeResponse;
import com.baafoo.server.api.dto.LoginResponse;
import com.baafoo.server.auth.AuthService;
import com.baafoo.server.storage.StorageService;

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

        // --- User self-service: change password ---
        if (path.equals("/__baafoo__/api/auth/me/password") && "PUT".equals(method)) {
            String username = ctx.auth.getUsername();
            if (username == null || username.isEmpty()) {
                return ApiResponse.fail(401, "Authentication required");
            }
            // Block when Ehre is the unified user management system.
            ServerConfig config = ctx.getConfig();
            if (config != null && config.getAuth() != null
                    && config.getAuth().getSso() != null
                    && config.getAuth().getSso().isUseEhreProfile()) {
                String ehreUrl = config.getAuth().getSso().getBaseUrl();
                return ApiResponse.fail(403,
                        "密码由统一管理系统维护，请前往 " + ehreUrl + " 修改密码");
            }
            Map<String, Object> reqBody = ctx.mapper.readValue(body, Map.class);
            String oldPassword = (String) reqBody.get("oldPassword");
            String newPassword = (String) reqBody.get("newPassword");
            if (oldPassword == null || newPassword == null || newPassword.isEmpty()) {
                return ApiResponse.fail(400, "oldPassword and newPassword are required");
            }
            AuthService.PasswordValidation pv = AuthService.validatePassword(newPassword);
            if (!pv.isValid()) {
                return ApiResponse.fail(400, pv.getMessage());
            }
            com.baafoo.core.model.User user = ctx.storage.getUserByUsername(username);
            if (user == null) {
                return ApiResponse.fail(404, "User not found");
            }
            if (!ctx.authService.verifyPassword(oldPassword, user.getPassword())) {
                return ApiResponse.fail(400, "Old password is incorrect");
            }
            String newHash = ctx.authService.hashPassword(newPassword);
            ctx.storage.updateUserPassword(username, newHash);
            return ApiResponse.ok("Password changed", null);
        }

        // --- User self-service: update profile ---
        if (path.equals("/__baafoo__/api/auth/me") && "PUT".equals(method)) {
            String username = ctx.auth.getUsername();
            if (username == null || username.isEmpty()) {
                return ApiResponse.fail(401, "Authentication required");
            }
            // Block when Ehre is the unified user management system.
            ServerConfig config = ctx.getConfig();
            if (config != null && config.getAuth() != null
                    && config.getAuth().getSso() != null
                    && config.getAuth().getSso().isUseEhreProfile()) {
                String ehreUrl = config.getAuth().getSso().getBaseUrl();
                return ApiResponse.fail(403,
                        "用户信息由统一管理系统维护，请前往 " + ehreUrl + " 修改个人信息");
            }
            Map<String, Object> reqBody = ctx.mapper.readValue(body, Map.class);
            String displayName = (String) reqBody.get("displayName");
            String email = (String) reqBody.get("email");
            String phone = (String) reqBody.get("phone");
            com.baafoo.core.model.User user = ctx.storage.getUserByUsername(username);
            if (user == null) {
                return ApiResponse.fail(404, "User not found");
            }
            if (displayName != null && !displayName.isEmpty()) {
                user.setDisplayName(displayName);
            }
            if (email != null) {
                user.setEmail(email);
            }
            if (phone != null) {
                user.setPhone(phone);
            }
            // Persist via storage service. Baafoo storage does not have a
            // dedicated updateProfile method, so we reuse updateUserPassword
            // pattern: direct SQL update would be ideal, but for now we
            // only support password changes (the most common self-service).
            // Profile update requires a storage method — return a friendly
            // message if the storage layer doesn't support it yet.
            return ApiResponse.fail(501, "Profile update not yet supported in Baafoo storage layer");
        }

        return null;
    }
}
