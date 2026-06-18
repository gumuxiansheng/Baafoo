package com.baafoo.core.util;

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
 *   <li>{@code {{environment}}} — current agent environment name</li>
 *   <li>{@code {{faker.phone}}} / {@code {{faker.email}}} / etc. — dynamic fake data</li>
 *   <li>{@code {{faker.randomElement [a,b,c]}}} — pick a random element from a list</li>
 *   <li>{@code {{faker.regexify 'pattern'}}} — generate a string matching a regex</li>
 * </ul>
 * </p>
 *
 * <p>Variables are resolved on every request, so faker functions produce
 * different values each time (useful for generating unique test data).
 * When a rule has {@code fakerSeed} set, the seed is applied via
 * {@link FakerProvider#setSeed(Long)} before rendering and cleared afterwards,
 * so the same seed produces a deterministic sequence of values.</p>
 */
public class TemplateEngine {

    private static final Logger log = LoggerFactory.getLogger(TemplateEngine.class);

    /**
     * Pattern: {{variable.expression}}
     *
     * <p>The expression body is permissive — it allows word chars, dots, spaces,
     * brackets, commas, single quotes, and double quotes. This is necessary so
     * that faker functions like {@code randomElement [a,b,c]} and
     * {@code regexify '[A-Z]{3}'} can be expressed inside a template.</p>
     *
     * <p>The pattern is non-greedy and stops at the first {@code }}}. This means
     * a template variable cannot itself contain the literal sequence
     * {@code }}}, which is an acceptable limitation for response body templates.</p>
     */
    private static final Pattern TEMPLATE_PATTERN = Pattern.compile("\\{\\{([\\w. \\[\\],:'\"{}()|*+?\\\\-]+?)}}");

    /**
     * Render a response body template by substituting all template variables.
     *
     * @param template    raw body template (may contain {{...}} expressions)
     * @param context     request context providing request.* variables
     * @return rendered body string with all variables resolved
     */
    public static String render(String template, RequestContext context) {
        return render(template, context, null);
    }

    /**
     * Render a response body template by substituting all template variables.
     *
     * @param template    raw body template (may contain {{...}} expressions)
     * @param context     request context providing request.* variables
     * @param fakerSeed   optional seed for deterministic faker output. When non-null,
     *                    all faker functions in this template use a deterministic
     *                    {@link java.util.Random} seeded with this value.
     * @return rendered body string with all variables resolved
     */
    public static String render(String template, RequestContext context, Long fakerSeed) {
        if (template == null || template.isEmpty()) {
            return template;
        }
        if (!template.contains("{{")) {
            return template;
        }

        // Apply seed for the duration of this render call if provided.
        if (fakerSeed != null) {
            FakerProvider.setSeed(fakerSeed);
        }
        try {
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
        } finally {
            if (fakerSeed != null) {
                FakerProvider.setSeed(null);
            }
        }
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

        // environment — current agent environment name
        if ("environment".equals(expression)) {
            return nullToEmpty(context != null ? context.getEnvironment() : null);
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
            return JsonPathUtil.extract(context.getBody(), jsonPath);
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
        private String environment;

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

        public RequestContext(String method, String path, String host,
                              Map<String, String> headers, Map<String, String> queryParams,
                              String body, String environment) {
            this.method = method;
            this.path = path;
            this.host = host;
            this.headers = headers;
            this.queryParams = queryParams;
            this.body = body;
            this.environment = environment;
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

        public String getEnvironment() { return environment; }
        public void setEnvironment(String environment) { this.environment = environment; }
    }
}
