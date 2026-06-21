package com.baafoo.server.mcp;

import com.baafoo.server.mcp.tools.*;

import java.util.*;

/**
 * Registry that holds all MCP tools.
 */
public class McpToolRegistry {

    public static List<McpTool> createAllTools() {
        List<McpTool> tools = new ArrayList<>();

        // Rule tools
        tools.add(new RuleTools.ListRulesTool());
        tools.add(new RuleTools.GetRuleTool());
        tools.add(new RuleTools.CreateRuleTool());
        tools.add(new RuleTools.UpdateRuleTool());
        tools.add(new RuleTools.DeleteRuleTool());
        tools.add(new RuleTools.UndoRuleTool());

        // Environment tools
        tools.add(new EnvironmentTools.ListEnvironmentsTool());
        tools.add(new EnvironmentTools.GetEnvironmentTool());
        tools.add(new EnvironmentTools.CreateEnvironmentTool());
        tools.add(new EnvironmentTools.UpdateEnvironmentTool());
        tools.add(new EnvironmentTools.DeleteEnvironmentTool());
        tools.add(new EnvironmentTools.AssociateRulesTool());

        // Scene tools
        tools.add(new SceneTools.ListScenesTool());
        tools.add(new SceneTools.GetSceneTool());
        tools.add(new SceneTools.CreateSceneTool());
        tools.add(new SceneTools.UpdateSceneTool());
        tools.add(new SceneTools.DeleteSceneTool());

        // Recording tools
        tools.add(new RecordingTools.ListRecordingsTool());
        tools.add(new RecordingTools.GetRecordingStatsTool());
        tools.add(new RecordingTools.DeleteRecordingTool());

        // MQ relationship tools
        tools.add(new MqRelationshipTools.ListMqRelationshipsTool());
        tools.add(new MqRelationshipTools.CreateMqRelationshipTool());
        tools.add(new MqRelationshipTools.DeleteMqRelationshipTool());

        // Agent tools
        tools.add(new AgentTools.ListAgentsTool());
        tools.add(new AgentTools.GetAgentTool());

        // System tools
        tools.add(new SystemTools.GetSystemStatusTool());
        tools.add(new SystemTools.ExportOpenApiTool());

        return Collections.unmodifiableList(tools);
    }
}
