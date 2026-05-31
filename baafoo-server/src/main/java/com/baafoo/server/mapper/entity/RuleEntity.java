package com.baafoo.server.mapper.entity;

public class RuleEntity {
    private String id;
    private String name;
    private String protocol;
    private String serviceName;
    private String host;
    private Integer port;
    private String conditionsJson;
    private String responsesJson;
    private Boolean enabled;
    private Integer priority;
    private String tagsJson;
    private String environmentsJson;
    private Integer version;
    private Long createdAt;
    private Long updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getProtocol() { return protocol; }
    public void setProtocol(String protocol) { this.protocol = protocol; }

    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public Integer getPort() { return port; }
    public void setPort(Integer port) { this.port = port; }

    public String getConditionsJson() { return conditionsJson; }
    public void setConditionsJson(String conditionsJson) { this.conditionsJson = conditionsJson; }

    public String getResponsesJson() { return responsesJson; }
    public void setResponsesJson(String responsesJson) { this.responsesJson = responsesJson; }

    public Boolean getEnabled() { return enabled; }
    public void setEnabled(Boolean enabled) { this.enabled = enabled; }

    public Integer getPriority() { return priority; }
    public void setPriority(Integer priority) { this.priority = priority; }

    public String getTagsJson() { return tagsJson; }
    public void setTagsJson(String tagsJson) { this.tagsJson = tagsJson; }

    public String getEnvironmentsJson() { return environmentsJson; }
    public void setEnvironmentsJson(String environmentsJson) { this.environmentsJson = environmentsJson; }

    public Integer getVersion() { return version; }
    public void setVersion(Integer version) { this.version = version; }

    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }

    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }
}
