package com.baafoo.server.api;

import com.baafoo.core.config.ServerConfig;
import com.baafoo.core.event.EventBus;
import com.baafoo.core.i18n.I18n;
import com.baafoo.server.auth.AuthService;
import com.baafoo.server.storage.JdbcStorageService;
import com.baafoo.server.storage.SceneService;
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
    /** i18n message resolver for the request locale */
    final I18n i18n;
    /** Server configuration (may be null in test contexts) */
    final ServerConfig config;

    ApiContext(StorageService storage, AuthService authService, ObjectMapper mapper, String uri,
               AuthService.AuthResult auth, String remoteAddr) {
        this(storage, authService, mapper, uri, auth, remoteAddr, null, I18n.defaultInstance(), null);
    }

    ApiContext(StorageService storage, AuthService authService, ObjectMapper mapper, String uri,
               AuthService.AuthResult auth, String remoteAddr, EventBus eventBus) {
        this(storage, authService, mapper, uri, auth, remoteAddr, eventBus, I18n.defaultInstance(), null);
    }

    ApiContext(StorageService storage, AuthService authService, ObjectMapper mapper, String uri,
               AuthService.AuthResult auth, String remoteAddr, EventBus eventBus, I18n i18n) {
        this(storage, authService, mapper, uri, auth, remoteAddr, eventBus, i18n, null);
    }

    ApiContext(StorageService storage, AuthService authService, ObjectMapper mapper, String uri,
               AuthService.AuthResult auth, String remoteAddr, EventBus eventBus, I18n i18n, ServerConfig config) {
        this.storage = storage;
        this.authService = authService;
        this.mapper = mapper;
        this.uri = uri;
        this.auth = auth;
        this.remoteAddr = remoteAddr;
        this.eventBus = eventBus;
        this.i18n = i18n;
        this.config = config;
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

    /**
     * Get the i18n message resolver for the current request locale.
     */
    public I18n getI18n() { return i18n; }

    public StorageService getStorage() { return storage; }

    /**
     * P1-3: returns the {@link SceneService} if the backing storage exposes one
     * (e.g., {@link JdbcStorageService}). Returns {@code null} for storage
     * implementations that have not been migrated. Callers should fall back to
     * {@link #getStorage()} when null.
     */
    public SceneService getSceneService() {
        if (storage instanceof JdbcStorageService) {
            return ((JdbcStorageService) storage).getSceneService();
        }
        return null;
    }

    public AuthService getAuthService() { return authService; }
    public AuthService.AuthResult getAuth() { return auth; }
    public String getRemoteAddr() { return remoteAddr; }
    public String getUri() { return uri; }
    public EventBus getEventBus() { return eventBus; }

    /** Server configuration, or null in test contexts. */
    public ServerConfig getConfig() { return config; }
}
