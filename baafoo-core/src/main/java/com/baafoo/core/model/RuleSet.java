package com.baafoo.core.model;

import java.util.Collections;
import java.util.List;

/**
 * A named collection of rules.
 * Rules can be organized into sets for bulk management.
 */
public class RuleSet {

    /** Unique rule set ID */
    private String id;

    /** Rule set name */
    private String name;

    /** Description */
    private String description;

    /** Rule IDs in this set */
    private List<String> ruleIds;

    /** Whether this set is enabled */
    private boolean enabled;

    /** Tags */
    private List<String> tags;

    /** Created timestamp */
    private long createdAt;

    /** Updated timestamp */
    private long updatedAt;

    public RuleSet() {
        this.ruleIds = Collections.emptyList();
        this.tags = Collections.emptyList();
        this.enabled = true;
    }

    // --- Getters / Setters ---

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public List<String> getRuleIds() { return ruleIds; }
    public void setRuleIds(List<String> ruleIds) { this.ruleIds = ruleIds; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }

    @Override
    public String toString() {
        return "RuleSet{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", ruleCount=" + ruleIds.size() +
                '}';
    }
}
