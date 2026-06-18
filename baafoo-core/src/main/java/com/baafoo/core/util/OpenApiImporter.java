package com.baafoo.core.util;

import com.baafoo.core.model.MatchCondition;
import com.baafoo.core.model.ResponseEntry;
import com.baafoo.core.model.Rule;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * OpenAPI 3.0 specification importer (PRD §1 R-S10 Phase 1).
 *
 * <p>Parses an OpenAPI 3.0 JSON spec and generates Baafoo {@link Rule} skeletons
 * for each path + method combination. Uses the {@code example-first} strategy:
 * if a response or schema has an {@code example}, it is used as the response body;
 * otherwise a minimal placeholder body is generated from the schema's simple
 * property types.</p>
 *
 * <p>Phase 1 limitations (per PRD):
 * <ul>
 *   <li>JSON only (no YAML — Phase 2)</li>
 *   <li>No {@code $ref} resolution (Phase 2)</li>
 *   <li>No {@code allOf}/{@code oneOf}/{@code anyOf} (Phase 2)</li>
 *   <li>Swagger 2.0 not supported (Phase 3)</li>
 * </ul>
 * </p>
 *
 * <p>Thread-safety: stateless, safe for concurrent use. A new ObjectMapper is
 * created per call (ObjectMapper is thread-safe after configuration, but creating
 * per-call avoids any shared-state concerns).</p>
 */
public final class OpenApiImporter {

    /** HTTP methods recognized in OpenAPI paths. */
    private static final List<String> HTTP_METHODS = Arrays.asList(
            "get", "post", "put", "delete", "patch", "head", "options");

    /** Pattern to find path parameters like {@code {id}} or {@code {user-id}}. */
    private static final Pattern PATH_PARAM_PATTERN = Pattern.compile("\\{[^}]+}");

    private final ObjectMapper mapper;

    public OpenApiImporter() {
        this.mapper = new ObjectMapper();
    }

    /**
     * Parse an OpenAPI 3.0 JSON spec and generate rules.
     *
     * @param jsonContent  the raw OpenAPI JSON string
     * @param ruleIdPrefix prefix for generated rule IDs (e.g. {@code "openapi-"})
     * @param environments default environments to associate with generated rules
     *                     (empty list = no environment association)
     * @return the import result with generated rules and statistics
     * @throws OpenApiImportException if the spec cannot be parsed
     */
    public OpenApiImportResult importSpec(String jsonContent, String ruleIdPrefix,
                                           List<String> environments)
            throws OpenApiImportException {

        if (jsonContent == null || jsonContent.trim().isEmpty()) {
            throw new OpenApiImportException("OpenAPI spec content is empty", 0);
        }

        JsonNode root;
        try {
            root = mapper.readTree(jsonContent);
        } catch (Exception e) {
            throw new OpenApiImportException("Failed to parse JSON: " + e.getMessage(), 0);
        }

        if (root == null || root.isMissingNode()) {
            throw new OpenApiImportException("OpenAPI spec is null or missing", 0);
        }

        // Validate OpenAPI version (3.x)
        JsonNode openapiNode = root.get("openapi");
        if (openapiNode == null || !openapiNode.isTextual()) {
            // Check for Swagger 2.0 (not supported in Phase 1)
            JsonNode swaggerNode = root.get("swagger");
            if (swaggerNode != null) {
                throw new OpenApiImportException(
                        "Swagger 2.0 is not supported in Phase 1. Please provide an OpenAPI 3.0 spec.", 0);
            }
            throw new OpenApiImportException(
                    "Missing 'openapi' field. Not a valid OpenAPI 3.0 spec.", 0);
        }
        String version = openapiNode.asText();
        if (!version.startsWith("3.")) {
            throw new OpenApiImportException(
                    "OpenAPI version " + version + " is not supported. Phase 1 supports 3.x only.", 0);
        }

        JsonNode paths = root.get("paths");
        if (paths == null || !paths.isObject()) {
            return new OpenApiImportResult(Collections.<Rule>emptyList(), 0,
                    "No 'paths' field found in OpenAPI spec");
        }

        List<Rule> generatedRules = new ArrayList<Rule>();
        int skippedCount = 0;
        List<String> warnings = new ArrayList<String>();

        Iterator<Map.Entry<String, JsonNode>> pathIter = paths.fields();
        while (pathIter.hasNext()) {
            Map.Entry<String, JsonNode> pathEntry = pathIter.next();
            String path = pathEntry.getKey();
            JsonNode pathItem = pathEntry.getValue();

            if (!pathItem.isObject()) continue;

            for (String method : HTTP_METHODS) {
                JsonNode operation = pathItem.get(method);
                if (operation == null || !operation.isObject()) continue;

                // Check if this operation has responses defined
                JsonNode responses = operation.get("responses");
                if (responses == null || responses.size() == 0) {
                    skippedCount++;
                    warnings.add("Skipped " + method.toUpperCase() + " " + path
                            + ": no responses defined");
                    continue;
                }

                Rule rule = buildRule(operation, path, method, ruleIdPrefix, environments);
                generatedRules.add(rule);
            }
        }

        String summary = String.format("Generated %d rules, skipped %d paths (no responses)",
                generatedRules.size(), skippedCount);
        return new OpenApiImportResult(generatedRules, skippedCount, summary, warnings);
    }

