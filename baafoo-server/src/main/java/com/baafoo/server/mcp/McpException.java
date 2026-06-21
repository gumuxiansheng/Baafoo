package com.baafoo.server.mcp;

/**
 * MCP exception.
 */
public class McpException extends RuntimeException {

    private final int statusCode;

    public McpException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public int getStatusCode() { return statusCode; }
}
