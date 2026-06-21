package com.baafoo.server.mcp.tools;

import com.baafoo.core.model.Environment;
import com.baafoo.core.model.EnvironmentMode;
import com.baafoo.server.mcp.*;

import java.util.*;

/**
 * Environment management MCP tools.
 */
public class EnvironmentTools {

    public static class ListEnvironmentsTool implements McpTool {
        @Override public String getName() { return "list_environments"; }
        @Override public String getDescription() { return "列出所有环境配置。返回环境ID、名称、模式、描述等信息。"; }
        @Override public String getInputSchema() {
            return McpSchemaBuilder.create().buildJson();
        }
        @Override public McpSafetyLevel getSafetyLevel() { return McpSafetyLevel.READ_ONLY; }
        @Override public Object execute(Map<String, Object> args, McpToolContext ctx) {
            return ctx.getStorage().listEnvironments();
        }
    }

    public static class GetEnvironmentTool implements McpTool {
        @Override public String getName() { return "get_environment"; }
        @Override public String getDescription() { return "获取指定环境的详细信息。"; }
        @Override public String getInputSchema() {
            return McpSchemaBuilder.create()
                    .requiredString("id", "环境ID")
                    .buildJson();
        }
        @Override public McpSafetyLevel getSafetyLevel() { return McpSafetyLevel.READ_ONLY; }
        @Override public Object execute(Map<String, Object> args, McpToolContext ctx) {
            String id = McpToolContext.requireString(args, "id");
            Environment env = ctx.getStorage().getEnvironment(id);
            if (env == null) throw new McpException(404, "Environment not found: " + id);
            return env;
        }
    }

    public static class CreateEnvironmentTool implements McpTool {
        @Override public String getName() { return "create_environment"; }
        @Override public String getDescription() { return "创建新环境。需要 admin 权限。环境模式：STUB（仅Mock）、RECORD（仅录制）、RECORD_AND_STUB（录制+Mock）、PASSTHROUGH（透传）。"; }
        @Override public String getInputSchema() {
            return McpSchemaBuilder.create()
                    .requiredString("id", "环境ID（唯一）")
                    .requiredString("name", "环境名称（如 dev/staging/prod）")
                    .stringProperty("description", "环境描述")
                    .enumProperty("mode", "环境模式", "STUB", "PASSTHROUGH", "RECORD", "RECORD_AND_STUB", "RECORD_ALL")
                    .buildJson();
        }
        @Override public McpSafetyLevel getSafetyLevel() { return McpSafetyLevel.CONTROLLED_WRITE; }
        @Override public Object execute(Map<String, Object> args, McpToolContext ctx) {
            ctx.requireAdmin(); // Only admin can create environments
            Environment env = new Environment();
            env.setId(McpToolContext.requireString(args, "id"));
            env.setName(McpToolContext.requireString(args, "name"));
            env.setDescription(McpToolContext.getString(args, "description"));
            String modeStr = McpToolContext.getString(args, "mode");
            if (modeStr != null) {
                env.setMode(EnvironmentMode.valueOf(modeStr));
            }
            return ctx.getStorage().createEnvironment(env);
        }
    }

    public static class UpdateEnvironmentTool implements McpTool {
        @Override public String getName() { return "update_environment"; }
        @Override public String getDescription() { return "更新环境配置。需要 admin 权限。"; }
        @Override public String getInputSchema() {
            return McpSchemaBuilder.create()
                    .requiredString("id", "环境ID")
                    .stringProperty("name", "环境名称")
                    .stringProperty("description", "环境描述")
                    .enumProperty("mode", "环境模式", "STUB", "PASSTHROUGH", "RECORD", "RECORD_AND_STUB", "RECORD_ALL")
                    .buildJson();
        }
        @Override public McpSafetyLevel getSafetyLevel() { return McpSafetyLevel.CONTROLLED_WRITE; }
        @Override public Object execute(Map<String, Object> args, McpToolContext ctx) {
            ctx.requireAdmin();
            String id = McpToolContext.requireString(args, "id");
            Environment existing = ctx.getStorage().getEnvironment(id);
            if (existing == null) throw new McpException(404, "Environment not found: " + id);

            String name = McpToolContext.getString(args, "name");
            if (name != null) existing.setName(name);
            String desc = McpToolContext.getString(args, "description");
            if (desc != null) existing.setDescription(desc);
            String modeStr = McpToolContext.getString(args, "mode");
            if (modeStr != null) existing.setMode(EnvironmentMode.valueOf(modeStr));

            Environment updated = ctx.getStorage().updateEnvironment(id, existing);
            return updated != null ? updated : throwNotFound("Environment not found: " + id);
        }
    }

    public static class DeleteEnvironmentTool implements McpTool {
        @Override public String getName() { return "delete_environment"; }
        @Override public String getDescription() { return "删除环境。需要 admin 权限。此操作不可逆。"; }
        @Override public String getInputSchema() {
            return McpSchemaBuilder.create()
                    .requiredString("id", "环境ID")
                    .buildJson();
        }
        @Override public McpSafetyLevel getSafetyLevel() { return McpSafetyLevel.AUDIT_REQUIRED; }
        @Override public Object execute(Map<String, Object> args, McpToolContext ctx) {
            ctx.requireAdmin();
            String id = McpToolContext.requireString(args, "id");
            boolean deleted = ctx.getStorage().deleteEnvironment(id);
            if (!deleted) throw new McpException(404, "Environment not found: " + id);
            Map<String, Object> result = new HashMap<>();
            result.put("message", "Environment deleted");
            result.put("id", id);
            return result;
        }
    }

    public static class AssociateRulesTool implements McpTool {
        @Override public String getName() { return "associate_rules"; }
        @Override public String getDescription() { return "将规则关联到环境（批量操作）。需要 admin 权限。"; }
        @Override public String getInputSchema() {
            return McpSchemaBuilder.create()
                    .requiredString("environmentId", "环境ID")
                    .arrayProperty("ruleIds", "规则ID列表", "string")
                    .buildJson();
        }
        @Override public McpSafetyLevel getSafetyLevel() { return McpSafetyLevel.CONTROLLED_WRITE; }
        @Override public Object execute(Map<String, Object> args, McpToolContext ctx) {
            ctx.requirePermission("environment", "associate");
            String envId = McpToolContext.requireString(args, "environmentId");
            List<String> ruleIds = McpToolContext.getStringList(args, "ruleIds");
            Environment env = ctx.getStorage().getEnvironment(envId);
            if (env == null) throw new McpException(404, "Environment not found: " + envId);
            ctx.getStorage().associateRulesToEnvironment(env.getName(), ruleIds);
            Map<String, Object> result = new HashMap<>();
            result.put("message", "Associated " + ruleIds.size() + " rules to " + env.getName());
            return result;
        }
    }

    private static <T> T throwNotFound(String msg) {
        throw new McpException(404, msg);
    }
}
