package com.baafoo.server.api;

import com.baafoo.core.api.ApiResponse;
import com.baafoo.core.api.PaginatedResult;
import com.baafoo.core.model.RecordingEntry;

import java.util.List;

class RecordingApiHandler implements ResourceHandler {
    @Override
    public Object handle(String method, String path, String body, ApiContext ctx) throws Exception {
        String API_PREFIX = "/__baafoo__/api/";

        // HAR export endpoint
        if (path.equals(API_PREFIX + "logs/export/har") && "GET".equals(method)) {
            String ruleId = ctx.queryParam("ruleId");
            int limit = ctx.queryParamInt("limit", 1000);
            if (limit > 10000) limit = 10000;
            List<RecordingEntry> recordings = ctx.storage.listRecordings(ruleId, limit);
            String harJson = HarExporter.export(recordings);
            return new RawJsonResponse(harJson, "application/json");
        }

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

                // Search filters
                String agentId = ctx.queryParam("agentId");
                String agentIp = ctx.queryParam("agentIp");
                String protocol = ctx.queryParam("protocol");
                String reqMethod = ctx.queryParam("method");
                String reqPath = ctx.queryParam("path");
                String statusCodeStr = ctx.queryParam("statusCode");
                Integer statusCode = null;
                if (statusCodeStr != null && !statusCodeStr.isEmpty()) {
                    try { statusCode = Integer.parseInt(statusCodeStr); } catch (NumberFormatException ignored) {}
                }
                String keyword = ctx.queryParam("keyword");

                PaginatedResult<RecordingEntry> result = ctx.storage.listRecordingsPaged(
                        ruleId, agentId, agentIp, protocol, reqMethod, reqPath, statusCode, keyword, page, size);
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
