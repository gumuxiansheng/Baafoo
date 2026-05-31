package com.baafoo.server.api;

import com.baafoo.core.api.ApiResponse;
import com.baafoo.core.model.*;
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
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ManagementApiHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Logger log = LoggerFactory.getLogger(ManagementApiHandler.class);

    private static final String API_PREFIX = "/__baafoo__/api/";

    private final StorageService storage;
    private final AuthService authService;
    private final ObjectMapper mapper;

    private String currentUri;

    public ManagementApiHandler(StorageService storage, AuthService authService) {
        this.storage = storage;
        this.authService = authService;
        this.mapper = new ObjectMapper();
    }

    private List<String> getInheritedEnvironments(String ruleId) {
        List<String> inherited = new ArrayList<String>();
        for (SceneSet scene : storage.listScenes()) {
            if (!scene.isActive()) continue;
            List<String> items = scene.getItemIds();
            if (items == null || !items.contains(ruleId)) continue;
            List<String> envs = scene.getEnvironments();
            if (envs != null) {
                for (String env : envs) {
                    if (!inherited.contains(env)) {
                        inherited.add(env);
                    }
                }
            }
        }
        return inherited;
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        String uri = request.uri();
        String method = request.method().name();
        currentUri = uri;

        try {
            String path = extractPath(uri);

            if (path.startsWith(API_PREFIX)) {
                Object result = handleApiRequest(path, method, request, ctx);
                sendJson(ctx, 200, result);
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

    private void requirePermission(String role, String resource, String action) {
        if (!AuthService.hasPermission(role, resource, action)) {
            throw new ApiException(403, "permission_denied",
                    getRequiredRoleForAction(resource, action), role);
        }
    }

    private String getRequiredRoleForAction(String resource, String action) {
        if ("rule".equals(resource)) return "developer";
        if ("scene".equals(resource)) return "tester";
        if ("environment".equals(resource)) return "admin";
        if ("recording".equals(resource)) return "tester";
        if ("user".equals(resource)) return "admin";
        return "admin";
    }

    private Object handleApiRequest(String path, String method, FullHttpRequest request, ChannelHandlerContext ctx) throws Exception {
        if ("OPTIONS".equals(method)) {
            return ApiResponse.ok("OK", null);
        }

        String remoteAddr = resolveRemoteAddr(ctx, request);
        AuthService.AuthResult auth = authenticate(request, remoteAddr);

        // --- Auth endpoints (no permission check needed) ---
        if (path.equals(API_PREFIX + "auth/me") && "GET".equals(method)) {
            Map<String, Object> me = new HashMap<String, Object>();
            me.put("authenticated", true);
            me.put("role", auth.role);
            me.put("username", auth.username);
            me.put("authMethod", auth.message);
            List<String> permissions = new ArrayList<String>();
            permissions.add("read:all");
            if (AuthService.hasPermission(auth.role, "rule", "create")) permissions.add("write:rule");
            if (AuthService.hasPermission(auth.role, "scene", "create")) permissions.add("write:scene");
            if (AuthService.hasPermission(auth.role, "environment", "create")) permissions.add("write:environment");
            if (AuthService.hasPermission(auth.role, "recording", "delete")) permissions.add("write:recording");
            if (AuthService.hasPermission(auth.role, "user", "create")) permissions.add("write:user");
            me.put("permissions", permissions);
            return ApiResponse.ok(me);
        }

        if (path.equals(API_PREFIX + "auth/login") && "POST".equals(method)) {
            Map<String, Object> body = mapper.readValue(
                    request.content().toString(StandardCharsets.UTF_8), Map.class);
            String username = (String) body.get("username");
            String password = (String) body.get("password");
            Object expiresInObj = body.get("expiresIn");
            Long expiresInMs = null;
            if (expiresInObj instanceof Number) {
                expiresInMs = ((Number) expiresInObj).longValue() * 1000;
            }
            if (username == null || username.isEmpty() || password == null || password.isEmpty()) {
                return ApiResponse.fail(400, "Username and password are required");
            }
            AuthService.LoginResult loginResult = authService.login(username, password, expiresInMs);
            if (!loginResult.success) {
                return ApiResponse.fail(401, loginResult.message);
            }
            Map<String, Object> result = new HashMap<String, Object>();
            result.put("token", loginResult.token);
            result.put("role", loginResult.role);
            return ApiResponse.ok(result);
        }

        // --- Permission check for all other endpoints ---
        if (!auth.success) {
            throw new ApiException(401, "Authentication failed: " + auth.message);
        }

        // --- User management endpoints (admin only) ---
        if (path.equals(API_PREFIX + "users") && "GET".equals(method)) {
            requirePermission(auth.role, "user", "read");
            List<User> users = storage.listUsers();
            List<Map<String, Object>> safeUsers = new ArrayList<Map<String, Object>>();
            for (User u : users) {
                Map<String, Object> safe = new HashMap<String, Object>();
                safe.put("id", u.getId());
                safe.put("username", u.getUsername());
                safe.put("displayName", u.getDisplayName());
                safe.put("email", u.getEmail());
                safe.put("role", u.getRole());
                safe.put("apiKey", u.getApiKey() != null);
                safe.put("createdAt", u.getCreatedAt());
                safe.put("updatedAt", u.getUpdatedAt());
                safe.put("lastLoginAt", u.getLastLoginAt());
                safeUsers.add(safe);
            }
            return ApiResponse.ok(safeUsers);
        }

        if (path.equals(API_PREFIX + "users") && "POST".equals(method)) {
            requirePermission(auth.role, "user", "create");
            Map<String, Object> body = mapper.readValue(
                    request.content().toString(StandardCharsets.UTF_8), Map.class);
            String username = (String) body.get("username");
            String password = (String) body.get("password");
            String displayName = (String) body.get("displayName");
            String email = (String) body.get("email");
            String role = (String) body.get("role");
            if (username == null || username.isEmpty()) {
                return ApiResponse.fail(400, "Username is required");
            }
            AuthService.PasswordValidation pv = AuthService.validatePassword(password);
            if (!pv.valid) {
                return ApiResponse.fail(400, pv.message);
            }
            if (role == null || !isValidRole(role)) {
                return ApiResponse.fail(400, "Invalid role. Must be one of: admin, developer, tester, guest");
            }
            if (storage.getUserByUsername(username) != null) {
                return ApiResponse.fail(409, "User already exists: " + username);
            }
            User user = new User();
            user.setUsername(username);
            user.setPasswordHash(authService.hashPassword(password));
            user.setDisplayName(displayName != null ? displayName : username);
            user.setEmail(email);
            user.setRole(role);
            User created = storage.createUser(user);
            Map<String, Object> safe = new HashMap<String, Object>();
            safe.put("id", created.getId());
            safe.put("username", created.getUsername());
            safe.put("displayName", created.getDisplayName());
            safe.put("email", created.getEmail());
            safe.put("role", created.getRole());
            return ApiResponse.created(safe);
        }

        if (path.equals(API_PREFIX + "users/import") && "POST".equals(method)) {
            requirePermission(auth.role, "user", "create");
            String csv = request.content().toString(StandardCharsets.UTF_8);
            return handleCsvImport(csv);
        }

        if (path.startsWith(API_PREFIX + "users/") && path.endsWith("/role") && "PUT".equals(method)) {
            requirePermission(auth.role, "user", "update");
            String username = extractId(path, API_PREFIX + "users/", "/role");
            Map<String, Object> body = mapper.readValue(
                    request.content().toString(StandardCharsets.UTF_8), Map.class);
            String newRole = (String) body.get("role");
            if (newRole == null || !isValidRole(newRole)) {
                return ApiResponse.fail(400, "Invalid role. Must be one of: admin, developer, tester, guest");
            }
            boolean updated = storage.updateUserRole(username, newRole);
            return updated ? ApiResponse.ok("Role updated", null) : ApiResponse.notFound("User not found");
        }

        if (path.startsWith(API_PREFIX + "users/") && path.endsWith("/api-key") && "POST".equals(method)) {
            requirePermission(auth.role, "user", "update");
            String username = extractId(path, API_PREFIX + "users/", "/api-key");
            String newApiKey = authService.generateApiKey();
            boolean updated = storage.updateUserApiKey(username, newApiKey);
            if (!updated) return ApiResponse.notFound("User not found");
            Map<String, Object> result = new HashMap<String, Object>();
            result.put("apiKey", newApiKey);
            return ApiResponse.ok(result);
        }

        if (path.startsWith(API_PREFIX + "users/") && path.endsWith("/api-key") && "DELETE".equals(method)) {
            requirePermission(auth.role, "user", "update");
            String username = extractId(path, API_PREFIX + "users/", "/api-key");
            boolean updated = storage.updateUserApiKey(username, null);
            return updated ? ApiResponse.ok("API key revoked", null) : ApiResponse.notFound("User not found");
        }

        if (path.startsWith(API_PREFIX + "users/") && "DELETE".equals(method)) {
            requirePermission(auth.role, "user", "delete");
            String username = extractId(path, API_PREFIX + "users/", null);
            if (username.equals(auth.username)) {
                return ApiResponse.fail(400, "Cannot delete yourself");
            }
            boolean deleted = storage.deleteUser(username);
            return deleted ? ApiResponse.ok("Deleted", null) : ApiResponse.notFound("User not found");
        }

        // --- Rules ---
        if (path.equals(API_PREFIX + "rules")) {
            if ("GET".equals(method)) {
                return ApiResponse.ok(storage.listRules());
            }
            if ("POST".equals(method)) {
                requirePermission(auth.role, "rule", "create");
                Rule rule = mapper.readValue(request.content().toString(StandardCharsets.UTF_8), Rule.class);
                return ApiResponse.created(storage.createRule(rule));
            }
        }

        if (path.startsWith(API_PREFIX + "rules/") && path.contains("/undo")) {
            String id = extractId(path, API_PREFIX + "rules/", "/undo");
            requirePermission(auth.role, "rule", "update");
            boolean success = storage.undoRule(id);
            return success ? ApiResponse.ok("Undo successful", null)
                    : ApiResponse.notFound("Rule not found or no undo history");
        }

        if (path.startsWith(API_PREFIX + "rules/") && path.contains("/inherited-environments")) {
            String id = extractId(path, API_PREFIX + "rules/", "/inherited-environments");
            List<String> inherited = getInheritedEnvironments(id);
            return ApiResponse.ok(inherited);
        }

        if (path.startsWith(API_PREFIX + "rules/")) {
            String id = extractId(path, API_PREFIX + "rules/", null);
            if ("GET".equals(method)) {
                Rule rule = storage.getRule(id);
                return rule != null ? ApiResponse.ok(rule) : ApiResponse.notFound("Rule not found");
            }
            if ("PUT".equals(method)) {
                requirePermission(auth.role, "rule", "update");
                Rule update = mapper.readValue(request.content().toString(StandardCharsets.UTF_8), Rule.class);
                Rule existing = storage.getRule(id);
                if (existing == null) return ApiResponse.notFound("Rule not found");
                List<String> inheritedEnvs = getInheritedEnvironments(id);
                List<String> requestedEnvs = update.getEnvironments() != null ? update.getEnvironments() : new ArrayList<String>();
                List<String> mergedEnvs = new ArrayList<String>(requestedEnvs);
                for (String inherited : inheritedEnvs) {
                    if (!mergedEnvs.contains(inherited)) {
                        mergedEnvs.add(inherited);
                    }
                }
                update.setEnvironments(mergedEnvs);
                Rule updated = storage.updateRule(id, update);
                return updated != null ? ApiResponse.ok(updated) : ApiResponse.notFound("Rule not found");
            }
            if ("DELETE".equals(method)) {
                requirePermission(auth.role, "rule", "delete");
                boolean deleted = storage.deleteRule(id);
                return deleted ? ApiResponse.ok("Deleted", null) : ApiResponse.notFound("Rule not found");
            }
        }

        // --- Rule Sets ---
        if (path.equals(API_PREFIX + "rulesets")) {
            if ("GET".equals(method)) return ApiResponse.ok(storage.listRuleSets());
            if ("POST".equals(method)) {
                requirePermission(auth.role, "rule", "create");
                RuleSet set = mapper.readValue(request.content().toString(StandardCharsets.UTF_8), RuleSet.class);
                return ApiResponse.created(storage.createRuleSet(set));
            }
        }

        // --- Environments ---
        if (path.equals(API_PREFIX + "environments")) {
            if ("GET".equals(method)) return ApiResponse.ok(storage.listEnvironments());
            if ("POST".equals(method)) {
                requirePermission(auth.role, "environment", "create");
                Environment env = mapper.readValue(request.content().toString(StandardCharsets.UTF_8), Environment.class);
                return ApiResponse.created(storage.createEnvironment(env));
            }
        }

        if (path.startsWith(API_PREFIX + "environments/")) {
            String id;
            boolean isRulesSubPath = path.endsWith("/rules");
            if (isRulesSubPath) {
                id = extractId(path, API_PREFIX + "environments/", "/rules");
            } else {
                id = extractId(path, API_PREFIX + "environments/", null);
            }
            log.debug("Environment route: path={} id={} isRulesSubPath={} method={}", path, id, isRulesSubPath, method);

            if (isRulesSubPath && "POST".equals(method)) {
                requirePermission(auth.role, "environment", "associate");
                Map<String, Object> body = mapper.readValue(
                        request.content().toString(StandardCharsets.UTF_8), Map.class);
                @SuppressWarnings("unchecked")
                List<String> ruleIds = (List<String>) body.get("ruleIds");
                Environment env = storage.getEnvironment(id);
                if (env == null) return ApiResponse.notFound("Environment not found");
                storage.associateRulesToEnvironment(env.getName(), ruleIds != null ? ruleIds : new ArrayList<String>());
                return ApiResponse.ok("Associated " + (ruleIds != null ? ruleIds.size() : 0) + " rules", null);
            }

            if (isRulesSubPath && "DELETE".equals(method)) {
                requirePermission(auth.role, "environment", "associate");
                Map<String, Object> body = mapper.readValue(
                        request.content().toString(StandardCharsets.UTF_8), Map.class);
                @SuppressWarnings("unchecked")
                List<String> ruleIds = (List<String>) body.get("ruleIds");
                Environment env = storage.getEnvironment(id);
                if (env == null) return ApiResponse.notFound("Environment not found");
                storage.dissociateRulesFromEnvironment(env.getName(), ruleIds != null ? ruleIds : new ArrayList<String>());
                return ApiResponse.ok("Dissociated " + (ruleIds != null ? ruleIds.size() : 0) + " rules", null);
            }

            if ("GET".equals(method)) {
                Environment env = storage.getEnvironment(id);
                return env != null ? ApiResponse.ok(env) : ApiResponse.notFound("Environment not found");
            }
            if ("PUT".equals(method)) {
                requirePermission(auth.role, "environment", "update");
                Environment update = mapper.readValue(request.content().toString(StandardCharsets.UTF_8), Environment.class);
                Environment updated = storage.updateEnvironment(id, update);
                return updated != null ? ApiResponse.ok(updated) : ApiResponse.notFound("Environment not found");
            }
            if ("DELETE".equals(method)) {
                requirePermission(auth.role, "environment", "delete");
                boolean deleted = storage.deleteEnvironment(id);
                return deleted ? ApiResponse.ok("Deleted", null) : ApiResponse.notFound("Environment not found");
            }
        }

        // --- Agent ---
        if (path.equals(API_PREFIX + "agent/register") && "POST".equals(method)) {
            Map<String, Object> body = mapper.readValue(
                    request.content().toString(StandardCharsets.UTF_8), Map.class);
            String agentId = (String) body.getOrDefault("agentId", "");
            String env = (String) body.getOrDefault("environment", "default");
            String hostname = (String) body.getOrDefault("hostname", "unknown");
            String version = (String) body.getOrDefault("version", "1.0.0");
            @SuppressWarnings("unchecked")
            List<String> protocols = (List<String>) body.getOrDefault("protocols", new ArrayList<String>());

            StorageService.AgentRegistration reg = storage.registerAgent(agentId, env, hostname, version, protocols);

            Environment environment = storage.getEnvironmentByName(env);
            String mode = environment != null ? environment.getMode().getValue() : "record-and-stub";

            Map<String, Object> result = new HashMap<String, Object>();
            result.put("agentId", reg.agentId);
            result.put("mode", mode);
            result.put("pollIntervalSec", 10);
            return ApiResponse.ok(result);
        }

        if (path.equals(API_PREFIX + "agent/heartbeat") && "POST".equals(method)) {
            Map<String, Object> body = mapper.readValue(
                    request.content().toString(StandardCharsets.UTF_8), Map.class);
            String agentId = (String) body.get("agentId");
            storage.agentHeartbeat(agentId);
            return ApiResponse.ok("OK", null);
        }

        if (path.equals(API_PREFIX + "agent/poll") && "GET".equals(method)) {
            String agentId = parseQueryParam(currentUri, "agentId");
            List<Rule> rules = storage.listRules();

            String mode = "record-and-stub";
            for (StorageService.AgentRegistration reg : storage.listAgents()) {
                if (reg.agentId != null && reg.agentId.equals(agentId)) {
                    Environment env = storage.getEnvironmentByName(reg.environment);
                    if (env != null) mode = env.getMode().getValue();
                    break;
                }
            }

            Map<String, Object> result = new HashMap<String, Object>();
            result.put("rules", rules);
            result.put("mode", mode);
            result.put("version", System.currentTimeMillis());
            return ApiResponse.ok(result);
        }

        if (path.equals(API_PREFIX + "agent/recordings") && "POST".equals(method)) {
            requirePermission(auth.role, "recording", "create");
            List<RecordingEntry> batch = mapper.readValue(
                    request.content().toString(StandardCharsets.UTF_8),
                    mapper.getTypeFactory().constructCollectionType(List.class, RecordingEntry.class));
            storage.addRecordings(batch);
            return ApiResponse.ok("Recorded " + batch.size(), null);
        }

        // --- Recordings ---
        if (path.equals(API_PREFIX + "recordings") && "GET".equals(method)) {
            String ruleId = parseQueryParam(currentUri, "ruleId");
            int limit = parseIntParam(currentUri, "limit", 100);
            return ApiResponse.ok(storage.listRecordings(ruleId, limit));
        }

        if (path.startsWith(API_PREFIX + "recordings/") && "DELETE".equals(method)) {
            requirePermission(auth.role, "recording", "delete");
            String id = extractId(path, API_PREFIX + "recordings/", null);
            boolean deleted = storage.deleteRecording(id);
            return deleted ? ApiResponse.ok("Deleted", null) : ApiResponse.notFound("Recording not found");
        }

        // --- Scene Sets ---
        if (path.equals(API_PREFIX + "scenes")) {
            if ("GET".equals(method)) return ApiResponse.ok(storage.listScenes());
            if ("POST".equals(method)) {
                requirePermission(auth.role, "scene", "create");
                SceneSet scene = mapper.readValue(request.content().toString(StandardCharsets.UTF_8), SceneSet.class);
                return ApiResponse.created(storage.createScene(scene));
            }
        }

        if (path.startsWith(API_PREFIX + "scenes/")) {
            String id = extractId(path, API_PREFIX + "scenes/", null);
            if ("GET".equals(method)) {
                SceneSet scene = storage.listScenes().stream()
                        .filter(s -> s.getId().equals(id)).findFirst().orElse(null);
                return scene != null ? ApiResponse.ok(scene) : ApiResponse.notFound("Scene set not found");
            }
            if ("PUT".equals(method)) {
                requirePermission(auth.role, "scene", "update");
                SceneSet update = mapper.readValue(request.content().toString(StandardCharsets.UTF_8), SceneSet.class);
                SceneSet updated = storage.updateScene(id, update);
                return updated != null ? ApiResponse.ok(updated) : ApiResponse.notFound("Scene not found");
            }
            if ("DELETE".equals(method)) {
                requirePermission(auth.role, "scene", "delete");
                boolean deleted = storage.deleteScene(id);
                return deleted ? ApiResponse.ok("Deleted", null) : ApiResponse.notFound("Scene not found");
            }
        }

        // --- Agents ---
        if (path.equals(API_PREFIX + "agents") && "GET".equals(method)) {
            return ApiResponse.ok(storage.listAgents());
        }

        // --- System Status ---
        if (path.equals(API_PREFIX + "status") && "GET".equals(method)) {
            List<StorageService.AgentRegistration> allAgents = storage.listAgents();
            long onlineThreshold = System.currentTimeMillis() - 60000;
            long onlineCount = 0;
            for (StorageService.AgentRegistration agent : allAgents) {
                if (agent.lastHeartbeat > onlineThreshold) {
                    onlineCount++;
                }
            }

            Map<String, Object> status = new HashMap<String, Object>();
            status.put("version", "1.0.0-SNAPSHOT");
            status.put("rules", storage.listRules().size());
            status.put("environments", storage.listEnvironments().size());
            status.put("agents", allAgents.size());
            status.put("onlineAgents", onlineCount);
            status.put("scenes", storage.listScenes().size());
            status.put("uptime", System.currentTimeMillis());
            status.put("authEnabled", authService.isAuthEnabled());
            return ApiResponse.ok(status);
        }

        throw new ApiException(404, "API endpoint not found: " + method + " " + path);
    }

    private boolean isValidRole(String role) {
        return "admin".equals(role) || "developer".equals(role) || "tester".equals(role) || "guest".equals(role);
    }

    private Object handleCsvImport(String csv) {
        String[] lines = csv.split("\r?\n");
        if (lines.length < 2) {
            return ApiResponse.fail(400, "CSV文件至少需要包含标题行和一行数据");
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
            return ApiResponse.fail(400, "CSV必须包含\"用户名\"和\"密码\"列");
        }
        int created = 0, skipped = 0, failed = 0;
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
                failed++;
                errors.add("第" + (i + 1) + "行: 用户名或密码为空");
                continue;
            }
            if (storage.getUserByUsername(username) != null) {
                skipped++;
                continue;
            }
            AuthService.PasswordValidation pv = AuthService.validatePassword(password);
            if (!pv.valid) {
                failed++;
                errors.add("第" + (i + 1) + "行(" + username + "): " + pv.message);
                continue;
            }
            if (!isValidRole(role)) {
                failed++;
                errors.add("第" + (i + 1) + "行(" + username + "): 无效角色代码 '" + role + "'");
                continue;
            }
            User user = new User();
            user.setUsername(username);
            user.setPasswordHash(authService.hashPassword(password));
            user.setDisplayName(displayName.isEmpty() ? username : displayName);
            user.setEmail(email.isEmpty() ? null : email);
            user.setRole(role);
            User result = storage.createUser(user);
            if (result != null) {
                created++;
            } else {
                failed++;
                errors.add("第" + (i + 1) + "行(" + username + "): 创建失败");
            }
        }
        Map<String, Object> summary = new HashMap<String, Object>();
        summary.put("created", created);
        summary.put("skipped", skipped);
        summary.put("failed", failed);
        summary.put("errors", errors);
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
            log.error("Error serializing response: {}", e.getMessage());
            ctx.close();
        }
    }

    private String extractPath(String uri) {
        int queryIdx = uri.indexOf('?');
        return queryIdx >= 0 ? uri.substring(0, queryIdx) : uri;
    }

    private String extractId(String path, String prefix, String suffix) {
        String idSection = suffix != null
                ? path.substring(prefix.length(), path.length() - suffix.length())
                : path.substring(prefix.length());
        return idSection;
    }

    private String parseQueryParam(String uri, String key) {
        int queryIdx = uri.indexOf('?');
        if (queryIdx < 0) return null;
        String query = uri.substring(queryIdx + 1);
        for (String pair : query.split("&")) {
            int eqIdx = pair.indexOf('=');
            if (eqIdx > 0 && pair.substring(0, eqIdx).equals(key)) {
                return pair.substring(eqIdx + 1);
            }
        }
        return null;
    }

    private int parseIntParam(String uri, String key, int defaultValue) {
        String val = parseQueryParam(uri, key);
        if (val == null) return defaultValue;
        try {
            return Integer.parseInt(val);
        } catch (NumberFormatException e) {
            return defaultValue;
        }
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
