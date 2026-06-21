package com.baafoo.server.mcp;

import java.util.*;

/**
 * Builder for JSON Schema (input schema for MCP tools).
 */
public class McpSchemaBuilder {

    private final Map<String, Object> schema = new LinkedHashMap<>();
    private final Map<String, Object> properties = new LinkedHashMap<>();
    private final List<String> required = new ArrayList<>();

    private McpSchemaBuilder() {
        schema.put("type", "object");
    }

    public static McpSchemaBuilder create() {
        return new McpSchemaBuilder();
    }

    public McpSchemaBuilder property(String name, String type, String description) {
        Map<String, Object> prop = new LinkedHashMap<>();
        prop.put("type", type);
        prop.put("description", description);
        properties.put(name, prop);
        return this;
    }

    public McpSchemaBuilder requiredProperty(String name, String type, String description) {
        property(name, type, description);
        required.add(name);
        return this;
    }

    public McpSchemaBuilder integerProperty(String name, String description) {
        return property(name, "integer", description);
    }

    public McpSchemaBuilder requiredInteger(String name, String description) {
        return requiredProperty(name, "integer", description);
    }

    public McpSchemaBuilder stringProperty(String name, String description) {
        return property(name, "string", description);
    }

    public McpSchemaBuilder requiredString(String name, String description) {
        return requiredProperty(name, "string", description);
    }

    public McpSchemaBuilder booleanProperty(String name, String description) {
        return property(name, "boolean", description);
    }

    public McpSchemaBuilder requiredBoolean(String name, String description) {
        return requiredProperty(name, "boolean", description);
    }

    public McpSchemaBuilder enumProperty(String name, String description, String... values) {
        Map<String, Object> prop = new LinkedHashMap<>();
        prop.put("type", "string");
        prop.put("description", description);
        prop.put("enum", Arrays.asList(values));
        properties.put(name, prop);
        return this;
    }

    public McpSchemaBuilder arrayProperty(String name, String description, String itemType) {
        Map<String, Object> prop = new LinkedHashMap<>();
        prop.put("type", "array");
        prop.put("description", description);
        Map<String, Object> items = new LinkedHashMap<>();
        items.put("type", itemType);
        prop.put("items", items);
        properties.put(name, prop);
        return this;
    }

    public String buildJson() {
        schema.put("properties", properties);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        return toJsonString(schema);
    }

    private String toJsonString(Object obj) {
        StringBuilder sb = new StringBuilder();
        writeJson(sb, obj);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private void writeJson(StringBuilder sb, Object obj) {
        if (obj == null) {
            sb.append("null");
        } else if (obj instanceof String) {
            sb.append("\"").append(escapeJson((String) obj)).append("\"");
        } else if (obj instanceof Number || obj instanceof Boolean) {
            sb.append(obj);
        } else if (obj instanceof List) {
            sb.append("[");
            boolean first = true;
            for (Object item : (List<?>) obj) {
                if (!first) sb.append(",");
                writeJson(sb, item);
                first = false;
            }
            sb.append("]");
        } else if (obj instanceof Map) {
            sb.append("{");
            boolean first = true;
            for (Map.Entry<String, Object> entry : ((Map<String, Object>) obj).entrySet()) {
                if (!first) sb.append(",");
                sb.append("\"").append(escapeJson(entry.getKey())).append("\":");
                writeJson(sb, entry.getValue());
                first = false;
            }
            sb.append("}");
        }
    }

    private String escapeJson(String s) {
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
    }
}
