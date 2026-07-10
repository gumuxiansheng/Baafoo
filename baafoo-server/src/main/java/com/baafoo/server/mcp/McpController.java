package com.baafoo.server.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * MCP Controller (JSON-RPC 2.0 over HTTP).
 * Handles POST /__baafoo__/api/mcp
 *
 * Authentication: uses the existing AuthFilter (JWT or X-Api-Key).
 * The authenticated role is passed to each tool via McpToolContext.
 */
public class McpController extends SimpleChannelInboundHandler<FullHttpRequest> {

    private static final Logger log = LoggerFactory.getLogger(McpController.class);
    private static final String MCP_PATH = "/__baafoo__/api/mcp";

    private final ObjectMapper mapper;
    private final List<McpTool> tools;
    private final Map<String, McpTool> toolMap;
    private final com.baafoo.server.storage.StorageService storage;
    private final com.baafoo.server.auth.AuthService authService;

    public McpController(com.baafoo.server.storage.StorageService storage,
                         com.baafoo.server.auth.AuthService authService,
                         List<McpTool> tools) {
        this.storage = storage;
        this.authService = authService;
        this.mapper = new ObjectMapper();
        this.tools = tools;
        this.toolMap = new LinkedHashMap<>();
        for (McpTool tool : tools) {
            toolMap.put(tool.getName(), tool);
        }
        log.info("MCP Controller initialized with {} tools", tools.size());
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, FullHttpRequest request) {
        String uri = request.uri();
        String path = extractPath(uri);

        if (!MCP_PATH.equals(path)) {
            ctx.fireChannelRead(request.retain());
            return;
        }

        // CORS preflight
        if ("OPTIONS".equals(request.method().name())) {
            sendJson(ctx, 200, "{\"jsonrpc\":\"2.0\",\"result\":{},\"id\":null}");
            return;
        }

        if (!"POST".equals(request.method().name())) {
            sendJson(ctx, 405, errorResponse(null, -32601, "Method not allowed"));
            return;
        }

        try {
            String body = request.content().toString(StandardCharsets.UTF_8);
            @SuppressWarnings("unchecked")
            Map<String, Object> jsonRpc = mapper.readValue(body, Map.class);

            String method = (String) jsonRpc.get("method");
            Object id = jsonRpc.get("id");
            @SuppressWarnings("unchecked")
            Map<String, Object> params = (Map<String, Object>) jsonRpc.getOrDefault("params", new HashMap<>());

            Object result;
            switch (method != null ? method : "") {
                case "initialize":
                    result = handleInitialize();
                    break;
                case "tools/list":
                    result = handleToolsList();
                    break;
                case "tools/call":
                    result = handleToolsCall(params, request);
                    break;
                case "ping":
                    result = Collections.emptyMap();
                    break;
                default:
                    sendJson(ctx, 200, errorResponse(id, -32601, "Unknown method: " + method));
                    return;
            }

            sendJson(ctx, 200, successResponse(id, result));
        } catch (McpException e) {
            log.warn("MCP error: {}", e.getMessage());
            sendJson(ctx, 200, errorResponse(null, -32000, e.getMessage()));
        } catch (Exception e) {
            log.error("MCP internal error: {}", e.getMessage(), e);
            sendJson(ctx, 500, errorResponse(null, -32603, "Internal error: " + e.getMessage()));
        }
    }

    private Object handleInitialize() {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<String, Object> protocolVersion = new LinkedHashMap<>();
        protocolVersion.put("protocolVersion", "2024-11-05");
        protocolVersion.put("capabilities", Collections.singletonMap("tools", Collections.emptyMap()));
        protocolVersion.put("serverInfo", Collections.singletonMap("name", "baafoo-mcp-server"));
        result.put("protocolVersion", "2024-11-05");
        result.put("capabilities", Collections.singletonMap("tools", Collections.emptyMap()));
        result.put("serverInfo", Collections.singletonMap("name", "baafoo-mcp-server"));
        return result;
    }

