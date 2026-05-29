package com.baafoo.plugin;

import java.util.Collections;
import java.util.Map;

/**
 * Result returned by Plugin processing.
 *
 * <p><b>stub</b> mode: plugin returns the mocked response.</p>
 * <p><b>passthrough</b> mode: plugin delegates to the original callable.</p>
 * <p><b>record</b> mode: plugin records the real response then returns it.</p>
 * <p><b>record-and-stub</b> mode: combine both (record + return stub).</p>
 */
public class InterceptResult {

    /** Whether the interception was handled (stubbed) */
    private boolean stubbed;

    /** Response data (for stubbed responses) */
    private byte[] responseData;

    /** Response headers */
    private Map<String, String> responseHeaders;

    /** Response status code (for HTTP) */
    private int statusCode;

    /** Error message if interception failed */
    private String errorMessage;

    /** Metadata for recording */
    private Map<String, Object> metadata;

    public InterceptResult() {
        this.stubbed = false;
        this.responseData = new byte[0];
        this.responseHeaders = Collections.emptyMap();
        this.statusCode = 200;
    }

    /**
     * Create a stubbed result with pre-configured response data.
     */
    public static InterceptResult stub(byte[] data, Map<String, String> headers, int statusCode) {
        InterceptResult result = new InterceptResult();
        result.stubbed = true;
        result.responseData = data;
        result.responseHeaders = headers;
        result.statusCode = statusCode;
        return result;
    }

    /**
     * Create a passthrough result (not stubbed).
     */
    public static InterceptResult passthrough() {
        return new InterceptResult();
    }

    /**
     * Create an error result.
     */
    public static InterceptResult error(String message) {
        InterceptResult result = new InterceptResult();
        result.stubbed = true;
        result.errorMessage = message;
        result.statusCode = 500;
        return result;
    }

    // --- Getters / Setters ---

    public boolean isStubbed() { return stubbed; }
    public void setStubbed(boolean stubbed) { this.stubbed = stubbed; }

    public byte[] getResponseData() { return responseData; }
    public void setResponseData(byte[] responseData) { this.responseData = responseData; }

    public Map<String, String> getResponseHeaders() { return responseHeaders; }
    public void setResponseHeaders(Map<String, String> responseHeaders) { this.responseHeaders = responseHeaders; }

    public int getStatusCode() { return statusCode; }
    public void setStatusCode(int statusCode) { this.statusCode = statusCode; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public Map<String, Object> getMetadata() { return metadata; }
    public void setMetadata(Map<String, Object> metadata) { this.metadata = metadata; }

    @Override
    public String toString() {
        return "InterceptResult{" +
                "stubbed=" + stubbed +
                ", statusCode=" + statusCode +
                ", responseDataSize=" + (responseData != null ? responseData.length : 0) +
                '}';
    }
}
