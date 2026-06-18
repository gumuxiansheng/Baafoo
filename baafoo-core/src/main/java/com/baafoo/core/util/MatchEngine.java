package com.baafoo.core.util;

import com.baafoo.core.model.MatchCondition;
import com.baafoo.core.model.ResponseEntry;
import com.baafoo.core.model.Rule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * Rule matching engine.
 *
 * <p>Matches incoming requests against configured rules.
 * Rules are sorted by priority (ascending), and the first match wins.</p>
 *
 * <p>"未匹配规则默认返回 404" — unmatched requests return HTTP 404,
 * NOT passthrough to real downstream (per product-advice.md safety design).</p>
 */
public class MatchEngine {

    private static final Logger log = LoggerFactory.getLogger(MatchEngine.class);

    /** Pre-compiled regex patterns (cached by rule ID + condition index) */
    private final Map<String, Pattern> patternCache = new ConcurrentHashMap<String, Pattern>();

    /**
     * Match request against a list of rules.
     *
     * @param rules     sorted list of rules (by priority)
     * @param protocol  request protocol
     * @param host      target host
     * @param port      target port
     * @param serviceName service name (for Consul)
     * @param method    HTTP method (nullable for non-HTTP)
     * @param path      request path (nullable for non-HTTP)
     * @param headers   request headers
     * @param queryParams query parameters
     * @param body      request body (nullable)
     * @return matched rule + response entry index, or null if no match
     */
    public MatchResult match(List<Rule> rules, String protocol, String host, int port,
                              String serviceName, String method, String path,
                              Map<String, String> headers, Map<String, String> queryParams,
                              String body) {

        if (rules == null || rules.isEmpty()) {
            return MatchResult.NO_MATCH;
        }

        for (Rule rule : rules) {
            if (!rule.isEnabled()) {
                continue;
            }

            // Protocol filter
            if (!matchesProtocol(rule, protocol)) {
                continue;
            }

            // Target filter: service name (Consul) OR host:port
            if (!matchesTarget(rule, host, port, serviceName)) {
                continue;
            }

            // Match all conditions (AND logic)
            int responseIdx = matchConditions(rule, method, path, headers, queryParams, body);
            if (responseIdx >= 0) {
                log.debug("Rule matched: {} (name={})", rule.getId(), rule.getName());
                return new MatchResult(rule, responseIdx);
            }
        }

        log.debug("No rule matched for {}://{}:{}{}", protocol, host, port, path);
        return MatchResult.NO_MATCH;
    }

    private boolean matchesProtocol(Rule rule, String protocol) {
        if (rule.getProtocol() == null || rule.getProtocol().isEmpty()) {
            return true; // wildcard
        }
        return rule.getProtocol().equalsIgnoreCase(protocol);
    }

    private boolean matchesTarget(Rule rule, String host, int port, String serviceName) {
        // Service name match (Consul)
        if (rule.getServiceName() != null && !rule.getServiceName().isEmpty()) {
            if (serviceName != null) {
                return rule.getServiceName().equalsIgnoreCase(serviceName);
            }
            return false;
        }

        // Host match — null host means caller doesn't know target host (e.g. MQ broker),
        // in which case we skip host filtering instead of failing the match.
        if (rule.getHost() != null && !rule.getHost().isEmpty()) {
            if (host != null && !rule.getHost().equalsIgnoreCase(host)) return false;
        }

        // Port match (port <= 0 means "any port", so we skip port filtering)
        if (rule.getPort() != null && rule.getPort() > 0 && port > 0) {
            if (port != rule.getPort()) return false;
        }

        return true;
    }

    /**
     * Match conditions and return the response entry index.
     *
     * @return >= 0 if matched (index into responses array), -1 if no match
     */
    private int matchConditions(Rule rule, String method, String path,
                                 Map<String, String> headers, Map<String, String> queryParams,
                                 String body) {

        List<MatchCondition> conditions = rule.getConditions();
        if (conditions == null || conditions.isEmpty()) {
            // No conditions = match all, return first response
            return 0;
        }

        // Check if all rule-level conditions match
        boolean allMatch = true;
        for (MatchCondition cond : conditions) {
            if (!matchSingleCondition(cond, method, path, headers, queryParams, body)) {
                allMatch = false;
                break;
            }
        }

        if (!allMatch) {
            return -1;
        }

        // Determine which response entry to use (for parameterized rules)
        List<ResponseEntry> responses = rule.getResponses();
        if (responses == null || responses.isEmpty()) {
            return 0;
        }

        for (int i = 0; i < responses.size(); i++) {
            ResponseEntry entry = responses.get(i);
            MatchCondition entryCond = entry.getCondition();
            if (entryCond == null) {
                // No condition = default response
                return i;
            }
            if (matchSingleCondition(entryCond, method, path, headers, queryParams, body)) {
                return i;
            }
        }

        // All matched but no response condition matched → use first (default)
        return 0;
    }