    private Object handleToolsList() {
        List<Map<String, Object>> toolList = new ArrayList<>();
        for (McpTool tool : tools) {
            Map<String, Object> toolInfo = new LinkedHashMap<>();
            toolInfo.put("name", tool.getName());
            toolInfo.put("description", tool.getDescription());
            // Parse schema JSON string to Map
            try {
                @SuppressWarnings("unchecked")
                Map<String, Object> schemaMap = mapper.readValue(tool.getInputSchema(), Map.class);
                toolInfo.put("inputSchema", schemaMap);
            } catch (Exception e) {
                toolInfo.put("inputSchema", Collections.emptyMap());
            }
            toolList.add(toolInfo);
        }
        return Collections.singletonMap("tools", toolList);
    }

    @SuppressWarnings("unchecked")
    private Object handleToolsCall(Map<String, Object> params, FullHttpRequest request) throws Exception {
        String toolName = (String) params.get("name");
        Map<String, Object> arguments = (Map<String, Object>) params.getOrDefault("arguments", new HashMap<>());

        McpTool tool = toolMap.get(toolName);
        if (tool == null) {
            throw new McpException(404, "Unknown tool: " + toolName);
        }

        // Read the authenticated role/username set by AuthFilter in request headers.
        String role = request.headers().get("X-Baafoo-Auth-Role");
        String username = request.headers().get("X-Baafoo-Auth-User");
        if (role == null || role.isEmpty()) {
            role = "guest";
        }
        if (username == null || username.isEmpty()) {
            username = "mcp-user";
        }

        // Enforce safety level: AUDIT_REQUIRED tools are restricted to admin only.
        McpSafetyLevel safety = tool.getSafetyLevel();
        if (safety == McpSafetyLevel.AUDIT_REQUIRED && !"admin".equals(role)) {
            throw new McpException(403, "Permission denied: tool '" + toolName + "' requires admin role, you have " + role);
        }

        McpToolContext ctx = new McpToolContext(storage, authService, role, username);

        Object toolResult = tool.execute(arguments, ctx);

        // Wrap result in MCP content format
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> content = new ArrayList<>();
        Map<String, Object> textContent = new LinkedHashMap<>();
        textContent.put("type", "text");
        textContent.put("text", mapper.writeValueAsString(toolResult));
        content.add(textContent);
        result.put("content", content);
        return result;
    }

    // --- JSON-RPC helpers ---

    private String successResponse(Object id, Object result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("result", result);
        try {
            return mapper.writeValueAsString(response);
        } catch (Exception e) {
            return "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32603,\"message\":\"Serialization error\"}}";
        }
    }

    private String errorResponse(Object id, int code, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        response.put("error", error);
        try {
            return mapper.writeValueAsString(response);
        } catch (Exception e) {
            return "{\"jsonrpc\":\"2.0\",\"id\":null,\"error\":{\"code\":-32603,\"message\":\"Serialization error\"}}";
        }
    }

    private void sendJson(ChannelHandlerContext ctx, int statusCode, String json) {
        FullHttpResponse response = new DefaultFullHttpResponse(
                HttpVersion.HTTP_1_1, HttpResponseStatus.valueOf(statusCode),
                Unpooled.copiedBuffer(json, StandardCharsets.UTF_8));
        response.headers().set(HttpHeaderNames.CONTENT_TYPE, "application/json; charset=UTF-8");
        response.headers().set(HttpHeaderNames.CONTENT_LENGTH, response.content().readableBytes());
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_ORIGIN, "*");
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_METHODS, "GET, POST, OPTIONS");
        response.headers().set(HttpHeaderNames.ACCESS_CONTROL_ALLOW_HEADERS, "Content-Type, Authorization, X-Api-Key");
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }

    private String extractPath(String uri) {
        int qIdx = uri.indexOf('?');
        return qIdx >= 0 ? uri.substring(0, qIdx) : uri;
    }
}
