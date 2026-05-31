package com.baafoo.server.api.dto;

public class LoginResponse {
    public String token;
    public String role;

    public LoginResponse token(String v) { this.token = v; return this; }
    public LoginResponse role(String v) { this.role = v; return this; }
}
