package com.baafoo.server.api;

import com.baafoo.core.api.ApiResponse;
import com.baafoo.core.model.MqRelationship;

class MqRelationshipApiHandler implements ResourceHandler {
    @Override
    public Object handle(String method, String path, String body, ApiContext ctx) throws Exception {
        String API_PREFIX = "/__baafoo__/api/";

        if (path.equals(API_PREFIX + "mq-relationships")) {
            if ("GET".equals(method)) return ApiResponse.ok(ctx.storage.listMqRelationships());
            if ("POST".equals(method)) {
                ctx.requirePermission("mq-relationship", "create");
                MqRelationship relationship = ctx.mapper.readValue(body, MqRelationship.class);
                return ApiResponse.created(ctx.storage.createMqRelationship(relationship));
            }
        }

        if (path.startsWith(API_PREFIX + "mq-relationships/")) {
            String id = ApiUtils.extractId(path, API_PREFIX + "mq-relationships/", null);
            if ("GET".equals(method)) {
                MqRelationship relationship = ctx.storage.getMqRelationship(id);
                return relationship != null ? ApiResponse.ok(relationship) : ApiResponse.notFound("MQ relationship not found");
            }
            if ("PUT".equals(method)) {
                ctx.requirePermission("mq-relationship", "update");
                MqRelationship update = ctx.mapper.readValue(body, MqRelationship.class);
                MqRelationship updated = ctx.storage.updateMqRelationship(id, update);
                return updated != null ? ApiResponse.ok(updated) : ApiResponse.notFound("MQ relationship not found");
            }
            if ("DELETE".equals(method)) {
                ctx.requirePermission("mq-relationship", "delete");
                boolean deleted = ctx.storage.deleteMqRelationship(id);
                return deleted ? ApiResponse.ok("Deleted", null) : ApiResponse.notFound("MQ relationship not found");
            }
        }

        return null;
    }
}
