package com.baafoo.server.api;

import com.baafoo.core.event.EventBus;
import com.baafoo.server.auth.AuthService;
import com.baafoo.server.storage.StorageService;
import com.fasterxml.jackson.databind.ObjectMapper;

public class ApiContext {
    final StorageService storage;
    final AuthService authService;
    final ObjectMapper mapper;
    final String uri;
    final AuthService.AuthResult auth;
    final String remoteAddr;
    /** P2: Event bus for firing plugin events from API handlers */
    final EventBus eventBus;

    ApiContext(StorageService storage, AuthService authService, ObjectMapper mapper, String uri,
               AuthService.AuthResult auth, String remoteAddr) {
        this(storage, authService, mapper, uri, auth, remoteAddr, null);
    }

    ApiContext(StorageService storage, AuthService authService, ObjectMapper mapper, String uri,
               AuthService.AuthResult auth, String remoteAddr, EventBus eventBus) {
        this.storage = storage;
        this.authService = authService;
        this.mapper = mapper;
        this.uri = uri;
        this.auth = auth;
        this.remoteAddr = remoteAddr;
        this.eventBus = eventBus;
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

    public StorageService getStorage() { return storage; }
    public AuthService getAuthService() { return authService; }
    public AuthService.AuthResult getAuth() { return auth; }
    public String getRemoteAddr() { return remoteAddr; }
    public String getUri() { return uri; }
    public EventBus getEventBus() { return eventBus; }
}
