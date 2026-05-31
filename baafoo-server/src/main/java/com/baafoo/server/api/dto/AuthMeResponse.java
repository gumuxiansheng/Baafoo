package com.baafoo.server.api.dto;

import java.util.List;

public class AuthMeResponse {
    public boolean authenticated;
    public String role;
    public String username;
    public String authMethod;
    public List<String> permissions;

    public AuthMeResponse authenticated(boolean v) { this.authenticated = v; return this; }
    public AuthMeResponse role(String v) { this.role = v; return this; }
    public AuthMeResponse username(String v) { this.username = v; return this; }
    public AuthMeResponse authMethod(String v) { this.authMethod = v; return this; }
    public AuthMeResponse permissions(List<String> v) { this.permissions = v; return this; }
}
