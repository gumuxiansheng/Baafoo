package com.baafoo.server.mcp.tools;

import com.baafoo.core.model.SceneSet;
import com.baafoo.server.mcp.*;

import java.util.*;

/**
 * Scene set management MCP tools.
 */
public class SceneTools {

    public static class ListScenesTool implements McpTool {
        @Override public String getName() { return "list_scenes"; }
        @Override public String getDescription() { return "列出所有场景集。场景集是一组规则的集合，可批量启用/禁用。"; }
        @Override public String getInputSchema() {
            return McpSchemaBuilder.create().buildJson();
        }
        @Override public McpSafetyLevel getSafetyLevel() { return McpSafetyLevel.READ_ONLY; }
        @Override public Object execute(Map<String, Object> args, McpToolContext ctx) {
            return ctx.getStorage().listScenes();
        }
    }

    public static class GetSceneTool implements McpTool {
        @Override public String getName() { return "get_scene"; }
        @Override public String getDescription() { return "获取指定场景集的详细信息。"; }
        @Override public String getInputSchema() {
            return McpSchemaBuilder.create()
                    .requiredString("id", "场景集ID")
                    .buildJson();
        }
        @Override public McpSafetyLevel getSafetyLevel() { return McpSafetyLevel.READ_ONLY; }
        @Override public Object execute(Map<String, Object> args, McpToolContext ctx) {
            String id = McpToolContext.requireString(args, "id");
            SceneSet scene = ctx.getStorage().getScene(id);
            if (scene == null) throw new McpException(404, "Scene not found: " + id);
            return scene;
        }
    }

    public static class CreateSceneTool implements McpTool {
        @Override public String getName() { return "create_scene"; }
        @Override public String getDescription() { return "创建场景集。需要 developer 或 tester 权限。"; }
        @Override public String getInputSchema() {
            return McpSchemaBuilder.create()
                    .requiredString("id", "场景集ID")
                    .requiredString("name", "场景集名称")
                    .stringProperty("description", "场景描述")
                    .arrayProperty("itemIds", "规则/规则集ID列表", "string")
                    .booleanProperty("active", "是否激活")
                    .arrayProperty("tags", "标签", "string")
                    .arrayProperty("environments", "生效环境", "string")
                    .buildJson();
        }
        @Override public McpSafetyLevel getSafetyLevel() { return McpSafetyLevel.CONTROLLED_WRITE; }
        @Override public Object execute(Map<String, Object> args, McpToolContext ctx) {
            ctx.requirePermission("scene", "create");
            SceneSet scene = new SceneSet();
            scene.setId(McpToolContext.requireString(args, "id"));
            scene.setName(McpToolContext.requireString(args, "name"));
            scene.setDescription(McpToolContext.getString(args, "description"));
            scene.setItemIds(McpToolContext.getStringList(args, "itemIds"));
            Boolean active = McpToolContext.getBoolean(args, "active");
            scene.setActive(active != null && active);
            scene.setTags(McpToolContext.getStringList(args, "tags"));
            scene.setEnvironments(McpToolContext.getStringList(args, "environments"));
            return ctx.getStorage().createScene(scene);
        }
    }

    public static class UpdateSceneTool implements McpTool {
        @Override public String getName() { return "update_scene"; }
        @Override public String getDescription() { return "更新场景集。需要 developer 或 tester 权限。"; }
        @Override public String getInputSchema() {
            return McpSchemaBuilder.create()
                    .requiredString("id", "场景集ID")
                    .stringProperty("name", "场景集名称")
                    .stringProperty("description", "场景描述")
                    .booleanProperty("active", "是否激活")
                    .buildJson();
        }
        @Override public McpSafetyLevel getSafetyLevel() { return McpSafetyLevel.CONTROLLED_WRITE; }
        @Override public Object execute(Map<String, Object> args, McpToolContext ctx) {
            ctx.requirePermission("scene", "update");
            String id = McpToolContext.requireString(args, "id");
            SceneSet existing = ctx.getStorage().getScene(id);
            if (existing == null) throw new McpException(404, "Scene not found: " + id);

            String name = McpToolContext.getString(args, "name");
            if (name != null) existing.setName(name);
            String desc = McpToolContext.getString(args, "description");
            if (desc != null) existing.setDescription(desc);
            Boolean active = McpToolContext.getBoolean(args, "active");
            if (active != null) existing.setActive(active);

            SceneSet updated = ctx.getStorage().updateScene(id, existing);
            return updated != null ? updated : throwNotFound("Scene not found: " + id);
        }
    }

    public static class DeleteSceneTool implements McpTool {
        @Override public String getName() { return "delete_scene"; }
        @Override public String getDescription() { return "删除场景集。需要 developer 或 tester 权限。"; }
        @Override public String getInputSchema() {
            return McpSchemaBuilder.create()
                    .requiredString("id", "场景集ID")
                    .buildJson();
        }
        @Override public McpSafetyLevel getSafetyLevel() { return McpSafetyLevel.AUDIT_REQUIRED; }
        @Override public Object execute(Map<String, Object> args, McpToolContext ctx) {
            ctx.requirePermission("scene", "delete");
            String id = McpToolContext.requireString(args, "id");
            boolean deleted = ctx.getStorage().deleteScene(id);
            if (!deleted) throw new McpException(404, "Scene not found: " + id);
            Map<String, Object> result = new HashMap<>();
            result.put("message", "Scene deleted");
            result.put("id", id);
            return result;
        }
    }

    private static <T> T throwNotFound(String msg) {
        throw new McpException(404, msg);
    }
}