    private Rule buildRule(JsonNode operation, String path, String method,
                           String ruleIdPrefix, List<String> environments) {
        Rule rule = new Rule();
        String ruleId = generateRuleId(ruleIdPrefix, method, path);
        rule.setId(ruleId);

        // Rule name: operationId or "METHOD path"
        JsonNode operationIdNode = operation.get("operationId");
        String opId = operationIdNode != null && operationIdNode.isTextual()
                ? operationIdNode.asText()
                : method.toUpperCase() + " " + path;
        rule.setName(opId);

        rule.setProtocol("http");
        rule.setEnabled(true);
        rule.setPriority(100);

        // Environments
        if (environments != null && !environments.isEmpty()) {
            rule.setEnvironments(new ArrayList<String>(environments));
        } else {
            rule.setEnvironments(Collections.<String>emptyList());
        }

        // Tags from OpenAPI tags
        JsonNode tagsNode = operation.get("tags");
        if (tagsNode != null && tagsNode.isArray() && tagsNode.size() > 0) {
            List<String> tags = new ArrayList<String>();
            for (JsonNode tag : tagsNode) {
                tags.add(tag.asText());
            }
            rule.setTags(tags);
        } else {
            rule.setTags(Collections.<String>emptyList());
        }

        // Build conditions: method + path (with regex for path params)
        List<MatchCondition> conditions = new ArrayList<MatchCondition>();
        conditions.add(MatchCondition.method(method.toUpperCase()));

        // Convert path parameters {id} to regex
        String pathRegex = convertPathToRegex(path);
        if (path.contains("{") && path.contains("}")) {
            conditions.add(MatchCondition.path("regex", pathRegex));
        } else {
            conditions.add(MatchCondition.path("equals", path));
        }
        rule.setConditions(conditions);

        // Build response: extract first 2xx status code
        ResponseEntry responseEntry = buildResponseEntry(operation, path, method);
        List<ResponseEntry> responses = new ArrayList<ResponseEntry>();
        responses.add(responseEntry);
        rule.setResponses(responses);

        return rule;
    }

