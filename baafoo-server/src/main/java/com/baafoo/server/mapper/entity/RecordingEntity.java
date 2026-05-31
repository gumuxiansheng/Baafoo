package com.baafoo.server.mapper.entity;

public class RecordingEntity {
    private String id;
    private String ruleId;
    private String environmentId;
    private String agentId;
    private String protocol;
    private String host;
    private Integer port;
    private String serviceName;
    private String method;
    private String path;
    private String requestHeadersJson;
    private String requestBody;
    private Integer responseStatusCode;
    private String responseHeadersJson;
    private String responseBody;
    private Long responseTimeMs;
    private Long recordedAt;
    private String tagsJson;

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

    public Integer getPort() { return port; }
    public void setPort(Integer port) { this.port = port; }

    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }

    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public String getRequestHeadersJson() { return requestHeadersJson; }
    public void setRequestHeadersJson(String requestHeadersJson) { this.requestHeadersJson = requestHeadersJson; }

    public String getRequestBody() { return requestBody; }
    public void setRequestBody(String requestBody) { this.requestBody = requestBody; }

    public Integer getResponseStatusCode() { return responseStatusCode; }
    public void setResponseStatusCode(Integer responseStatusCode) { this.responseStatusCode = responseStatusCode; }

    public String getResponseHeadersJson() { return responseHeadersJson; }
    public void setResponseHeadersJson(String responseHeadersJson) { this.responseHeadersJson = responseHeadersJson; }

    public String getResponseBody() { return responseBody; }
    public void setResponseBody(String responseBody) { this.responseBody = responseBody; }

    public Long getResponseTimeMs() { return responseTimeMs; }
    public void setResponseTimeMs(Long responseTimeMs) { this.responseTimeMs = responseTimeMs; }

    public Long getRecordedAt() { return recordedAt; }
    public void setRecordedAt(Long recordedAt) { this.recordedAt = recordedAt; }

    public String getTagsJson() { return tagsJson; }
    public void setTagsJson(String tagsJson) { this.tagsJson = tagsJson; }
}
