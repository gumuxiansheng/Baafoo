package com.baafoo.server.api.dto;

public class ApiKeyResponse {
    public String apiKey;

    public ApiKeyResponse apiKey(String v) { this.apiKey = v; return this; }
}
