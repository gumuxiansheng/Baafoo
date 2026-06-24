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

    /** Rule name (transient — not stored in DB, populated by API layer) */
    private String ruleName;

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

    /** Agent server IP address */
    private String agentIp;

    /** Tags for filtering */
    private Map<String, String> tags;

    /**
     * Direction of recorded data.
     * <ul>
     *   <li>TCP byte recording: {@code "request"} or {@code "response"}</li>
     *   <li>MQ (Kafka/Pulsar/JMS) recording: {@code "produce"} or {@code "consume"}</li>
     * </ul>
     */
    private String direction;

    /** Session ID grouping request/response pairs for the same connection */
    private String sessionId;

    /** Raw data as hex string (for TCP byte recording) */
    private String dataHex;

    /** Duration in milliseconds (for TCP recording, time between request and response) */
    private long durationMs;

    /** Whether this recording was captured without a matching rule (RECORD_ALL mode) */
    private boolean unmatched;

    /** gRPC service name extracted from path (e.g., "helloworld.Greeter") */
    private String grpcService;

    /** gRPC method name extracted from path (e.g., "SayHello") */
    private String grpcMethod;

    /** gRPC response status code (0 = OK, 5 = NOT_FOUND, etc.) */
    private int grpcStatus;

    /** gRPC content type (e.g., "application/grpc", "application/grpc+proto") */
    private String grpcContentType;

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

    public String getRuleName() { return ruleName; }
    public void setRuleName(String ruleName) { this.ruleName = ruleName; }

    public String getEnvironmentId() { return environmentId; }
    public void setEnvironmentId(String environmentId) { this.environmentId = environmentId; }

    public String getAgentId() { return agentId; }
    public void setAgentId(String agentId) { this.agentId = agentId; }

    public String getAgentIp() { return agentIp; }
    public void setAgentIp(String agentIp) { this.agentIp = agentIp; }

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

    public String getDirection() { return direction; }
    public void setDirection(String direction) { this.direction = direction; }

    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }

    public String getDataHex() { return dataHex; }
    public void setDataHex(String dataHex) { this.dataHex = dataHex; }

    public long getDurationMs() { return durationMs; }
    public void setDurationMs(long durationMs) { this.durationMs = durationMs; }

    public boolean isUnmatched() { return unmatched; }
    public void setUnmatched(boolean unmatched) { this.unmatched = unmatched; }

    public String getGrpcService() { return grpcService; }
    public void setGrpcService(String grpcService) { this.grpcService = grpcService; }

    public String getGrpcMethod() { return grpcMethod; }
    public void setGrpcMethod(String grpcMethod) { this.grpcMethod = grpcMethod; }

    public int getGrpcStatus() { return grpcStatus; }
    public void setGrpcStatus(int grpcStatus) { this.grpcStatus = grpcStatus; }

    public String getGrpcContentType() { return grpcContentType; }
    public void setGrpcContentType(String grpcContentType) { this.grpcContentType = grpcContentType; }

    @Override
    public String toString() {
        return "RecordingEntry{" +
                "id='" + id + '\'' +
                ", protocol='" + protocol + '\'' +
                ", method='" + method + '\'' +
                ", path='" + path + '\'' +
                ", responseStatusCode=" + responseStatusCode +
                ", grpcService='" + grpcService + '\'' +
                ", grpcMethod='" + grpcMethod + '\'' +
                ", grpcStatus=" + grpcStatus +
                ", direction='" + direction + '\'' +
                ", sessionId='" + sessionId + '\'' +
                '}';
    }
}
