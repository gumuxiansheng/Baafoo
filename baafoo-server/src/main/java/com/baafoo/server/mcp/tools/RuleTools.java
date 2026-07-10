package com.baafoo.server.mcp.tools;

import com.baafoo.core.api.PaginatedResult;
import com.baafoo.core.model.Rule;
import com.baafoo.server.mcp.*;

import java.util.*;

/**
 * Rule management MCP tools.
 */
public class RuleTools {

    public static class ListRulesTool implements McpTool {
        @Override public String getName() { return "list_rules"; }
        @Override public String getDescription() { return "列出所有 Mock 规则（分页）。可按协议、关键字、环境、主机过滤。"; }
        @Override public String getInputSchema() {
            return McpSchemaBuilder.create()
                    .integerProperty("page", "页码，默认1")
                    .integerProperty("size", "每页数量，默认20")
                    .stringProperty("protocol", "协议过滤（http/tcp/kafka/pulsar/jms）")
                    .stringProperty("keyword", "搜索关键字")
                    .stringProperty("environment", "环境过滤")
                    .stringProperty("host", "主机过滤")
                    .buildJson();
        }
        @Override public McpSafetyLevel getSafetyLevel() { return McpSafetyLevel.READ_ONLY; }
        @Override public Object execute(Map<String, Object> args, McpToolContext ctx) {
            Integer page = McpToolContext.getInteger(args, "page");
            Integer size = McpToolContext.getInteger(args, "size");
            String protocol = McpToolContext.getString(args, "protocol");
            String keyword = McpToolContext.getString(args, "keyword");
            String environment = McpToolContext.getString(args, "environment");
            String host = McpToolContext.getString(args, "host");

            PaginatedResult<Rule> result = ctx.getStorage().listRulesPaged(
                    protocol, keyword, environment, host,
                    null, null,
                    page != null ? page : 1,
                    size != null ? size : 20);
            return formatPage(result);
        }
    }

    public static class GetRuleTool implements McpTool {
        @Override public String getName() { return "get_rule"; }
        @Override public String getDescription() { return "获取指定 Mock 规则的详细信息。"; }
        @Override public String getInputSchema() {
            return McpSchemaBuilder.create()
                    .requiredString("id", "规则ID")
                    .buildJson();
        }
        @Override public McpSafetyLevel getSafetyLevel() { return McpSafetyLevel.READ_ONLY; }
        @Override public Object execute(Map<String, Object> args, McpToolContext ctx) {
            String id = McpToolContext.requireString(args, "id");
            Rule rule = ctx.getStorage().getRule(id);
            if (rule == null) throw new McpException(404, "Rule not found: " + id);
            return rule;
        }
    }

    public static class CreateRuleTool implements McpTool {
        @Override public String getName() { return "create_rule"; }
        @Override public String getDescription() { return "创建新的 Mock 规则。需要 developer 或 admin 权限。"; }
        @Override public String getInputSchema() {
            return McpSchemaBuilder.create()
                    .requiredString("id", "规则ID（唯一）")
                    .requiredString("name", "规则名称")
                    .requiredString("protocol", "协议（http/tcp/kafka/pulsar/jms）")
                    .stringProperty("host", "目标主机")
                    .integerProperty("port", "目标端口")
                    .stringProperty("serviceName", "目标服务名（Consul模式）")
                    .booleanProperty("enabled", "是否启用（默认true）")
                    .integerProperty("priority", "优先级（数字越小优先级越高，默认100）")
                    .arrayProperty("tags", "标签列表", "string")
                    .arrayProperty("environments", "生效环境列表", "string")
                    .buildJson();
        }
        @Override public McpSafetyLevel getSafetyLevel() { return McpSafetyLevel.CONTROLLED_WRITE; }
        @Override public Object execute(Map<String, Object> args, McpToolContext ctx) {
            ctx.requirePermission("rule", "create");
            Rule rule = new Rule();
            rule.setId(McpToolContext.requireString(args, "id"));
            rule.setName(McpToolContext.requireString(args, "name"));
            rule.setProtocol(McpToolContext.requireString(args, "protocol"));
            rule.setHost(McpToolContext.getString(args, "host"));
            Integer port = McpToolContext.getInteger(args, "port");
            if (port != null) rule.setPort(port);
            rule.setServiceName(McpToolContext.getString(args, "serviceName"));
            Boolean enabled = McpToolContext.getBoolean(args, "enabled");
            rule.setEnabled(enabled == null ? true : enabled);
            Integer priority = McpToolContext.getInteger(args, "priority");
            rule.setPriority(priority != null ? priority : 100);
            rule.setTags(McpToolContext.getStringList(args, "tags"));
            rule.setEnvironments(McpToolContext.getStringList(args, "environments"));
            return ctx.getStorage().createRule(rule);
        }
    }

