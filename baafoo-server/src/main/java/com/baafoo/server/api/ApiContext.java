package com.baafoo.server.api;

import com.baafoo.server.auth.AuthService;
import com.baafoo.server.storage.StorageService;
import com.fasterxml.jackson.databind.ObjectMapper;

class ApiContext {
    final StorageService storage;
    final AuthService authService;
    final ObjectMapper mapper;
    final String uri;
    final AuthService.AuthResult auth;
    final String remoteAddr;

    ApiContext(StorageService storage, AuthService authService, ObjectMapper mapper, String uri, AuthService.AuthResult auth, String remoteAddr) {
        this.storage = storage;
        this.authService = authService;
        this.mapper = mapper;
        this.uri = uri;
        this.auth = auth;
        this.remoteAddr = remoteAddr;
    }

    String queryParam(String key) {
        return ApiUtils.parseQueryParam(uri, key);
    }

    int queryParamInt(String key, int defaultVal) {
        return ApiUtils.parseIntParam(uri, key, defaultVal);
    }

    void requirePermission(String resource, String action) {
        if (!AuthService.hasPermission(auth.getRole(), resource, action)) {
            throw new ManagementApiHandler.ApiException(403, "permission_denied",
                    ApiUtils.getRequiredRoleForAction(resource, action), auth.getRole());
        }
    }
}
