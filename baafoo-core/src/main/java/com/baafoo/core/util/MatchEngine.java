package com.baafoo.core.util;

import com.baafoo.core.model.MatchCondition;
import com.baafoo.core.model.ResponseEntry;
import com.baafoo.core.model.Rule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
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
    private final Map<String, Pattern> patternCache = new HashMap<String, Pattern>();

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

        // Host match
        if (rule.getHost() != null && !rule.getHost().isEmpty()) {
            if (host == null) return false;
            if (!rule.getHost().equalsIgnoreCase(host)) return false;
        }

        // Port match (port <= 0 means "any port")
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
                    // JSONPath matching deferred to plugin (requires Jackson)
                    log.warn("bodyJsonPath matching not implemented at engine level, assuming pass");
                    return true;

                default:
                    log.warn("Unknown condition type: {}", cond.getType());
                    return false;
            }
        } catch (Exception e) {
            log.warn("Error matching condition {}: {}", cond.getType(), e.getMessage());
            return false;
        }
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
            Pattern pattern = patternCache.computeIfAbsent(regex, Pattern::compile);
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
