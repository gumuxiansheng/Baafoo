package com.baafoo.server.api.dto;

import java.time.OffsetDateTime;

public class UserSafeResponse {
    public Long id;
    public String username;
    public String displayName;
    public String email;
    public String phone;
    public String avatar;
    public String role;
    public boolean apiKey;
    public OffsetDateTime createdAt;
    public OffsetDateTime updatedAt;
    public OffsetDateTime lastLoginAt;
}
