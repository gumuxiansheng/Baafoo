package com.baafoo.core.model;

import java.util.Collections;
import java.util.Map;

/**
 * A response entry within a rule.
 * Rules can have multiple responses (parameterized by conditions).
 * The first matching response is returned.
 */
public class ResponseEntry {

    /** Response name (e.g., "成功", "参数错误", "超时") */
    private String name;

    /** HTTP status code */
    private int statusCode;

    /** Response headers */
    private Map<String, String> headers;

    /** Response body template (supports variable substitution) */
    private String body;

    /** Response delay in milliseconds */
    private long delayMs;

    /** Match condition for this response (empty = always match / default) */
    private MatchCondition condition;

    /** Response body charset (null = UTF-8). Supports GBK, GB2312, Big5, ISO-8859-1, etc. */
    private String charset;

    /** gRPC status code (0 = OK, 5 = NOT_FOUND, 12 = UNIMPLEMENTED, etc.). Defaults to 0. */
    private int grpcStatus;

    /** gRPC status message (human-readable error details). */
    private String grpcStatusMessage;

    public ResponseEntry() {
        this.statusCode = 200;
        this.headers = Collections.emptyMap();
        this.delayMs = 0;
        this.grpcStatus = 0;
    }

    // --- Getters / Setters ---

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public int getStatusCode() { return statusCode; }
    public void setStatusCode(int statusCode) { this.statusCode = statusCode; }

    public Map<String, String> getHeaders() { return headers; }
    public void setHeaders(Map<String, String> headers) { this.headers = headers; }

    public String getBody() { return body; }
    public void setBody(String body) { this.body = body; }

    public long getDelayMs() { return delayMs; }
    public void setDelayMs(long delayMs) { this.delayMs = delayMs; }

    public MatchCondition getCondition() { return condition; }
    public void setCondition(MatchCondition condition) { this.condition = condition; }

    public String getCharset() { return charset; }
    public void setCharset(String charset) { this.charset = charset; }

    public int getGrpcStatus() { return grpcStatus; }
    public void setGrpcStatus(int grpcStatus) { this.grpcStatus = grpcStatus; }

    public String getGrpcStatusMessage() { return grpcStatusMessage; }
    public void setGrpcStatusMessage(String grpcStatusMessage) { this.grpcStatusMessage = grpcStatusMessage; }

    @Override
    public String toString() {
        return "ResponseEntry{" +
                "name='" + name + '\'' +
                ", statusCode=" + statusCode +
                ", grpcStatus=" + grpcStatus +
                ", delayMs=" + delayMs +
                '}';
    }
}
