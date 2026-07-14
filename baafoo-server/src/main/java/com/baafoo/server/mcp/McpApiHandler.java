package com.baafoo.server.mcp;

import com.baafoo.core.api.ApiResponse;
import com.baafoo.server.api.ApiContext;
import com.baafoo.server.api.ResourceHandler;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * MCP API handler (JSON-RPC 2.0 over HTTP).
 * Registered as a ResourceHandler in the ManagementApiHandler chain.
 *
 * Endpoint: POST /__baafoo__/api/mcp
 *
 * Authentication: uses the existing AuthFilter (JWT or X-Api-Key).
 * The authenticated role is passed to each tool via McpToolContext.
 */
public class McpApiHandler implements ResourceHandler {

    private static final Logger log = LoggerFactory.getLogger(McpApiHandler.class);
    private static final String MCP_PATH = "/__baafoo__/api/mcp";

    private final ObjectMapper mapper = com.baafoo.core.util.JsonUtils.MAPPER;
    private final List<McpTool> tools;
    private final Map<String, McpTool> toolMap;

    public McpApiHandler() {
        this.tools = McpToolRegistry.createAllTools();
        this.toolMap = new LinkedHashMap<>();
        for (McpTool tool : tools) {
            toolMap.put(tool.getName(), tool);
        }
        log.info("MCP API Handler initialized with {} tools", tools.size());
    }

    @Override
    public Object handle(String method, String path, String body, ApiContext ctx) throws Exception {
        if (!MCP_PATH.equals(path)) {
            return null;
        }

        if ("OPTIONS".equals(method)) {
            return ApiResponse.ok("OK", null);
        }

        if (!"POST".equals(method)) {
            throw new com.baafoo.server.api.ManagementApiHandler.ApiException(405, "Method not allowed");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> jsonRpc = mapper.readValue(body, Map.class);

        String rpcMethod = (String) jsonRpc.get("method");
        Object id = jsonRpc.get("id");
        @SuppressWarnings("unchecked")
        Map<String, Object> params = (Map<String, Object>) jsonRpc.getOrDefault("params", new HashMap<>());

        Object result;
        switch (rpcMethod != null ? rpcMethod : "") {
            case "initialize":
                result = handleInitialize();
                break;
            case "tools/list":
                result = handleToolsList();
                break;
            case "tools/call":
                result = handleToolsCall(params, ctx);
                break;
            case "ping":
                result = Collections.emptyMap();
                break;
            default:
                return errorResponseMap(id, -32601, "Unknown method: " + rpcMethod);
        }

        return successResponseMap(id, result);
    }

    private Object handleInitialize() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("protocolVersion", "2024-11-05");
        Map<String, Object> capabilities = new LinkedHashMap<>();
        capabilities.put("tools", new HashMap<>());
        result.put("capabilities", capabilities);
        Map<String, Object> serverInfo = new LinkedHashMap<>();
        serverInfo.put("name", "baafoo-mcp-server");
        serverInfo.put("version", "1.0.0-SNAPSHOT");
        result.put("serverInfo", serverInfo);
        return result;
    }

    private Object handleToolsList() {
        List<Map<String, Object>> toolList = new ArrayList<>();
        for (McpTool tool : tools) {
            Map<String, Object> toolInfo = new LinkedHashMap<>();
            toolInfo.put("name", tool.getName());
            toolInfo.put("description", tool.getDescription());
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
    private Object handleToolsCall(Map<String, Object> params, ApiContext ctx) throws Exception {
        String toolName = (String) params.get("name");
        Map<String, Object> arguments = (Map<String, Object>) params.getOrDefault("arguments", new HashMap<>());

        McpTool tool = toolMap.get(toolName);
        if (tool == null) {
            throw new McpException(404, "Unknown tool: " + toolName);
        }

        // Build MCP context from ApiContext
        String role = ctx.getAuth() != null ? ctx.getAuth().getRole() : "guest";
        String username = ctx.getAuth() != null ? ctx.getAuth().getUsername() : null;
        McpToolContext mcpCtx = new McpToolContext(ctx.getStorage(), ctx.getAuthService(), role, username);

        Object toolResult = tool.execute(arguments, mcpCtx);

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

    // --- JSON-RPC response helpers (return Map, serialized by ManagementApiHandler) ---

    private Map<String, Object> successResponseMap(Object id, Object result) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        response.put("result", result);
        return response;
    }

    private Map<String, Object> errorResponseMap(Object id, int code, String message) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        response.put("id", id);
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("code", code);
        error.put("message", message);
        response.put("error", error);
        return response;
    }
}
