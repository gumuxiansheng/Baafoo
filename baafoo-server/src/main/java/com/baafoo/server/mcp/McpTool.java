package com.baafoo.server.mcp;

/**
 * MCP tool interface. Each tool handles one operation.
 */
public interface McpTool {

    String getName();

    String getDescription();

    String getInputSchema();

    McpSafetyLevel getSafetyLevel();

    Object execute(java.util.Map<String, Object> args, McpToolContext ctx) throws Exception;
}
