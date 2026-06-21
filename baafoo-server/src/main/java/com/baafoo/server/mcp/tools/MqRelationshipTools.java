package com.baafoo.server.mcp.tools;

import com.baafoo.core.model.MqRelationship;
import com.baafoo.server.mcp.*;

import java.util.*;

/**
 * MQ relationship management MCP tools.
 */
public class MqRelationshipTools {

    public static class ListMqRelationshipsTool implements McpTool {
        @Override public String getName() { return "list_mq_relationships"; }
        @Override public String getDescription() { return "列出所有消息队列关系映射（Kafka/Pulsar/JMS 之间的消息流转关系）。"; }
        @Override public String getInputSchema() {
            return McpSchemaBuilder.create().buildJson();
        }
        @Override public McpSafetyLevel getSafetyLevel() { return McpSafetyLevel.READ_ONLY; }
        @Override public Object execute(Map<String, Object> args, McpToolContext ctx) {
            return ctx.getStorage().listMqRelationships();
        }
    }

    public static class CreateMqRelationshipTool implements McpTool {
        @Override public String getName() { return "create_mq_relationship"; }
        @Override public String getDescription() { return "创建消息队列关系映射。需要 developer 权限。"; }
        @Override public String getInputSchema() {
            return McpSchemaBuilder.create()
                    .requiredString("fromProtocol", "源协议（kafka/pulsar/jms）")
                    .requiredString("fromTopic", "源Topic")
                    .requiredString("toProtocol", "目标协议")
                    .requiredString("toTopic", "目标Topic")
                    .stringProperty("name", "关系名称（可选）")
                    .buildJson();
        }
        @Override public McpSafetyLevel getSafetyLevel() { return McpSafetyLevel.CONTROLLED_WRITE; }
        @Override public Object execute(Map<String, Object> args, McpToolContext ctx) {
            ctx.requirePermission("mq-relationship", "create");
            MqRelationship rel = new MqRelationship();
            rel.setFromProtocol(McpToolContext.requireString(args, "fromProtocol"));
            rel.setFromTopic(McpToolContext.requireString(args, "fromTopic"));
            rel.setToProtocol(McpToolContext.requireString(args, "toProtocol"));
            rel.setToTopic(McpToolContext.requireString(args, "toTopic"));
            String relName = McpToolContext.getString(args, "name");
            if (relName != null) rel.setName(relName);
            return ctx.getStorage().createMqRelationship(rel);
        }
    }

    public static class DeleteMqRelationshipTool implements McpTool {
        @Override public String getName() { return "delete_mq_relationship"; }
        @Override public String getDescription() { return "删除消息队列关系映射。需要 developer 权限。"; }
        @Override public String getInputSchema() {
            return McpSchemaBuilder.create()
                    .requiredString("id", "关系映射ID")
                    .buildJson();
        }
        @Override public McpSafetyLevel getSafetyLevel() { return McpSafetyLevel.AUDIT_REQUIRED; }
        @Override public Object execute(Map<String, Object> args, McpToolContext ctx) {
            ctx.requirePermission("mq-relationship", "delete");
            String id = McpToolContext.requireString(args, "id");
            boolean deleted = ctx.getStorage().deleteMqRelationship(id);
            if (!deleted) throw new McpException(404, "MQ relationship not found: " + id);
            Map<String, Object> result = new HashMap<>();
            result.put("message", "MQ relationship deleted");
            result.put("id", id);
            return result;
        }
    }
}