    public static class UpdateRuleTool implements McpTool {
        @Override public String getName() { return "update_rule"; }
        @Override public String getDescription() { return "更新已有的 Mock 规则。需要 developer 或 admin 权限。"; }
        @Override public String getInputSchema() {
            return McpSchemaBuilder.create()
                    .requiredString("id", "规则ID")
                    .stringProperty("name", "规则名称")
                    .stringProperty("protocol", "协议")
                    .stringProperty("host", "目标主机")
                    .integerProperty("port", "目标端口")
                    .booleanProperty("enabled", "是否启用")
                    .integerProperty("priority", "优先级")
                    .buildJson();
        }
        @Override public McpSafetyLevel getSafetyLevel() { return McpSafetyLevel.CONTROLLED_WRITE; }
        @Override public Object execute(Map<String, Object> args, McpToolContext ctx) {
            ctx.requirePermission("rule", "update");
            String id = McpToolContext.requireString(args, "id");
            Rule existing = ctx.getStorage().getRule(id);
            if (existing == null) throw new McpException(404, "Rule not found: " + id);

            String name = McpToolContext.getString(args, "name");
            if (name != null) existing.setName(name);
            String protocol = McpToolContext.getString(args, "protocol");
            if (protocol != null) existing.setProtocol(protocol);
            String host = McpToolContext.getString(args, "host");
            if (host != null) existing.setHost(host);
            Integer port = McpToolContext.getInteger(args, "port");
            if (port != null) existing.setPort(port);
            Boolean enabled = McpToolContext.getBoolean(args, "enabled");
            if (enabled != null) existing.setEnabled(enabled);
            Integer priority = McpToolContext.getInteger(args, "priority");
            if (priority != null) existing.setPriority(priority);

            Rule updated = ctx.getStorage().updateRule(id, existing);
            return updated != null ? updated : throwNotFound("Rule not found: " + id);
        }
    }

    public static class DeleteRuleTool implements McpTool {
        @Override public String getName() { return "delete_rule"; }
        @Override public String getDescription() { return "删除 Mock 规则。需要 developer 或 admin 权限。"; }
        @Override public String getInputSchema() {
            return McpSchemaBuilder.create()
                    .requiredString("id", "规则ID")
                    .buildJson();
        }
        @Override public McpSafetyLevel getSafetyLevel() { return McpSafetyLevel.AUDIT_REQUIRED; }
        @Override public Object execute(Map<String, Object> args, McpToolContext ctx) {
            ctx.requirePermission("rule", "delete");
            String id = McpToolContext.requireString(args, "id");
            boolean deleted = ctx.getStorage().deleteRule(id);
            if (!deleted) throw new McpException(404, "Rule not found: " + id);
            Map<String, Object> result = new HashMap<>();
            result.put("message", "Rule deleted");
            result.put("id", id);
            return result;
        }
    }

    public static class UndoRuleTool implements McpTool {
        @Override public String getName() { return "undo_rule"; }
        @Override public String getDescription() { return "撤销规则的最后一次修改，回滚到上一版本。"; }
        @Override public String getInputSchema() {
            return McpSchemaBuilder.create()
                    .requiredString("id", "规则ID")
                    .buildJson();
        }
        @Override public McpSafetyLevel getSafetyLevel() { return McpSafetyLevel.CONTROLLED_WRITE; }
        @Override public Object execute(Map<String, Object> args, McpToolContext ctx) {
            ctx.requirePermission("rule", "update");
            String id = McpToolContext.requireString(args, "id");
            boolean success = ctx.getStorage().undoRule(id);
            if (!success) throw new McpException(404, "Rule not found or no undo history");
            Map<String, Object> result = new HashMap<>();
            result.put("message", "Undo successful");
            result.put("id", id);
            return result;
        }
    }

    // === Helper ===

    private static Map<String, Object> formatPage(PaginatedResult<?> page) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("records", page.getItems());
        result.put("total", page.getTotal());
        result.put("page", page.getPage());
        result.put("size", page.getSize());
        result.put("totalPages", page.getTotalPages());
        return result;
    }

    private static <T> T throwNotFound(String msg) {
        throw new McpException(404, msg);
    }
}
