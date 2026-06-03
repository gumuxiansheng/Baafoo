package com.baafoo.core.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import java.util.Collections;
import java.util.Map;

/**
 * Environment configuration.
 * Each environment has a name, mode, and optional metadata.
 * Agents are associated with a single environment.
 */
public class Environment {

    /** Unique environment ID */
    private String id;

    /** Environment name (e.g., "dev", "staging", "prod") */
    private String name;

    /** Environment description */
    private String description;

    /** Current mode */
    private EnvironmentMode mode;

    /** Agent IDs registered with this environment */
    private java.util.List<String> agentIds;

    /** Environment-level variables for response templating */
    private Map<String, String> variables;

    /** Custom metadata */
    private Map<String, String> metadata;

    /** Created timestamp */
    private long createdAt;

    /** Updated timestamp */
    private long updatedAt;

    public Environment() {
        this.mode = EnvironmentMode.RECORD_AND_STUB;
        this.agentIds = new java.util.ArrayList<String>();
        this.variables = Collections.emptyMap();
        this.metadata = Collections.emptyMap();
    }

    // --- Getters / Setters ---

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public EnvironmentMode getMode() { return mode; }
    public void setMode(EnvironmentMode mode) { this.mode = mode; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public java.util.List<String> getAgentIds() { return agentIds; }
    public void setAgentIds(java.util.List<String> agentIds) { this.agentIds = agentIds; }

    public Map<String, String> getVariables() { return variables; }
    public void setVariables(Map<String, String> variables) { this.variables = variables; }

    public Map<String, String> getMetadata() { return metadata; }
    public void setMetadata(Map<String, String> metadata) { this.metadata = metadata; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }

    @JsonIgnore
    public boolean isStubMode() { return mode == EnvironmentMode.STUB || mode == EnvironmentMode.RECORD_AND_STUB; }

    @JsonIgnore
    public boolean isRecording() { return mode == EnvironmentMode.RECORD || mode == EnvironmentMode.RECORD_AND_STUB; }

    @Override
    public String toString() {
        return "Environment{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", mode=" + mode +
                '}';
    }
}