    /**
     * Convert an OpenAPI path with parameters to a regex pattern.
     * Example: {@code /api/users/{id}/orders/{orderId}}
     *       → {@code /api/users/[^/]+/orders/[^/]+}
     */
    String convertPathToRegex(String path) {
        Matcher m = PATH_PARAM_PATTERN.matcher(path);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(sb, "[^/]+");
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String generateRuleId(String prefix, String method, String path) {
        // Slugify: /api/users/{id} → api-users-id
        String slug = path.replaceAll("[^a-zA-Z0-9]", "-")
                .replaceAll("-+", "-")
                .replaceAll("^-|-$", "");
        if (slug.isEmpty()) slug = "root";
        return prefix + method + "-" + slug;
    }

    private ResponseEntry buildResponseEntry(JsonNode operation, String path, String method) {
        ResponseEntry entry = new ResponseEntry();
        JsonNode responses = operation.get("responses");

        // Find first 2xx status code (prefer 200, then any 2xx)
        String statusCode = findFirstSuccessStatus(responses);
        if (statusCode != null) {
            try {
                entry.setStatusCode(Integer.parseInt(statusCode));
            } catch (NumberFormatException e) {
                entry.setStatusCode(200);
            }
        } else {
            entry.setStatusCode(200);
        }

        entry.setName(method.toUpperCase() + " " + path);

        // Generate response body using example-first strategy
        JsonNode responseDef = responses.get(statusCode);
        String body = generateResponseBody(responseDef);
        entry.setBody(body);

        return entry;
    }

    /**
     * Find the first 2xx status code in the responses object.
     * Prefers "200" if present, then any status starting with "2".
     */
    private String findFirstSuccessStatus(JsonNode responses) {
        if (responses == null) return null;

        // Prefer 200
        if (responses.has("200")) return "200";

        // Then any 2xx
        Iterator<String> statusIter = responses.fieldNames();
        while (statusIter.hasNext()) {
            String status = statusIter.next();
            if (status.startsWith("2") && status.length() == 3) {
                return status;
            }
        }

        // Fall back to default
        if (responses.has("default")) return "default";

        // Fall back to first available
        statusIter = responses.fieldNames();
        if (statusIter.hasNext()) return statusIter.next();

        return null;
    }

    /**
     * Generate response body using example-first strategy (AC-04).
     *
     * <p>Strategy:
     * <ol>
     *   <li>If response has {@code example} → use it (JSON-serialized)</li>
     *   <li>If response has {@code content.application/json.example} → use it</li>
     *   <li>If schema has {@code example} → use it</li>
     *   <li>If schema is {@code type: object} with simple properties → generate
     *       placeholder JSON with type-based defaults</li>
     *   <li>Otherwise → empty string</li>
     * </ol>
     * </p>
     */
    String generateResponseBody(JsonNode responseDef) {
        if (responseDef == null) return "";

        // 1. Response-level example
        JsonNode example = responseDef.get("example");
        if (example != null && !example.isNull()) {
            return nodeToJson(example);
        }

        // 2. Content → application/json → example
        JsonNode content = responseDef.get("content");
        if (content != null) {
            JsonNode jsonContent = content.get("application/json");
            if (jsonContent != null) {
                JsonNode contentExample = jsonContent.get("example");
                if (contentExample != null && !contentExample.isNull()) {
                    return nodeToJson(contentExample);
                }
                // 3. Schema-level example
                JsonNode schema = jsonContent.get("schema");
                if (schema != null) {
                    JsonNode schemaExample = schema.get("example");
                    if (schemaExample != null && !schemaExample.isNull()) {
                        return nodeToJson(schemaExample);
                    }
                    // 4. Generate from schema
                    return generateFromSchema(schema);
                }
            }
        }

        // 3. Direct schema (OpenAPI 3.0 allows schema at response level in some contexts)
        JsonNode schema = responseDef.get("schema");
        if (schema != null) {
            JsonNode schemaExample = schema.get("example");
            if (schemaExample != null && !schemaExample.isNull()) {
                return nodeToJson(schemaExample);
            }
            return generateFromSchema(schema);
        }

        return "";
    }

    /**
     * Generate a placeholder JSON body from a schema definition.
     *
     * <p>Handles {@code type: object} with simple {@code properties}. Complex
     * types ({@code $ref}, {@code allOf}, {@code oneOf}, {@code anyOf}) are
     * deferred to Phase 2 and result in an empty object.</p>
     */
    @SuppressWarnings("unchecked")
    String generateFromSchema(JsonNode schema) {
        if (schema == null || schema.isNull()) return "";

        // Check for $ref / allOf / oneOf / anyOf — not supported in Phase 1
        if (schema.has("$ref") || schema.has("allOf")
                || schema.has("oneOf") || schema.has("anyOf")) {
            return "{}";
        }

        String type = schema.has("type") ? schema.get("type").asText() : "object";

        // If schema has an example, use it
        JsonNode example = schema.get("example");
        if (example != null && !example.isNull()) {
            return nodeToJson(example);
        }

        // If schema has a default, use it
        JsonNode defaultValue = schema.get("default");
        if (defaultValue != null && !defaultValue.isNull()) {
            return nodeToJson(defaultValue);
        }

        if ("object".equals(type)) {
            JsonNode properties = schema.get("properties");
            if (properties == null || !properties.isObject()) {
                return "{}";
            }
            Map<String, Object> placeholder = new LinkedHashMap<String, Object>();
            Iterator<Map.Entry<String, JsonNode>> propIter = properties.fields();
            while (propIter.hasNext()) {
                Map.Entry<String, JsonNode> prop = propIter.next();
                placeholder.put(prop.getKey(), generatePlaceholderValue(prop.getValue()));
            }
            return nodeToJson(mapper.valueToTree(placeholder));
        }

        if ("array".equals(type)) {
            JsonNode items = schema.get("items");
            if (items != null) {
                Object itemValue = generatePlaceholderValue(items);
                List<Object> arr = new ArrayList<Object>();
                arr.add(itemValue);
                return nodeToJson(mapper.valueToTree(arr));
            }
            return "[]";
        }

        // Primitive types
        Object placeholder = generatePlaceholderValue(schema);
        return nodeToJson(mapper.valueToTree(placeholder));
    }

    /**
     * Generate a placeholder value for a single schema property.
     */
    private Object generatePlaceholderValue(JsonNode schema) {
        if (schema == null || schema.isNull()) return null;

        // Check for example/default first
        JsonNode example = schema.get("example");
        if (example != null && !example.isNull()) {
            return jsonNodeToObject(example);
        }
        JsonNode defaultValue = schema.get("default");
        if (defaultValue != null && !defaultValue.isNull()) {
            return jsonNodeToObject(defaultValue);
        }

        // Check for $ref / composition keywords — not supported
        if (schema.has("$ref") || schema.has("allOf")
                || schema.has("oneOf") || schema.has("anyOf")) {
            return null;
        }

        String type = schema.has("type") ? schema.get("type").asText() : "string";
        String format = schema.has("format") ? schema.get("format").asText() : "";

        switch (type) {
            case "string":
                if ("date".equals(format)) return "2024-01-01";
                if ("date-time".equals(format)) return "2024-01-01T00:00:00Z";
                if ("email".equals(format)) return "user@example.com";
                if ("uuid".equals(format)) return "00000000-0000-0000-0000-000000000000";
                if ("uri".equals(format)) return "https://example.com";
                return "string";
            case "integer":
            case "number":
                return 0;
            case "boolean":
                return false;
            case "array":
                JsonNode items = schema.get("items");
                if (items != null) {
                    Object itemValue = generatePlaceholderValue(items);
                    List<Object> arr = new ArrayList<Object>();
                    arr.add(itemValue);
                    return arr;
                }
                return Collections.emptyList();
            case "object":
                JsonNode properties = schema.get("properties");
                if (properties != null && properties.isObject()) {
                    Map<String, Object> obj = new LinkedHashMap<String, Object>();
                    Iterator<Map.Entry<String, JsonNode>> propIter = properties.fields();
                    while (propIter.hasNext()) {
                        Map.Entry<String, JsonNode> prop = propIter.next();
                        obj.put(prop.getKey(), generatePlaceholderValue(prop.getValue()));
                    }
                    return obj;
                }
                return Collections.emptyMap();
            default:
                return null;
        }
    }

    /**
     * Convert a JsonNode to its JSON string representation.
     */
    private String nodeToJson(JsonNode node) {
        try {
            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            return node.toString();
        }
    }

    /**
     * Convert a JsonNode to a Java object for placeholder building.
     */
    private Object jsonNodeToObject(JsonNode node) {
        if (node == null || node.isNull()) return null;
        try {
            return mapper.treeToValue(node, Object.class);
        } catch (Exception e) {
            return node.toString();
        }
    }

    // ===== Result classes =====

    /**
     * Result of an OpenAPI import operation.
     */
    public static class OpenApiImportResult {
        private final List<Rule> rules;
        private final int skippedCount;
        private final String summary;
        private final List<String> warnings;

        public OpenApiImportResult(List<Rule> rules, int skippedCount, String summary) {
            this(rules, skippedCount, summary, Collections.<String>emptyList());
        }

        public OpenApiImportResult(List<Rule> rules, int skippedCount, String summary,
                                    List<String> warnings) {
            this.rules = rules;
            this.skippedCount = skippedCount;
            this.summary = summary;
            this.warnings = warnings != null ? warnings : Collections.<String>emptyList();
        }

        public List<Rule> getRules() { return rules; }
        public int getSkippedCount() { return skippedCount; }
        public String getSummary() { return summary; }
        public List<String> getWarnings() { return warnings; }

        /** Number of rules successfully generated. */
        public int getGeneratedCount() { return rules != null ? rules.size() : 0; }
    }

    /**
     * Exception thrown when an OpenAPI spec cannot be parsed or is invalid.
     */
    public static class OpenApiImportException extends Exception {
        private static final long serialVersionUID = 1L;

        public OpenApiImportException(String message, int line) {
            super(line > 0 ? message + " (line " + line + ")" : message);
        }
    }
}
