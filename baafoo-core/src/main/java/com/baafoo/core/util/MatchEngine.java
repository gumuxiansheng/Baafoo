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
     * Per-rule request counter store. P2-6: decoupled from the global static
     * singleton so tests can inject a mock. Defaults to the global singleton
     * for backward compatibility.
     */
    private final StatefulCounterStore counterStore;

    /**
     * Max wall-clock time (ms) for a single regex match before timing out
     * and treating the match as a non-match (P2-5 ReDoS protection).
     */
    private final long regexTimeoutMs;

    /** Default regex timeout (ms) used when no value is configured. */
    private static final long DEFAULT_REGEX_TIMEOUT_MS = 100L;

    public MatchEngine() {
        this(StatefulCounterStore.global(), DEFAULT_REGEX_TIMEOUT_MS);
    }

    /** Test-friendly constructor allowing counter store and regex timeout injection. */
    public MatchEngine(StatefulCounterStore counterStore, long regexTimeoutMs) {
        this.counterStore = counterStore != null ? counterStore : StatefulCounterStore.global();
        this.regexTimeoutMs = regexTimeoutMs > 0 ? regexTimeoutMs : DEFAULT_REGEX_TIMEOUT_MS;
    }

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
        return match(rules, protocol, host, port, serviceName, method, path,
                null, headers, queryParams, body);
    }

    /**
     * Match with port=0 fallback.
     *
     * <p>If the first match (with the real port) fails, retries with port=0
     * to match rules that don't specify a port. Encapsulates the two-step
     * match pattern previously duplicated in every gRPC handler (D6 fix).</p>
     *
     * @param topic the MQ topic/destination name (null for non-MQ requests)
     */
    public MatchResult matchWithFallback(List<Rule> rules, String protocol, String host, int port,
                                          String serviceName, String method, String path, String topic,
                                          Map<String, String> headers, Map<String, String> queryParams,
                                          String body) {
        MatchResult result = match(rules, protocol, host, port, serviceName,
                method, path, topic, headers, queryParams, body);
        if (!result.isMatched() && port > 0) {
            result = match(rules, protocol, host, 0, serviceName,
                    method, path, topic, headers, queryParams, body);
        }
        return result;
    }

    // ---- P2-1: MatchRequest overloads (preferred for new call sites) ----

    /**
     * Match a {@link MatchRequest} against a list of rules.
     *
     * <p>P2-1: preferred over the 11-14 parameter overloads. Equivalent to
     * {@code match(rules, req.getProtocol(), req.getHost(), req.getPort(), ...)}.</p>
     */
    public MatchResult match(List<Rule> rules, MatchRequest req) {
        return match(rules, req.getProtocol(), req.getHost(), req.getPort(),
                req.getServiceName(), req.getMethod(), req.getPath(), req.getTopic(),
                req.getHeaders(), req.getQueryParams(), req.getBody());
    }

    /**
     * Match a {@link MatchRequest} with port=0 fallback.
     *
     * <p>P2-1: preferred over the 11-14 parameter overload. If the first match
     * (with the real port) fails, retries with port=0 to match rules that
     * don't specify a port.</p>
     */
    public MatchResult matchWithFallback(List<Rule> rules, MatchRequest req) {
        MatchResult result = match(rules, req);
        if (!result.isMatched() && req.getPort() > 0) {
            int originalPort = req.getPort();
            req.setPort(0);
            try {
                result = match(rules, req);
            } finally {
                req.setPort(originalPort);
            }
        }
        return result;
    }

    /**
     * Match request against a list of rules, with an explicit {@code topic} parameter
     * for MQ protocols (Kafka/Pulsar/JMS). The {@code topic} is used by conditions of
     * type {@code "topic"}; for HTTP rules, pass {@code null} and the topic condition
     * falls back to matching against {@code path}.
     *
     * @param topic the MQ topic/destination name (null for non-MQ requests)
     */
    public MatchResult match(List<Rule> rules, String protocol, String host, int port,
                              String serviceName, String method, String path, String topic,
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
            int[] matchResult = matchConditions(rule, method, path, topic, headers, queryParams, body);
            if (matchResult != null) {
                log.debug("Rule matched: {} (name={})", rule.getId(), rule.getName());
                // Use the count captured at increment time to avoid TOCTOU race
                // between incrementAndGet() and a separate get() call.
                return new MatchResult(rule, matchResult[0], matchResult[1]);
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
     * Match conditions and return the response entry index along with the
     * request count captured at increment time.
     *
     * @return int array [responseIdx, count] if matched, or null if no match.
     *         Using the captured count avoids a TOCTOU race between
     *         incrementAndGet() and a separate get() call.
     */
    private int[] matchConditions(Rule rule, String method, String path, String topic,
                                 Map<String, String> headers, Map<String, String> queryParams,
                                 String body) {

        List<MatchCondition> conditions = rule.getConditions();

        // Check if all rule-level conditions match.
        // requestCount at rule level uses the pre-increment count (0 on first request).
        if (conditions != null && !conditions.isEmpty()) {
            for (MatchCondition cond : conditions) {
                if (!matchSingleCondition(cond, method, path, topic, headers, queryParams, body, 0)) {
                    return null;
                }
            }
        }

        // Rule matched (or has no rule-level conditions) — increment per-rule
        // counter (1-based). This happens AFTER rule-level conditions pass but
        // BEFORE response entry evaluation, so requestCount conditions in
        // response entries see the incremented count.
        int count = counterStore.incrementAndGet(rule.getId());

        // Select response entry, evaluating requestCount conditions with the
        // incremented count.
        int responseIdx = selectResponseEntry(rule, method, path, topic, headers, queryParams, body, count);

        // Auto-reset counter if requestCountReset threshold is reached.
        // This happens AFTER the response is selected, so the current request
        // still uses the threshold-reaching count.
        Integer resetThreshold = rule.getRequestCountReset();
        if (resetThreshold != null && resetThreshold > 0) {
            counterStore.resetIfThreshold(rule.getId(), resetThreshold);
        }

        return new int[]{responseIdx, count};
    }

    /**
     * Select the response entry index by evaluating per-entry conditions.
     *
     * <p>Per AC-03: entries with conditions are evaluated in declaration order;
     * the first match wins. If no conditioned entry matches, the first
     * unconditional entry (or entry 0) is used as default.</p>
     *
     * @param count the current request count (1-based) for requestCount conditions
     */
    private int selectResponseEntry(Rule rule, String method, String path, String topic,
                                     Map<String, String> headers, Map<String, String> queryParams,
                                     String body, int count) {

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
            if (matchSingleCondition(entryCond, method, path, topic, headers, queryParams, body, count)) {
                return i;
            }
        }

        // All matched but no response condition matched → use first (default)
        return 0;
    }

    private boolean matchSingleCondition(MatchCondition cond, String method, String path, String topic,
                                          Map<String, String> headers, Map<String, String> queryParams,
                                          String body, int requestCount) {

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
                    // MQ (Kafka/Pulsar/JMS) topic/destination matching.
                    // Uses the explicit topic parameter; falls back to path for
                    // backward compatibility when topic is not provided.
                    return applyOperator(topic != null ? topic : path, operator, cond.getValue(), cond.isCaseSensitive());

                case "header":
                    if (headers == null) return false;
                    // "exists" checks key presence (containsKey), not value non-null.
                    // A header present with an empty value still satisfies "exists".
                    if ("exists".equals(operator)) return headers.containsKey(cond.getKey());
                    String headerVal = headers.get(cond.getKey());
                    if (headerVal == null) return false;
                    return applyOperator(headerVal, operator, cond.getValue(), cond.isCaseSensitive());

                case "query":
                    if (queryParams == null) return false;
                    // "exists" checks key presence (containsKey), not value non-null.
                    if ("exists".equals(operator)) return queryParams.containsKey(cond.getKey());
                    String queryVal = queryParams.get(cond.getKey());
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

                case "grpcService":
                    // gRPC service name matching. Extracts service name from gRPC path
                    // format "/package.Service/Method" → "package.Service".
                    // value = expected service name.
                    if (path == null) return false;
                    String grpcService = extractGrpcService(path);
                    if (grpcService == null) return false;
                    if ("exists".equals(operator)) return true;
                    return applyOperator(grpcService, operator, cond.getValue(), cond.isCaseSensitive());

                case "grpcMethod":
                    // gRPC method name matching. Extracts method name from gRPC path
                    // format "/package.Service/Method" → "Method".
                    // value = expected method name.
                    if (path == null) return false;
                    String grpcMethod = extractGrpcMethod(path);
                    if (grpcMethod == null) return false;
                    if ("exists".equals(operator)) return true;
                    return applyOperator(grpcMethod, operator, cond.getValue(), cond.isCaseSensitive());

                case "requestCount":
                    // Stateful Mock (PRD §3 R-S2 AC-13): match based on the
                    // per-rule request counter. The count is 1-based (first
                    // request = 1). Supported operators:
                    //   equals       — count == value
                    //   greaterThan  — count > value
                    //   lessThan     — count < value
                    //   range        — value is "[min,max]" (inclusive)
                    //   mod          — count % key == value (periodic trigger)
                    return matchRequestCount(requestCount, operator, cond.getValue(), cond.getKey());

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
     * Extract the gRPC service name from a gRPC HTTP/2 path.
     *
     * <p>gRPC path format: {@code /package.Service/Method}
     * <ul>
     *   <li>{@code /helloworld.Greeter/SayHello} → "helloworld.Greeter"</li>
     *   <li>{@code /Greeter/SayHello} → "Greeter"</li>
     * </ul>
     *
     * @param path the HTTP path from the gRPC request
     * @return the service name, or null if the path is not a valid gRPC path
     */
    static String extractGrpcService(String path) {
        if (path == null || path.isEmpty()) return null;
        // gRPC paths always start with "/"
        if (!path.startsWith("/")) return null;
        // Find the second "/" that separates service and method
        int secondSlash = path.indexOf('/', 1);
        if (secondSlash < 0) return null;
        // Service name is between first and second slash
        String service = path.substring(1, secondSlash);
        return service.isEmpty() ? null : service;
    }

    /**
     * Extract the gRPC method name from a gRPC HTTP/2 path.
     *
     * <p>gRPC path format: {@code /package.Service/Method}
     * <ul>
     *   <li>{@code /helloworld.Greeter/SayHello} → "SayHello"</li>
     * </ul>
     *
     * @param path the HTTP path from the gRPC request
     * @return the method name, or null if the path is not a valid gRPC path
     */
    static String extractGrpcMethod(String path) {
        if (path == null || path.isEmpty()) return null;
        if (!path.startsWith("/")) return null;
        int secondSlash = path.indexOf('/', 1);
        if (secondSlash < 0 || secondSlash == path.length() - 1) return null;
        String method = path.substring(secondSlash + 1);
        return method.isEmpty() ? null : method;
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

        // Remove leading hash comments (# ...) before checking the first token.
        // Note: GraphQL spec only defines # as line comment; // is NOT a valid comment.
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

        // Unknown leading token — cannot determine operation type.
        // Return null to avoid matching malformed GraphQL requests to query-type rules.
        return null;
    }

    /**
     * Evaluate a {@code requestCount} condition (PRD §3 R-S2 AC-13).
     *
     * <p>Supported operators:
     * <ul>
     *   <li>{@code equals} — count equals the integer value</li>
     *   <li>{@code greaterThan} — count is strictly greater than value</li>
     *   <li>{@code lessThan} — count is strictly less than value</li>
     *   <li>{@code range} — value is {@code "[min,max]"} (inclusive on both ends)</li>
     *   <li>{@code mod} — {@code count % divisor == remainder}, where
     *       {@code key} is the divisor and {@code value} is the remainder.
     *       Example: {@code key=3, value=0} triggers every 3rd request.</li>
     * </ul>
     * </p>
     *
     * @param count     the current request count (1-based)
     * @param operator  the comparison operator
     * @param value     the expected value (or range string, or remainder for mod)
     * @param key       the divisor for {@code mod} operator (ignored otherwise)
     * @return true if the count matches the condition
     */
    private boolean matchRequestCount(int count, String operator, String value, String key) {
        if (value == null) {
            return false;
        }
        try {
            switch (operator) {
                case "equals":
                    return count == Integer.parseInt(value.trim());

                case "greaterThan":
                    return count > Integer.parseInt(value.trim());

                case "lessThan":
                    return count < Integer.parseInt(value.trim());

                case "range": {
                    // value format: "[min,max]" or "min,max"
                    String rangeStr = value.trim();
                    if (rangeStr.startsWith("[")) rangeStr = rangeStr.substring(1);
                    if (rangeStr.endsWith("]")) rangeStr = rangeStr.substring(0, rangeStr.length() - 1);
                    String[] parts = rangeStr.split(",");
                    if (parts.length != 2) return false;
                    int min = Integer.parseInt(parts[0].trim());
                    int max = Integer.parseInt(parts[1].trim());
                    return count >= min && count <= max;
                }

                case "mod": {
                    // key = divisor, value = expected remainder
                    if (key == null) return false;
                    int divisor = Integer.parseInt(key.trim());
                    if (divisor <= 0) return false;
                    int remainder = Integer.parseInt(value.trim());
                    return count % divisor == remainder;
                }

                default:
                    log.warn("Unknown operator for requestCount: {}", operator);
                    return false;
            }
        } catch (NumberFormatException e) {
            log.warn("Invalid numeric value for requestCount condition: operator={}, value={}, key={}",
                    operator, value, key);
            return false;
        }
    }

    private boolean applyOperator(String actual, String operator, String expected, boolean caseSensitive) {
        // null actual: no operator can match (exists is handled by callers via containsKey).
        if (actual == null) return false;

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

    /**
     * Match {@code input} against {@code regex} with a wall-clock timeout
     * (P2-5 ReDoS protection).
     *
     * <p>If the regex match exceeds {@link #regexTimeoutMs}, it is treated as
     * a non-match and a warning is logged. This prevents a malicious or
     * pathological regex (e.g., catastrophic backtracking) from blocking the
     * matching thread.</p>
     *
     * <p>Implementation note: the timeout is enforced via a bounded thread pool
     * + Future.get(timeout). We deliberately do NOT use {@code Matcher.hasTransparentBounds}
     * or Java 9+ {@code Matcher} timeouts because Baafoo targets Java 8.</p>
     */
    private boolean matchRegex(String input, String regex) {
        try {
            Pattern pattern = patternCache.get(regex);
            if (pattern == null) {
                pattern = Pattern.compile(regex);
                if (patternCache.size() < 256) {
                    patternCache.putIfAbsent(regex, pattern);
                }
            }
            return matchWithTimeout(pattern, input);
        } catch (PatternSyntaxException e) {
            log.warn("Invalid regex: {}", regex);
            return false;
        }
    }

    private static final java.util.concurrent.ExecutorService REGEX_EXECUTOR =
            java.util.concurrent.Executors.newCachedThreadPool(
                    new java.util.concurrent.ThreadFactory() {
                        final java.util.concurrent.atomic.AtomicInteger seq = new java.util.concurrent.atomic.AtomicInteger();
                        @Override
                        public Thread newThread(Runnable r) {
                            Thread t = new Thread(r, "baafoo-regex-" + seq.incrementAndGet());
                            t.setDaemon(true);
                            return t;
                        }
                    });

    private boolean matchWithTimeout(Pattern pattern, String input) {
        // Fast path: for short inputs and simple patterns, run inline to avoid
        // the thread-pool overhead. We only offload to the executor when the
        // input is non-trivial in length.
        if (input == null) return false;
        if (input.length() < 64) {
            return pattern.matcher(input).matches();
        }

        java.util.concurrent.Future<Boolean> future = REGEX_EXECUTOR.submit(
                () -> pattern.matcher(input).matches());
        try {
            return future.get(regexTimeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            future.cancel(true);
            log.warn("Regex match timed out after {}ms (possible ReDoS): pattern={}",
                    regexTimeoutMs, pattern.pattern());
            return false;
        } catch (java.util.concurrent.ExecutionException e) {
            log.warn("Regex match failed: {}", e.getMessage());
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Regex match interrupted");
            return false;
        }
    }

    /**
     * Match result containing the matched rule and response entry index.
     */
    public static class MatchResult {

        public static final MatchResult NO_MATCH = new MatchResult(null, -1, 0);

        private final Rule rule;
        private final int responseIndex;
        private final int requestCount;

        public MatchResult(Rule rule, int responseIndex) {
            this(rule, responseIndex, 0);
        }

        public MatchResult(Rule rule, int responseIndex, int requestCount) {
            this.rule = rule;
            this.responseIndex = responseIndex;
            this.requestCount = requestCount;
        }

        public boolean isMatched() { return rule != null; }

        public Rule getRule() { return rule; }

        public int getResponseIndex() { return responseIndex; }

        /**
         * Get the request count (1-based) at the time this rule matched.
         *
         * <p>Used for {@code {{requestCount}}} template variable substitution
         * in response bodies (PRD §3 R-S2 AC-13 example).</p>
         *
         * @return the request count, or 0 if no counter was incremented
         */
        public int getRequestCount() { return requestCount; }

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
