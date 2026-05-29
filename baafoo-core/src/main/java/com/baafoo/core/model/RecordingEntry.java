package com.baafoo.core.model;

import java.util.Collections;
import java.util.Map;

/**
 * Recorded request/response pair.
 * Stored during record mode and used for replay.
 */
public class RecordingEntry {

    /** Unique recording ID */
    private String id;

    /** Rule ID that triggered this recording */
    private String ruleId;

    /** Environment ID */
    private String environmentId;

    /** Agent ID that recorded this */
    private String agentId;

    /** Protocol */
    private String protocol;

    /** Target host */
    private String host;

    /** Target port */
    private int port;

    /** Service name (for Consul) */
    private String serviceName;

    /** Request method (for HTTP) */
    private String method;

    /** Request path (for HTTP) */
    private String path;

    /** Request headers */
    private Map<String, String> requestHeaders;

    /** Request body */
    private String requestBody;

    /** Response status code */
    private int responseStatusCode;

    /** Response headers */
    private Map<String, String> responseHeaders;

    /** Response body */
    private String responseBody;

    /** Response time in milliseconds */
    private long responseTimeMs;

    /** Recording timestamp */
    private long recordedAt;

    /** Tags for filtering */
    private Map<String, String> tags;

    public RecordingEntry() {
        this.requestHeaders = Collections.emptyMap();
        this.responseHeaders = Collections.emptyMap();
        this.tags = Collections.emptyMap();
    }

    // --- Getters / Setters ---

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getRuleId() { return ruleId; }
    public void setRuleId(String ruleId) { this.ruleId = ruleId; }

    public String getEnvironmentId() { return environmentId; }
    public void setEnvironmentId(String environmentId) { this.environmentId = environmentId; }

    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }

    public String getProtocol() { return protocol; }
    public void setProtocol(String protocol) { this.protocol = protocol; }

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }

    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public Map<String, String> getRequestHeaders() { return requestHeaders; }
    public void setRequestHeaders(Map<String, String> requestHeaders) { this.requestHeaders = requestHeaders; }

    public String getRequestBody() { return requestBody; }
    public void setRequestBody(String requestBody) { this.requestBody = requestBody; }

    public int getResponseStatusCode() { return responseStatusCode; }
    public void setResponseStatusCode(int responseStatusCode) { this.responseStatusCode = responseStatusCode; }

    public Map<String, String> getResponseHeaders() { return responseHeaders; }
    public void setResponseHeaders(Map<String, String> responseHeaders) { this.responseHeaders = responseHeaders; }

    public String getResponseBody() { return responseBody; }
    public void setResponseBody(String responseBody) { this.responseBody = responseBody; }

    public long getResponseTimeMs() { return responseTimeMs; }
    public void setResponseTimeMs(long responseTimeMs) { this.responseTimeMs = responseTimeMs; }

    public long getRecordedAt() { return recordedAt; }
    public void setRecordedAt(long recordedAt) { this.recordedAt = recordedAt; }

    public Map<String, String> getTags() { return tags; }
    public void setTags(Map<String, String> tags) { this.tags = tags; }

    @Override
    public String toString() {
        return "RecordingEntry{" +
                "id='" + id + '\'' +
                ", protocol='" + protocol + '\'' +
                ", method='" + method + '\'' +
                ", path='" + path + '\'' +
                ", responseStatusCode=" + responseStatusCode +
                '}';
    }
}
