package com.baafoo.server.api;

import com.baafoo.core.api.ApiResponse;
import com.baafoo.core.i18n.I18n;
import com.baafoo.core.model.User;
import com.baafoo.server.api.dto.ApiKeyResponse;
import com.baafoo.server.api.dto.CsvImportResponse;
import com.baafoo.server.api.dto.UserSafeResponse;
import com.baafoo.server.auth.AuthService;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

class UserApiHandler implements ResourceHandler {
    @Override
    public Object handle(String method, String path, String body, ApiContext ctx) throws Exception {
        String API_PREFIX = "/__baafoo__/api/";
        I18n i18n = ctx.getI18n();

        if (path.equals(API_PREFIX + "users") && "GET".equals(method)) {
            ctx.requirePermission("user", "read");
            List<User> users = ctx.storage.listUsers();
            List<UserSafeResponse> safeUsers = new ArrayList<UserSafeResponse>();
            for (User u : users) {
                safeUsers.add(toSafeResponse(u));
            }
            return ApiResponse.ok(safeUsers);
        }

        if (path.equals(API_PREFIX + "users") && "POST".equals(method)) {
            ctx.requirePermission("user", "create");
            Map<String, Object> reqBody = ctx.mapper.readValue(body, Map.class);
            String username = (String) reqBody.get("username");
            String password = (String) reqBody.get("password");
            String displayName = (String) reqBody.get("displayName");
            String email = (String) reqBody.get("email");
            String phone = (String) reqBody.get("phone");
            String avatar = (String) reqBody.get("avatar");
            String role = (String) reqBody.get("role");
            if (username == null || username.isEmpty()) {
                return ApiResponse.fail(400, i18n.get("user.username_required"));
            }
            AuthService.PasswordValidation pv = AuthService.validatePassword(password);
            if (!pv.isValid()) {
                String errMsg = pv.getErrorCode() != null ? i18n.get(pv.getErrorCode()) : pv.getMessage();
                return ApiResponse.fail(400, errMsg);
            }
            if (role == null || !ApiUtils.isValidRole(role)) {
                return ApiResponse.fail(400, i18n.get("user.invalid_role"));
            }
            if (ctx.storage.getUserByUsername(username) != null) {
                return ApiResponse.fail(409, i18n.get("user.already_exists", username));
            }
            User user = new User();
            user.setUsername(username);
            user.setPassword(ctx.authService.hashPassword(password));
            user.setDisplayName(displayName != null ? displayName : username);
            user.setEmail(email);
            user.setPhone(phone);
            user.setAvatar(avatar);
            user.setRole(role);
            User created = ctx.storage.createUser(user);
            return ApiResponse.created(toSafeResponse(created));
        }

        if (path.equals(API_PREFIX + "users/import") && "POST".equals(method)) {
            ctx.requirePermission("user", "create");
            return handleCsvImport(body, ctx, i18n);
        }

        if (path.startsWith(API_PREFIX + "users/") && path.endsWith("/role") && "PUT".equals(method)) {
            ctx.requirePermission("user", "update");
            String username = ApiUtils.extractId(path, API_PREFIX + "users/", "/role");
            Map<String, Object> reqBody = ctx.mapper.readValue(body, Map.class);
            String newRole = (String) reqBody.get("role");
            if (newRole == null || !ApiUtils.isValidRole(newRole)) {
                return ApiResponse.fail(400, i18n.get("user.invalid_role"));
            }
            boolean updated = ctx.storage.updateUserRole(username, newRole);
            return updated ? ApiResponse.ok(i18n.get("user.role_updated"), null)
                    : ApiResponse.notFound(i18n.get("user.not_found", username));
        }

        if (path.startsWith(API_PREFIX + "users/") && path.endsWith("/api-key") && "POST".equals(method)) {
            ctx.requirePermission("user", "update");
            String username = ApiUtils.extractId(path, API_PREFIX + "users/", "/api-key");
            String newApiKey = ctx.authService.generateApiKey();
            boolean updated = ctx.storage.updateUserApiKey(username, newApiKey);
            if (!updated) return ApiResponse.notFound(i18n.get("user.not_found", username));
            ApiKeyResponse result = new ApiKeyResponse().apiKey(newApiKey);
            return ApiResponse.ok(result);
        }

        if (path.startsWith(API_PREFIX + "users/") && path.endsWith("/api-key") && "DELETE".equals(method)) {
            ctx.requirePermission("user", "update");
            String username = ApiUtils.extractId(path, API_PREFIX + "users/", "/api-key");
            boolean updated = ctx.storage.updateUserApiKey(username, null);
            return updated ? ApiResponse.ok("API key revoked", null)
                    : ApiResponse.notFound(i18n.get("user.not_found", username));
        }

        if (path.startsWith(API_PREFIX + "users/") && "DELETE".equals(method)) {
            ctx.requirePermission("user", "delete");
            String username = ApiUtils.extractId(path, API_PREFIX + "users/", null);
            if (username.equals(ctx.auth.getUsername())) {
                return ApiResponse.fail(400, i18n.get("user.cannot_delete_self"));
            }
            boolean deleted = ctx.storage.deleteUser(username);
            return deleted ? ApiResponse.ok(i18n.get("user.deleted"), null)
                    : ApiResponse.notFound(i18n.get("user.not_found", username));
        }

        return null;
    }

