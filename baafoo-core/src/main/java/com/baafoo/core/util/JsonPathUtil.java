package com.baafoo.core.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Lightweight JSON path extraction utility.
 *
 * <p>Supports a subset of JSONPath syntax sufficient for Baafoo's matching
 * and templating needs:
 * <ul>
 *   <li>Dot-notation field access: {@code user.address.city}</li>
 *   <li>Optional leading {@code $} root marker: {@code $.user.name}</li>
 *   <li>Array index access: {@code items[0]}</li>
 * </ul>
 * </p>
 *
 * <p>This is intentionally NOT a full JSONPath implementation — it avoids
 * pulling in a heavyweight dependency like Jayway json-path. Filters
 * ({@code [?(@.age > 18)]}), wildcards ({@code [*]}), and recursive
 * descent ({@code ..}) are not supported.</p>
 *
 * <p>Used by both {@link TemplateEngine} (for {@code {{request.body.xxx}}}
 * variable substitution) and {@link MatchEngine} (for {@code bodyJsonPath}
 * condition matching and GraphQL syntactic-sugar conditions).</p>
 */
public final class JsonPathUtil {

    private static final Logger log = LoggerFactory.getLogger(JsonPathUtil.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonPathUtil() {
    }

    /**
     * Extract a nested field from a JSON body.
     *
     * @param body     the raw JSON body string (must not be null/empty)
     * @param jsonPath dot-notation path, optionally prefixed with {@code $.}
     *                 (e.g. {@code $.operationName} or {@code user.address.city})
     * @return the extracted value as a string. For scalar nodes returns the
     *         scalar text; for object/array nodes returns the JSON serialization;
     *         returns empty string if the body is null/empty, the path is null/empty,
     *         the body fails to parse, or the path does not resolve.
     */
    public static String extract(String body, String jsonPath) {
        if (body == null || body.isEmpty() || jsonPath == null || jsonPath.isEmpty()) {
            return "";
        }

        String normalizedPath = normalizePath(jsonPath);
        if (normalizedPath.isEmpty()) {
            // path was just "$" — return the whole body
            return body;
        }

        try {
            JsonNode node = MAPPER.readTree(body);
            String[] parts = normalizedPath.split("\\.");
            for (String part : parts) {
                if (part.isEmpty()) {
                    continue;
                }
                node = resolvePart(node, part);
                if (node == null || node.isMissingNode()) {
                    return "";
                }
            }

            if (node.isValueNode()) {
                // Treat JSON null as missing — return empty string, not "null"
                if (node.isNull()) {
                    return "";
                }
                return node.asText();
            }
            // For object/array nodes, return the JSON string
            return node.toString();
        } catch (Exception e) {
            log.debug("Failed to extract JSON path '{}' from body: {}", jsonPath, e.getMessage());
            return "";
        }
    }

    /**
     * Check whether a JSON body contains a value at the given path.
     *
     * @param body     the raw JSON body string
     * @param jsonPath dot-notation path, optionally prefixed with {@code $.}
     * @return true if the path resolves to a non-missing node
     */
    public static boolean exists(String body, String jsonPath) {
        if (body == null || body.isEmpty() || jsonPath == null || jsonPath.isEmpty()) {
            return false;
        }
        String value = extract(body, jsonPath);
        // extract returns "" for both missing paths AND present-but-empty-string values.
        // To distinguish, re-parse and check node presence directly.
        try {
            JsonNode root = MAPPER.readTree(body);
            JsonNode node = navigate(root, normalizePath(jsonPath));
            return node != null && !node.isMissingNode() && !node.isNull();
        } catch (Exception e) {
            return false;
        }
    }

    private static String normalizePath(String jsonPath) {
        String path = jsonPath.trim();
        // Strip leading "$" or "$."
        if (path.startsWith("$")) {
            path = path.substring(1);
            if (path.startsWith(".")) {
                path = path.substring(1);
            }
        }
        return path.trim();
    }

    private static JsonNode resolvePart(JsonNode node, String part) {
        // Handle array index: items[0]
        if (part.contains("[")) {
            int bracketIdx = part.indexOf('[');
            String arrayField = part.substring(0, bracketIdx);
            if (!arrayField.isEmpty()) {
                if (!node.has(arrayField)) {
                    return null;
                }
                node = node.get(arrayField);
            }
            // Extract index — supports a single [n] suffix per part
            int closeIdx = part.indexOf(']', bracketIdx);
            if (closeIdx == -1) {
                return null;
            }
            String indexStr = part.substring(bracketIdx + 1, closeIdx).trim();
            try {
                int idx = Integer.parseInt(indexStr);
                if (node.isArray() && idx >= 0 && idx < node.size()) {
                    return node.get(idx);
                }
                return null;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        // Plain field
        if (node.has(part)) {
            return node.get(part);
        }
        return null;
    }

    private static JsonNode navigate(JsonNode root, String normalizedPath) {
        if (normalizedPath.isEmpty()) {
            return root;
        }
        JsonNode node = root;
        String[] parts = normalizedPath.split("\\.");
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            node = resolvePart(node, part);
            if (node == null || node.isMissingNode()) {
                return null;
            }
        }
        return node;
    }
}
