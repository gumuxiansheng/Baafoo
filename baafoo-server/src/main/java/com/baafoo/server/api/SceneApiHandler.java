package com.baafoo.server.api;

import com.baafoo.core.api.ApiResponse;
import com.baafoo.core.model.SceneSet;
import com.baafoo.server.storage.SceneService;

class SceneApiHandler implements ResourceHandler {
    @Override
    public Object handle(String method, String path, String body, ApiContext ctx) throws Exception {
        String API_PREFIX = "/__baafoo__/api/";

        // P1-3: prefer the dedicated SceneService when available; fall back to
        // the aggregate StorageService for non-Jdbc storage implementations.
        SceneService scenes = ctx.getSceneService();
        if (scenes == null) {
            scenes = new SceneServiceStorageAdapter(ctx.storage);
        }

        if (path.equals(API_PREFIX + "scenes")) {
            if ("GET".equals(method)) return ApiResponse.ok(scenes.listScenes());
            if ("POST".equals(method)) {
                ctx.requirePermission("scene", "create");
                SceneSet scene = ctx.mapper.readValue(body, SceneSet.class);
                return ApiResponse.created(scenes.createScene(scene));
            }
        }

        if (path.startsWith(API_PREFIX + "scenes/")) {
            String id = ApiUtils.extractId(path, API_PREFIX + "scenes/", null);
            if ("GET".equals(method)) {
                SceneSet scene = scenes.getScene(id);
                return scene != null ? ApiResponse.ok(scene) : ApiResponse.notFound("Scene set not found");
            }
            if ("PUT".equals(method)) {
                ctx.requirePermission("scene", "update");
                SceneSet update = ctx.mapper.readValue(body, SceneSet.class);
                SceneSet updated = scenes.updateScene(id, update);
                return updated != null ? ApiResponse.ok(updated) : ApiResponse.notFound("Scene not found");
            }
            if ("DELETE".equals(method)) {
                ctx.requirePermission("scene", "delete");
                boolean deleted = scenes.deleteScene(id);
                return deleted ? ApiResponse.ok("Deleted", null) : ApiResponse.notFound("Scene not found");
            }
        }

        return null;
    }

    /**
     * Adapter that exposes {@link com.baafoo.server.storage.StorageService}'s
     * scene methods as a {@link SceneService}. Used as a fallback when the
     * backing storage is not a {@code JdbcStorageService} (and therefore does
     * not expose a dedicated SceneService).
     */
    private static class SceneServiceStorageAdapter implements SceneService {
        private final com.baafoo.server.storage.StorageService storage;

        SceneServiceStorageAdapter(com.baafoo.server.storage.StorageService storage) {
            this.storage = storage;
        }

        @Override public java.util.List<SceneSet> listScenes() { return storage.listScenes(); }
        @Override public SceneSet getScene(String id) { return storage.getScene(id); }
        @Override public SceneSet createScene(SceneSet scene) { return storage.createScene(scene); }
        @Override public SceneSet updateScene(String id, SceneSet update) { return storage.updateScene(id, update); }
        @Override public boolean deleteScene(String id) { return storage.deleteScene(id); }
        @Override public java.util.List<String> getInheritedEnvironments(String ruleId) {
            // Fall back to ApiUtils implementation for non-Jdbc storage.
            return ApiUtils.getInheritedEnvironments(storage, ruleId);
        }
    }
}
