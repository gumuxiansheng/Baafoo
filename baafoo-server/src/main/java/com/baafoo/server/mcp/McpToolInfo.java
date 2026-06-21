package com.baafoo.server.mcp;

/**
 * MCP tool annotation. Marks a class as an MCP tool.
 */
public @interface McpToolInfo {
    String name();
    String description();
    McpSafetyLevel safetyLevel() default McpSafetyLevel.READ_ONLY;
}
