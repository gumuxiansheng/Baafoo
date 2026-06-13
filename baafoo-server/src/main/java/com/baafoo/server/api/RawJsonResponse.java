package com.baafoo.server.api;

/**
 * Wrapper for returning raw JSON content from API handlers.
 * When a handler returns this instead of an ApiResponse,
 * the ManagementApiHandler will write the content directly
 * without additional wrapping.
 */
public class RawJsonResponse {
    private final String json;
    private final String contentType;

    public RawJsonResponse(String json, String contentType) {
        this.json = json;
        this.contentType = contentType;
    }

    public String getJson() { return json; }
    public String getContentType() { return contentType; }
}
