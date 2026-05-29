package com.baafoo.server.api;

import com.baafoo.core.api.ApiResponse;
import com.baafoo.core.model.*;
import com.baafoo.server.storage.FileStorage;
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
import java.util.List;
import java.util.Map;

/**
 * HTTP Management API handler.
 *
 * <p>Routes (all prefixed with /__baafoo__/api/):
 * <ul>
 *   <li>GET/POST /__baafoo__/api/rules — List/Create rules</li>
 *   <li>GET/PUT/DELETE /__baafoo__/api/rules/{id} — Get/Update/Delete rule</li>
 *   <li>POST /__baafoo__/api/rules/{id}/undo — Undo rule version</li>
 *   <li>GET/POST /__baafoo__/api/environments — List/Create environments</li>
 *   <li>GET/PUT/DELETE /__baafoo__/api/environments/{id} — Manage environment</li>
 *   <li>POST /__baafoo__/api/agent/register — Agent registration</li>
 *   <li>POST /__baafoo__/api/agent/heartbeat — Agent heartbeat</li>
 *   <li>GET  /__baafoo__/api/agent/poll — Agent long-poll</li>
 *   <li>POST /__baafoo__/api/agent/recordings — Recording upload</li>
 *   <li>GET  /__baafoo__/api/recordings — List recordings</li>
 *   <li>GET  /__baafoo__/api/scenes — List scene sets</li>
 *   <li>POST /__baafoo__/api/scenes — Create scene set</li>
 *   <li>GET  /__baafoo__/api/rulesets — List rule sets</li>
 *   <li>GET  /__baafoo__/api/status — System status</li>
 * </ul></p>
 *
 * <p>Path prefix: /__baafoo__/api/ (non-API paths are delegated to StaticFileHandler)</p>
 */
