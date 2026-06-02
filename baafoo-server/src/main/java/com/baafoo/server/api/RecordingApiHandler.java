package com.baafoo.server.api;

import com.baafoo.core.api.ApiResponse;
import com.baafoo.core.api.PaginatedResult;
import com.baafoo.core.model.RecordingEntry;

class RecordingApiHandler implements ResourceHandler {
    @Override
    public Object handle(String method, String path, String body, ApiContext ctx) throws Exception {
        String API_PREFIX = "/__baafoo__/api/";

        if (path.equals(API_PREFIX + "recordings") && "GET".equals(method)) {
            String ruleId = ctx.queryParam("ruleId");
            // Pagination support: page & size params
            String pageStr = ctx.queryParam("page");
            String sizeStr = ctx.queryParam("size");

            if (pageStr != null || sizeStr != null) {
                // Paginated mode
                int page = ctx.queryParamInt("page", 1);
                int size = ctx.queryParamInt("size", 20);
                if (page < 1) page = 1;
                if (size < 1) size = 20;
                if (size > 100) size = 100;
                PaginatedResult<RecordingEntry> result = ctx.storage.listRecordingsPaged(ruleId, page, size);
                return ApiResponse.ok(result);
            } else {
                // Legacy mode: limit-based (backward compatible)
                int limit = ctx.queryParamInt("limit", 100);
                return ApiResponse.ok(ctx.storage.listRecordings(ruleId, limit));
            }
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