    private boolean matchSingleCondition(MatchCondition cond, String method, String path,
                                          Map<String, String> headers, Map<String, String> queryParams,
                                          String body) {

        if (cond == null || cond.getType() == null) {
            return true;
        }

        String operator = cond.getOperator();
        if (operator == null) operator = "equals";

        try {
            switch (cond.getType()) {
                case "method":
                    return applyOperator(method, operator, cond.getValue(), cond.isCaseSensitive());

                case "path":
                    return applyOperator(path, operator, cond.getValue(), cond.isCaseSensitive());

                case "topic":
                    // topic 是 path 的别名 —— MQ (Kafka/Pulsar/JMS) 规则用 topic 条件
                    // 匹配 topic/destination 名。broker 端把 topic 传入 path 形参槽位。
                    return applyOperator(path, operator, cond.getValue(), cond.isCaseSensitive());

                case "header":
                    if (headers == null) return false;
                    String headerVal = headers.get(cond.getKey());
                    if (headerVal == null && "exists".equals(operator)) return false;
                    if (headerVal == null) return false;
                    return applyOperator(headerVal, operator, cond.getValue(), cond.isCaseSensitive());

                case "query":
                    if (queryParams == null) return false;
                    String queryVal = queryParams.get(cond.getKey());
                    if (queryVal == null && "exists".equals(operator)) return false;
                    if (queryVal == null) return false;
                    return applyOperator(queryVal, operator, cond.getValue(), cond.isCaseSensitive());

                case "body":
                    if (body == null) return false;
                    return applyOperator(body, operator, cond.getValue(), cond.isCaseSensitive());

                case "bodyContains":
                    if (body == null) return false;
                    String search = cond.isCaseSensitive() ? cond.getValue() : cond.getValue().toLowerCase();
                    String target = cond.isCaseSensitive() ? body : body.toLowerCase();
                    return target.contains(search);

                case "bodyJsonPath":
                    // JSONPath matching — extracts a field from the JSON body and
                    // applies the operator. Path syntax supports dot-notation with
                    // an optional leading "$" (e.g. "$.operationName" or "user.id").
                    //
                    // Field convention (consistent with header/query conditions):
                    //   key   = JSONPath expression (e.g. "$.operationName")
                    //   value = expected value (for equals/contains/regex; ignored for exists)
                    if (body == null) return false;
                    String jsonPath = cond.getKey();
                    if (jsonPath == null || jsonPath.isEmpty()) {
                        // Backward-compat: if key is unset, treat value as the path
                        // with implicit "exists" semantics.
                        jsonPath = cond.getValue();
                        if (jsonPath == null || jsonPath.isEmpty()) return false;
                        return JsonPathUtil.exists(body, jsonPath);
                    }
                    if ("exists".equals(operator)) {
                        return JsonPathUtil.exists(body, jsonPath);
                    }
                    String extracted = JsonPathUtil.extract(body, jsonPath);
                    if (extracted.isEmpty()) return false;
                    return applyOperator(extracted, operator, cond.getValue(), cond.isCaseSensitive());

                case "graphqlOperationName":
                    // Syntactic sugar for bodyJsonPath with key="$.operationName".
                    // value = expected operation name.
                    if (body == null) return false;
                    String opName = JsonPathUtil.extract(body, "$.operationName");
                    if ("exists".equals(operator)) {
                        return JsonPathUtil.exists(body, "$.operationName");
                    }
                    if (opName.isEmpty()) return false;
                    return applyOperator(opName, operator, cond.getValue(), cond.isCaseSensitive());

                case "graphqlOperationType":
                    // Syntactic sugar: parse the GraphQL "query" field and return
                    // the operation type ("query" / "mutation" / "subscription").
                    // Anonymous queries (no leading keyword) default to "query".
                    // value = expected operation type.
                    if (body == null) return false;
                    String opType = extractGraphqlOperationType(body);
                    if (opType == null) return false;
                    if ("exists".equals(operator)) return true;
                    return applyOperator(opType, operator, cond.getValue(), cond.isCaseSensitive());

                default:
                    log.warn("Unknown condition type: {}", cond.getType());
                    return false;
            }
        } catch (Exception e) {
            log.warn("Error matching condition {}: {}", cond.getType(), e.getMessage());
            return false;
        }
    }

