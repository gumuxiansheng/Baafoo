package com.baafoo.server.mapper.entity;

public class UserEntity {
    private String id;
    private String username;
    private String passwordHash;
    private String displayName;
    private String email;
    private String role;
    private String apiKey;
    private Long createdAt;
    private Long updatedAt;
    private Long lastLoginAt;

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPasswordHash() { return passwordHash; }
    public void setPasswordHash(String passwordHash) { this.passwordHash = passwordHash; }

    public String getDisplayName() { return displayName; }
    public void setDisplayName(String displayName) { this.displayName = displayName; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getRole() { return role; }
    public void setRole(String role) { this.role = role; }

    public String getApiKey() { return apiKey; }
    public void setApiKey(String apiKey) { this.apiKey = apiKey; }

    public Long getCreatedAt() { return createdAt; }
    public void setCreatedAt(Long createdAt) { this.createdAt = createdAt; }

    public Long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(Long updatedAt) { this.updatedAt = updatedAt; }

    public Long getLastLoginAt() { return lastLoginAt; }
    public void setLastLoginAt(Long lastLoginAt) { this.lastLoginAt = lastLoginAt; }
}
