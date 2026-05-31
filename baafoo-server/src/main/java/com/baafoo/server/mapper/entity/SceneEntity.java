package com.baafoo.server.mapper.entity;

public class SceneEntity {
    private String id;
    private String name;
    private String description;
    private String itemIdsJson;
    private Boolean active;
    private String tagsJson;
    private String environmentsJson;
    private Long createdAt;
    private Long updatedAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getItemIdsJson() { return itemIdsJson; }
    public void setItemIdsJson(String itemIdsJson) { this.itemIdsJson = itemIdsJson; }

    public Boolean getActive() { return active; }
    public void setActive(Boolean active) { this.active = active; }

    public String getTagsJson() { return tagsJson; }
    public void setTagsJson(String tagsJson) { this.tagsJson = tagsJson; }

    public String getEnvironmentsJson() { return environmentsJson; }
    public void setEnvironmentsJson(String environmentsJson) { this.environmentsJson = environmentsJson; }

    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }

    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }
}
