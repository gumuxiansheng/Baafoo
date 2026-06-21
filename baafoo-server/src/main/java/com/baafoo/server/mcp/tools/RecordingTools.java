package com.baafoo.server.mcp.tools;

import com.baafoo.core.api.PaginatedResult;
import com.baafoo.core.model.RecordingEntry;
import com.baafoo.server.mcp.*;

import java.util.*;

/**
 * Recording (request log) management MCP tools.
 */
public class RecordingTools {

    public static class ListRecordingsTool implements McpTool {
        @Override public String getName() { return "list_recordings"; }
        @Override public String getDescription() { return "列出请求录制记录（分页）。可按规则ID、协议、方法、路径、状态码等过滤。"; }
        @Override public String getInputSchema() {
            return McpSchemaBuilder.create()
                    .integerProperty("page", "页码，默认1")
                    .integerProperty("size", "每页数量，默认20")
                    .stringProperty("ruleId", "规则ID过滤")
                    .stringProperty("agentId", "Agent ID过滤")
                    .stringProperty("protocol", "协议过滤")
                    .stringProperty("method", "HTTP方法过滤")
                    .stringProperty("path", "请求路径过滤")
                    .integerProperty("statusCode", "状态码过滤")
                    .stringProperty("keyword", "关键字搜索")
                    .buildJson();
        }
        @Override public McpSafetyLevel getSafetyLevel() { return McpSafetyLevel.READ_ONLY; }
        @Override public Object execute(Map<String, Object> args, McpToolContext ctx) {
            Integer page = McpToolContext.getInteger(args, "page");
            Integer size = McpToolContext.getInteger(args, "size");
            String ruleId = McpToolContext.getString(args, "ruleId");
            String agentId = McpToolContext.getString(args, "agentId");
            String protocol = McpToolContext.getString(args, "protocol");
            String method = McpToolContext.getString(args, "method");
            String path = McpToolContext.getString(args, "path");
            Integer statusCode = McpToolContext.getInteger(args, "statusCode");
            String keyword = McpToolContext.getString(args, "keyword");

            PaginatedResult<RecordingEntry> result = ctx.getStorage().listRecordingsPaged(
                    ruleId, agentId, null, protocol, method, path, statusCode, keyword,
                    page != null ? page : 1,
                    size != null ? size : 20);
            return formatPage(result);
        }
    }

    public static class GetRecordingStatsTool implements McpTool {
        @Override public String getName() { return "get_recording_stats"; }
        @Override public String getDescription() { return "获取录制统计信息：总数、总大小、按日趋势。"; }
        @Override public String getInputSchema() {
            return McpSchemaBuilder.create().buildJson();
        }
        @Override public McpSafetyLevel getSafetyLevel() { return McpSafetyLevel.READ_ONLY; }
        @Override public Object execute(Map<String, Object> args, McpToolContext ctx) {
            Map<String, Object> stats = new LinkedHashMap<>();
            stats.put("totalCount", ctx.getStorage().getRecordingCount());
            stats.put("totalSizeBytes", ctx.getStorage().getRecordingTotalSizeBytes());
            long sevenDaysAgo = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000;
            stats.put("dailyCounts", ctx.getStorage().getRecordingCountsByDay(sevenDaysAgo));
            return stats;
        }
    }

    public static class DeleteRecordingTool implements McpTool {
        @Override public String getName() { return "delete_recording"; }
        @Override public String getDescription() { return "删除指定录制记录。需要 developer 或 tester 权限。"; }
        @Override public String getInputSchema() {
            return McpSchemaBuilder.create()
                    .requiredString("id", "录制记录ID")
                    .buildJson();
        }
        @Override public McpSafetyLevel getSafetyLevel() { return McpSafetyLevel.AUDIT_REQUIRED; }
        @Override public Object execute(Map<String, Object> args, McpToolContext ctx) {
            ctx.requirePermission("recording", "delete");
            String id = McpToolContext.requireString(args, "id");
            boolean deleted = ctx.getStorage().deleteRecording(id);
            if (!deleted) throw new McpException(404, "Recording not found: " + id);
            Map<String, Object> result = new HashMap<>();
            result.put("message", "Recording deleted");
            result.put("id", id);
            return result;
        }
    }

    private static Map<String, Object> formatPage(PaginatedResult<?> page) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", page.getItems());
        result.put("total", page.getTotal());
        result.put("page", page.getPage());
        result.put("size", page.getSize());
        result.put("totalPages", page.getTotalPages());
        return result;
    }
}