    /**
     * Extract the GraphQL operation type from a request body.
     *
     * <p>GraphQL requests carry the query string in the {@code query} field of
     * the JSON body. The operation type is determined by the first operation
     * keyword in the query string:</p>
     * <ul>
     *   <li>{@code query GetUser { ... }} → "query"</li>
     *   <li>{@code mutation UpdateUser { ... }} → "mutation"</li>
     *   <li>{@code subscription UserUpdates { ... }} → "subscription"</li>
     *   <li>{@code { user { id } }} → "query" (anonymous query, default)</li>
     * </ul>
     *
     * @param body the HTTP request body (expected to be a GraphQL JSON request)
     * @return the operation type string, or {@code null} if the body is not a
     *         valid GraphQL request (e.g. missing {@code query} field)
     */
    private static String extractGraphqlOperationType(String body) {
        if (body == null || body.isEmpty()) return null;
        String query = JsonPathUtil.extract(body, "$.query");
        if (query == null || query.isEmpty()) return null;

        // Skip leading whitespace and comments, then look for the first
        // operation keyword. If the query starts with "{" it's an anonymous
        // query (shorthand syntax per GraphQL spec §6.1.2).
        String trimmed = query.trim();
        if (trimmed.isEmpty()) return null;

        // Remove leading line comments (// ...) and hash comments (# ...)
        // and block comments before checking the first token.
        // Simple approach: strip leading whitespace/comments and inspect.
        int i = 0;
        while (i < trimmed.length()) {
            char c = trimmed.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
                continue;
            }
            // GraphQL uses # for line comments
            if (c == '#') {
                // skip to end of line
                int nl = trimmed.indexOf('\n', i);
                if (nl == -1) return null;
                i = nl + 1;
                continue;
            }
            break;
        }
        if (i >= trimmed.length()) return null;

        // Anonymous query shorthand: starts with "{"
        if (trimmed.charAt(i) == '{') {
            return "query";
        }

        // Extract the first identifier token
        int start = i;
        while (i < trimmed.length() && Character.isLetterOrDigit(trimmed.charAt(i))) {
            i++;
        }
        String token = trimmed.substring(start, i).toLowerCase();

        if ("query".equals(token)) return "query";
        if ("mutation".equals(token)) return "mutation";
        if ("subscription".equals(token)) return "subscription";

        // Unknown leading token — treat as anonymous query
        return "query";
    }

    private boolean applyOperator(String actual, String operator, String expected, boolean caseSensitive) {
        if (actual == null) return "exists".equals(operator) ? false : false;

        switch (operator) {
            case "equals":
                return caseSensitive ? expected.equals(actual) : expected.equalsIgnoreCase(actual);

            case "contains":
                String act1 = caseSensitive ? actual : actual.toLowerCase();
                String exp1 = caseSensitive ? expected : expected.toLowerCase();
                return act1.contains(exp1);

            case "startsWith":
                String act2 = caseSensitive ? actual : actual.toLowerCase();
                String exp2 = caseSensitive ? expected : expected.toLowerCase();
                return act2.startsWith(exp2);

            case "endsWith":
                String act3 = caseSensitive ? actual : actual.toLowerCase();
                String exp3 = caseSensitive ? expected : expected.toLowerCase();
                return act3.endsWith(exp3);

            case "regex":
                return matchRegex(actual, expected);

            case "exists":
                return actual != null && !actual.isEmpty();

            default:
                log.warn("Unknown operator: {}", operator);
                return false;
        }
    }

    private boolean matchRegex(String input, String regex) {
        try {
            Pattern pattern = patternCache.get(regex);
            if (pattern == null) {
                pattern = Pattern.compile(regex);
                if (patternCache.size() < 256) {
                    patternCache.putIfAbsent(regex, pattern);
                }
            }
            return pattern.matcher(input).matches();
        } catch (PatternSyntaxException e) {
            log.warn("Invalid regex: {}", regex);
            return false;
        }
    }

    /**
     * Match result containing the matched rule and response entry index.
     */
    public static class MatchResult {

        public static final MatchResult NO_MATCH = new MatchResult(null, -1);

        private final Rule rule;
        private final int responseIndex;

        public MatchResult(Rule rule, int responseIndex) {
            this.rule = rule;
            this.responseIndex = responseIndex;
        }

        public boolean isMatched() { return rule != null; }

        public Rule getRule() { return rule; }

        public int getResponseIndex() { return responseIndex; }

        public ResponseEntry getResponse() {
            if (rule == null || rule.getResponses() == null || rule.getResponses().isEmpty()) {
                return null;
            }
            if (responseIndex >= 0 && responseIndex < rule.getResponses().size()) {
                return rule.getResponses().get(responseIndex);
            }
            return rule.getResponses().get(0);
        }
    }
}
