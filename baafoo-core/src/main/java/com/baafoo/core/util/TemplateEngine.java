package com.baafoo.core.util;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Template engine for response body rendering.
 *
 * <p>Supports the following variable expressions in body templates:
 * <ul>
 *   <li>{@code {{request.body.xxx}}} — extract field from request JSON body</li>
 *   <li>{@code {{request.header.xxx}}} — request header value</li>
 *   <li>{@code {{request.query.xxx}}} — query parameter value</li>
 *   <li>{@code {{request.path}}} — request path</li>
 *   <li>{@code {{request.method}}} — HTTP method</li>
 *   <li>{@code {{faker.phone}}} / {@code {{faker.email}}} / etc. — dynamic fake data</li>
 * </ul>
 * </p>
 *
 * <p>Variables are resolved on every request, so faker functions produce
 * different values each time (useful for generating unique test data).</p>
 */
public class TemplateEngine {

    private static final Logger log = LoggerFactory.getLogger(TemplateEngine.class);

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** Pattern: {{variable.expression}} */
    private static final Pattern TEMPLATE_PATTERN = Pattern.compile("\\{\\{([\\w.]+)}}");

    /**
     * Render a response body template by substituting all template variables.
     *
     * @param template    raw body template (may contain {{...}} expressions)
     * @param context     request context providing request.* variables
     * @return rendered body string with all variables resolved
     */
    public static String render(String template, RequestContext context) {
        if (template == null || template.isEmpty()) {
            return template;
        }
        if (!template.contains("{{")) {
            return template;
        }

        Matcher matcher = TEMPLATE_PATTERN.matcher(template);
        StringBuffer sb = new StringBuffer(template.length() + 128);

        while (matcher.find()) {
            String expression = matcher.group(1);
            String replacement = resolveExpression(expression, context);
            // Use Matcher.quoteReplacement to handle $ and \ in replacement
            matcher.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(sb);

        return sb.toString();
    }

    /**
     * Resolve a single template expression.
     */
    private static String resolveExpression(String expression, RequestContext context) {
        if (expression == null || expression.isEmpty()) {
            return "";
        }

        // faker.* — dynamic fake data
        if (expression.startsWith("faker.")) {
            String funcName = expression.substring(6);
            return FakerProvider.resolve(funcName);
        }

        // request.* — request context variables
        if (expression.startsWith("request.")) {
            return resolveRequestExpression(expression, context);
        }

        // Unknown prefix — return as-is
        log.debug("Unknown template variable: {}", expression);
        return "{{" + expression + "}}";
    }

    /**
     * Resolve request.* expressions.
     */
    private static String resolveRequestExpression(String expression, RequestContext context) {
        if (context == null) {
            return "";
        }

        String field = expression.substring(8); // strip "request."

        switch (field) {
            case "method":
                return nullToEmpty(context.getMethod());
            case "path":
                return nullToEmpty(context.getPath());
            case "host":
                return nullToEmpty(context.getHost());
            case "body":
                return nullToEmpty(context.getBody());
            default:
                break;
        }

        // request.body.xxx — JSON field extraction
        if (field.startsWith("body.")) {
            String jsonPath = field.substring(5);
            return extractJsonField(context.getBody(), jsonPath);
        }

        // request.header.xxx
        if (field.startsWith("header.")) {
            String headerName = field.substring(7);
            return getFromMap(context.getHeaders(), headerName);
        }

        // request.query.xxx
        if (field.startsWith("query.")) {
            String queryName = field.substring(6);
            return getFromMap(context.getQueryParams(), queryName);
        }

        log.debug("Unresolved request variable: {}", expression);
        return "{{" + expression + "}}";
    }

    /**
     * Extract a nested field from a JSON body.
     * Supports dot-notation like "user.address.city".
     */
    private static String extractJsonField(String body, String jsonPath) {
        if (body == null || body.isEmpty() || jsonPath == null || jsonPath.isEmpty()) {
            return "";
        }

        try {
            JsonNode node = MAPPER.readTree(body);
            String[] parts = jsonPath.split("\\.");
            for (String part : parts) {
                // Handle array index: items[0]
                if (part.contains("[")) {
                    int bracketIdx = part.indexOf('[');
                    String arrayField = part.substring(0, bracketIdx);
                    if (node.has(arrayField)) {
                        node = node.get(arrayField);
                    }
                    // Extract index
                    String indexStr = part.substring(bracketIdx + 1, part.indexOf(']'));
                    int idx = Integer.parseInt(indexStr);
                    if (node.isArray() && idx < node.size()) {
                        node = node.get(idx);
                    } else {
                        return "";
                    }
                } else {
                    if (node.has(part)) {
                        node = node.get(part);
                    } else {
                        return "";
                    }
                }
            }

            if (node.isValueNode()) {
                return node.asText();
            }
            // For object/array nodes, return the JSON string
            return node.toString();
        } catch (Exception e) {
            log.debug("Failed to extract JSON path '{}' from body: {}", jsonPath, e.getMessage());
            return "";
        }
    }

    private static String getFromMap(Map<String, String> map, String key) {
        if (map == null) return "";
        String value = map.get(key);
        if (value == null) {
            // Case-insensitive fallback
            for (Map.Entry<String, String> entry : map.entrySet()) {
                if (entry.getKey().equalsIgnoreCase(key)) {
                    return nullToEmpty(entry.getValue());
                }
            }
            return "";
        }
        return value;
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s;
    }

    /**
     * Request context holder for template variable resolution.
     */
    public static class RequestContext {
        private String method;
        private String path;
        private String host;
        private Map<String, String> headers;
        private Map<String, String> queryParams;
        private String body;

        public RequestContext() {
        }

        public RequestContext(String method, String path, String host,
                              Map<String, String> headers, Map<String, String> queryParams,
                              String body) {
            this.method = method;
            this.path = path;
            this.host = host;
            this.headers = headers;
            this.queryParams = queryParams;
            this.body = body;
        }

        public String getMethod() { return method; }
        public void setMethod(String method) { this.method = method; }

        public String getPath() { return path; }
        public void setPath(String path) { this.path = path; }

        public String getHost() { return host; }
        public void setHost(String host) { this.host = host; }

        public Map<String, String> getHeaders() { return headers; }
        public void setHeaders(Map<String, String> headers) { this.headers = headers; }

        public Map<String, String> getQueryParams() { return queryParams; }
        public void setQueryParams(Map<String, String> queryParams) { this.queryParams = queryParams; }

        public String getBody() { return body; }
        public void setBody(String body) { this.body = body; }
    }
}
