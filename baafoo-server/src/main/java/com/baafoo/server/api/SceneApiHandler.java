package com.baafoo.server.api;

import com.baafoo.core.api.ApiResponse;
import com.baafoo.core.model.SceneSet;

class SceneApiHandler implements ResourceHandler {
    @Override
    public Object handle(String method, String path, String body, ApiContext ctx) throws Exception {
        String API_PREFIX = "/__baafoo__/api/";

        if (path.equals(API_PREFIX + "scenes")) {
            if ("GET".equals(method)) return ApiResponse.ok(ctx.storage.listScenes());
            if ("POST".equals(method)) {
                ctx.requirePermission("scene", "create");
                SceneSet scene = ctx.mapper.readValue(body, SceneSet.class);
                return ApiResponse.created(ctx.storage.createScene(scene));
            }
        }

        if (path.startsWith(API_PREFIX + "scenes/")) {
            String id = ApiUtils.extractId(path, API_PREFIX + "scenes/", null);
            if ("GET".equals(method)) {
                SceneSet scene = ctx.storage.listScenes().stream()
                        .filter(s -> s.getId().equals(id)).findFirst().orElse(null);
                return scene != null ? ApiResponse.ok(scene) : ApiResponse.notFound("Scene set not found");
            }
            if ("PUT".equals(method)) {
                ctx.requirePermission("scene", "update");
                SceneSet update = ctx.mapper.readValue(body, SceneSet.class);
                SceneSet updated = ctx.storage.updateScene(id, update);
                return updated != null ? ApiResponse.ok(updated) : ApiResponse.notFound("Scene not found");
            }
            if ("DELETE".equals(method)) {
                ctx.requirePermission("scene", "delete");
                boolean deleted = ctx.storage.deleteScene(id);
                return deleted ? ApiResponse.ok("Deleted", null) : ApiResponse.notFound("Scene not found");
            }
        }

        return null;
    }
}