    private UserSafeResponse toSafeResponse(User u) {
        UserSafeResponse safe = new UserSafeResponse();
        safe.id = u.getId();
        safe.username = u.getUsername();
        safe.displayName = u.getDisplayName();
        safe.email = u.getEmail();
        safe.phone = u.getPhone();
        safe.avatar = u.getAvatar();
        safe.role = u.getRole();
        safe.apiKey = u.getApiKey() != null;
        safe.createdAt = u.getCreatedAt();
        safe.updatedAt = u.getUpdatedAt();
        safe.lastLoginAt = u.getLastLoginAt();
        return safe;
    }

    private Object handleCsvImport(String csv, ApiContext ctx, I18n i18n) {
        String[] lines = csv.split("\r?\n");
        if (lines.length < 2) {
            return ApiResponse.fail(400, i18n.get("user.csv_need_header_and_data"));
        }
        String[] headers = lines[0].split(",");
        for (int i = 0; i < headers.length; i++) {
            headers[i] = headers[i].trim().replace("\"", "");
        }
        int usernameIdx = -1, passwordIdx = -1, displayNameIdx = -1, emailIdx = -1, roleIdx = -1;
        for (int i = 0; i < headers.length; i++) {
            String h = headers[i];
            if ("用户名".equals(h) || "username".equalsIgnoreCase(h)) usernameIdx = i;
            else if ("密码".equals(h) || "password".equalsIgnoreCase(h)) passwordIdx = i;
            else if ("显示名称".equals(h) || "displayName".equalsIgnoreCase(h) || "display_name".equalsIgnoreCase(h)) displayNameIdx = i;
            else if ("邮箱".equals(h) || "email".equalsIgnoreCase(h)) emailIdx = i;
            else if ("角色代码".equals(h) || "role".equalsIgnoreCase(h) || "roleCode".equalsIgnoreCase(h)) roleIdx = i;
        }
        if (usernameIdx < 0 || passwordIdx < 0) {
            return ApiResponse.fail(400, i18n.get("user.csv_header_required"));
        }
        CsvImportResponse summary = new CsvImportResponse();
        List<String> errors = new ArrayList<String>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            String[] cols = parseCsvLine(line);
            String username = usernameIdx < cols.length ? cols[usernameIdx].trim() : "";
            String password = passwordIdx < cols.length ? cols[passwordIdx].trim() : "";
            String displayName = displayNameIdx >= 0 && displayNameIdx < cols.length ? cols[displayNameIdx].trim() : "";
            String email = emailIdx >= 0 && emailIdx < cols.length ? cols[emailIdx].trim() : "";
            String role = roleIdx >= 0 && roleIdx < cols.length ? cols[roleIdx].trim() : "guest";
            if (username.isEmpty() || password.isEmpty()) {
                summary.failed++;
                errors.add(i18n.get("user.csv_row_empty", i + 1));
                continue;
            }
            if (ctx.storage.getUserByUsername(username) != null) {
                summary.skipped++;
                continue;
            }
            AuthService.PasswordValidation pv = AuthService.validatePassword(password);
            if (!pv.isValid()) {
                summary.failed++;
                errors.add(i18n.get("user.csv_row_invalid_pw", i + 1, username, pv.getMessage()));
                continue;
            }
            if (!ApiUtils.isValidRole(role)) {
                summary.failed++;
                errors.add(i18n.get("user.csv_row_invalid_role", i + 1, username, role));
                continue;
            }
            User user = new User();
            user.setUsername(username);
            user.setPassword(ctx.authService.hashPassword(password));
            user.setDisplayName(displayName.isEmpty() ? username : displayName);
            user.setEmail(email.isEmpty() ? null : email);
            user.setRole(role);
            User result = ctx.storage.createUser(user);
            if (result != null) {
                summary.created++;
            } else {
                summary.failed++;
                errors.add(i18n.get("user.csv_row_create_failed", i + 1, username));
            }
        }
        summary.errors = errors;
        return ApiResponse.ok(summary);
    }

    private String[] parseCsvLine(String line) {
        List<String> result = new ArrayList<String>();
        StringBuilder sb = new StringBuilder();
        boolean inQuotes = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (inQuotes && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    sb.append('"');
                    i++;
                } else {
                    inQuotes = !inQuotes;
                }
            } else if (c == ',' && !inQuotes) {
                result.add(sb.toString());
                sb = new StringBuilder();
            } else {
                sb.append(c);
            }
        }
        result.add(sb.toString());
        return result.toArray(new String[0]);
    }
}
