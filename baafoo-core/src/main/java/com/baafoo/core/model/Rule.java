package com.baafoo.core.model;

import java.util.List;
import java.util.Collections;

/**
 * A single stub/mock rule that defines how to match and respond to a request.
 */
public class Rule {

    /** Unique rule ID */
    private String id;

    /** Rule name (human-readable) */
    private String name;

    /** Protocol this rule applies to */
    private String protocol;

    /** Target service name (for Consul-based rules) */
    private String serviceName;

    /** Target host (for direct host:port rules) */
    private String host;

    /** Target port (for direct host:port rules) */
    private Integer port;

    /** Match conditions (all must match for rule to apply) */
    private List<MatchCondition> conditions;

    /** Response configurations (for parameterized rules) */
    private List<ResponseEntry> responses;

    /** Whether this rule is enabled */
    private boolean enabled;

    /** Rule priority (lower = higher priority, default 100) */
    private int priority;

    /** Rule tags */
    private List<String> tags;

    /** Rule version for undo support */
    private int version;

    /** Created timestamp */
    private long createdAt;

    /** Updated timestamp */
    private long updatedAt;

    public Rule() {
        this.conditions = Collections.emptyList();
        this.responses = Collections.emptyList();
        this.tags = Collections.emptyList();
        this.enabled = true;
        this.priority = 100;
        this.version = 1;
    }

    // --- Getters / Setters ---

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

    public List<MatchCondition> getConditions() { return conditions; }
    public void setConditions(List<MatchCondition> conditions) { this.conditions = conditions; }

    public List<ResponseEntry> getResponses() { return responses; }
    public void setResponses(List<ResponseEntry> responses) { this.responses = responses; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }

    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }

    @Override
    public String toString() {
        return "Rule{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", protocol='" + protocol + '\'' +
                ", enabled=" + enabled +
                '}';
    }
}
