package com.baafoo.server.api;

import com.baafoo.core.api.ApiResponse;

class RecordingApiHandler implements ResourceHandler {
    @Override
    public Object handle(String method, String path, String body, ApiContext ctx) throws Exception {
        String API_PREFIX = "/__baafoo__/api/";

        if (path.equals(API_PREFIX + "recordings") && "GET".equals(method)) {
            String ruleId = ctx.queryParam("ruleId");
            int limit = ctx.queryParamInt("limit", 100);
            return ApiResponse.ok(ctx.storage.listRecordings(ruleId, limit));
        }

        if (path.startsWith(API_PREFIX + "recordings/") && "DELETE".equals(method)) {
            ctx.requirePermission("recording", "delete");
            String id = ApiUtils.extractId(path, API_PREFIX + "recordings/", null);
            boolean deleted = ctx.storage.deleteRecording(id);
            return deleted ? ApiResponse.ok("Deleted", null) : ApiResponse.notFound("Recording not found");
        }

        return null;
    }
}