public class ManagementApiHandler extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Logger log = LoggerFactory.getLogger(ManagementApiHandler.class);

    /** All API routes use this prefix */
    private static final String API_PREFIX = "/__baafoo__/api/";

    private final FileStorage storage;
    private final ObjectMapper mapper;

    /** Stores the original URI for query parameter parsing */
    private String currentUri;

    public ManagementApiHandler(FileStorage storage) {
        this.storage = storage;
        this.mapper = new ObjectMapper();
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        String uri = request.uri();
        String method = request.method().name();
        currentUri = uri;

        try {
            // Route to handler
            String path = extractPath(uri);

            if (path.startsWith(API_PREFIX)) {
                Object result = handleApiRequest(path, method, request);
                sendJson(ctx, 200, result);
            } else {
                // Pass through to next handler (StaticFileHandler)
                ctx.fireChannelRead(request.retain());
            }
        } catch (ApiException e) {
            sendJson(ctx, e.getStatusCode(), ApiResponse.fail(e.getStatusCode(), e.getMessage()));
        } catch (Exception e) {
            log.error("API error: {}", e.getMessage(), e);
            sendJson(ctx, 500, ApiResponse.internalError(e.getMessage()));
        }
    }

    private Object handleApiRequest(String path, String method, FullHttpRequest request) throws Exception {
        // --- Rules ---
        if (path.equals(API_PREFIX + "rules")) {
            if ("GET".equals(method)) {
                return ApiResponse.ok(storage.listRules());
            }
            if ("POST".equals(method)) {
                Rule rule = mapper.readValue(request.content().toString(StandardCharsets.UTF_8), Rule.class);
                return ApiResponse.created(storage.createRule(rule));
            }
        }

        if (path.startsWith(API_PREFIX + "rules/") && path.contains("/undo")) {
            String id = extractId(path, API_PREFIX + "rules/", "/undo");
            boolean success = storage.undoRule(id);
            return success ? ApiResponse.ok("Undo successful", null)
                    : ApiResponse.notFound("Rule not found or no undo history");
        }

        if (path.startsWith(API_PREFIX + "rules/")) {
            String id = extractId(path, API_PREFIX + "rules/", null);
            if ("GET".equals(method)) {
                Rule rule = storage.getRule(id);
                return rule != null ? ApiResponse.ok(rule) : ApiResponse.notFound("Rule not found");
            }
            if ("PUT".equals(method)) {
                Rule update = mapper.readValue(request.content().toString(StandardCharsets.UTF_8), Rule.class);
                Rule updated = storage.updateRule(id, update);
                return updated != null ? ApiResponse.ok(updated) : ApiResponse.notFound("Rule not found");
            }
            if ("DELETE".equals(method)) {
                boolean deleted = storage.deleteRule(id);
                return deleted ? ApiResponse.ok("Deleted", null) : ApiResponse.notFound("Rule not found");
            }
        }

        // --- Rule Sets ---
        if (path.equals(API_PREFIX + "rulesets")) {
            if ("GET".equals(method)) return ApiResponse.ok(storage.listRuleSets());
            if ("POST".equals(method)) {
                RuleSet set = mapper.readValue(request.content().toString(StandardCharsets.UTF_8), RuleSet.class);
                return ApiResponse.created(storage.createRuleSet(set));
            }
        }

        // --- Environments ---
        if (path.equals(API_PREFIX + "environments")) {
            if ("GET".equals(method)) return ApiResponse.ok(storage.listEnvironments());
            if ("POST".equals(method)) {
                Environment env = mapper.readValue(request.content().toString(StandardCharsets.UTF_8), Environment.class);
                return ApiResponse.created(storage.createEnvironment(env));
            }
        }

        if (path.startsWith(API_PREFIX + "environments/")) {
            String id = extractId(path, API_PREFIX + "environments/", null);
            if ("GET".equals(method)) {
                Environment env = storage.getEnvironment(id);
                return env != null ? ApiResponse.ok(env) : ApiResponse.notFound("Environment not found");
            }
            if ("PUT".equals(method)) {
                Environment update = mapper.readValue(request.content().toString(StandardCharsets.UTF_8), Environment.class);
                Environment updated = storage.updateEnvironment(id, update);
                return updated != null ? ApiResponse.ok(updated) : ApiResponse.notFound("Environment not found");
            }
            if ("DELETE".equals(method)) {
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

            FileStorage.AgentRegistration reg = storage.registerAgent(agentId, env, hostname, version, protocols);

            Environment environment = storage.getEnvironmentByName(env);
            String mode = environment != null ? environment.getMode().getValue() : "stub";

            java.util.Map<String, Object> result = new java.util.HashMap<String, Object>();
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

            // Get environment mode for this agent
            String mode = "stub";
            for (FileStorage.AgentRegistration reg : storage.listAgents()) {
                if (reg.agentId != null && reg.agentId.equals(agentId)) {
                    Environment env = storage.getEnvironmentByName(reg.environment);
                    if (env != null) mode = env.getMode().getValue();
                    break;
                }
            }

            java.util.Map<String, Object> result = new java.util.HashMap<String, Object>();
            result.put("rules", rules);
            result.put("mode", mode);
            result.put("version", System.currentTimeMillis());
            return ApiResponse.ok(result);
        }

        if (path.equals(API_PREFIX + "agent/recordings") && "POST".equals(method)) {
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
            String id = extractId(path, API_PREFIX + "recordings/", null);
            boolean deleted = storage.deleteRecording(id);
            return deleted ? ApiResponse.ok("Deleted", null) : ApiResponse.notFound("Recording not found");
        }

        // --- Scene Sets ---
        if (path.equals(API_PREFIX + "scenes")) {
            if ("GET".equals(method)) return ApiResponse.ok(storage.listScenes());
            if ("POST".equals(method)) {
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
                SceneSet update = mapper.readValue(request.content().toString(StandardCharsets.UTF_8), SceneSet.class);
                SceneSet updated = storage.updateScene(id, update);
                return updated != null ? ApiResponse.ok(updated) : ApiResponse.notFound("Scene not found");
            }
            if ("DELETE".equals(method)) {
                boolean deleted = storage.deleteScene(id);
                return deleted ? ApiResponse.ok("Deleted", null) : ApiResponse.notFound("Scene not found");
            }
        }

        // --- System Status ---
        if (path.equals(API_PREFIX + "status") && "GET".equals(method)) {
            java.util.Map<String, Object> status = new java.util.HashMap<String, Object>();
            status.put("version", "1.0.0-SNAPSHOT");
            status.put("rules", storage.listRules().size());
            status.put("environments", storage.listEnvironments().size());
            status.put("agents", storage.listAgents().size());
            status.put("scenes", storage.listScenes().size());
            status.put("uptime", System.currentTimeMillis());
            return ApiResponse.ok(status);
        }

        // 404
        throw new ApiException(404, "API endpoint not found: " + method + " " + path);
    }

    // --- Helpers ---

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
            response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "Content-Type, Authorization");

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
        public ApiException(int statusCode, String message) {
            super(message);
            this.statusCode = statusCode;
        }
        public int getStatusCode() { return statusCode; }
    }
}
