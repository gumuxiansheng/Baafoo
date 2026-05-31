package com.baafoo.server.mapper.entity;

public class EnvironmentEntity {
    private String id;
    private String name;
    private String mode;
    private String agentIdsJson;
    private String variablesJson;
    private String metadataJson;
    private Long createdAt;
    private Long updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getMode() { return mode; }
    public void setMode(String mode) { this.mode = mode; }

    public String getAgentIdsJson() { return agentIdsJson; }
    public void setAgentIdsJson(String agentIdsJson) { this.agentIdsJson = agentIdsJson; }

    public String getVariablesJson() { return variablesJson; }
    public void setVariablesJson(String variablesJson) { this.variablesJson = variablesJson; }

    public String getMetadataJson() { return metadataJson; }
    public void setMetadataJson(String metadataJson) { this.metadataJson = metadataJson; }

    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }

    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }
}
