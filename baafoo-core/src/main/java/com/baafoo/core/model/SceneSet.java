package com.baafoo.core.model;

import java.util.Collections;
import java.util.List;

/**
 * Scene set - a collection of rules representing a test scenario.
 * Allows bulk enabling/disabling of rule groups.
 */
public class SceneSet {

    /** Unique scene set ID */
    private String id;

    /** Scene set name */
    private String name;

    /** Description */
    private String description;

    /** Rule/rule set IDs in this scene */
    private List<String> itemIds;

    /** Whether this scene is active */
    private boolean active;

    /** Tags */
    private List<String> tags;

    /** Created timestamp */
    private long createdAt;

    /** Updated timestamp */
    private long updatedAt;

    public SceneSet() {
        this.itemIds = Collections.emptyList();
        this.tags = Collections.emptyList();
        this.active = false;
    }

    // --- Getters / Setters ---

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public List<String> getItemIds() { return itemIds; }
    public void setItemIds(List<String> itemIds) { this.itemIds = itemIds; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }

    @Override
    public String toString() {
        return "SceneSet{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", active=" + active +
                '}';
    }
}
