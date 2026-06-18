package com.baafoo.core.model;

/**
 * A match condition within a rule.
 * Multiple conditions in a rule are AND-ed together.
 */
public class MatchCondition {

    /**
     * Condition type: method, path, topic, header, query, body, bodyContains, bodyJsonPath,
     * graphqlOperationName, graphqlOperationType.
     * <p>
     * {@code topic} is an alias of {@code path} used by MQ (Kafka/Pulsar/JMS) rules
     * to match a topic/destination name. The broker passes the topic through the
     * {@code path} parameter slot of {@code MatchEngine.match}.
     * <p>
     * {@code graphqlOperationName} and {@code graphqlOperationType} are syntactic
     * sugars for matching GraphQL requests. They are equivalent to
     * {@code bodyJsonPath} with {@code key="$.operationName"} and a parsed
     * operation type respectively. See PRD §5 (R-S2 AC-14).
     */
    private String type;

    /** Operator: equals, contains, startsWith, endsWith, regex, exists */
    private String operator;

    /** Key (e.g., header name, query param name) */
    private String key;

    /** Expected value */
    private String value;

    /** Whether this condition is case-sensitive */
    private boolean caseSensitive;

    public MatchCondition() {
        this.caseSensitive = true;
    }

    // --- Factory methods ---

    public static MatchCondition method(String value) {
        MatchCondition c = new MatchCondition();
        c.type = "method";
        c.operator = "equals";
        c.value = value;
        return c;
    }

    public static MatchCondition path(String operator, String value) {
        MatchCondition c = new MatchCondition();
        c.type = "path";
        c.operator = operator;
        c.value = value;
        return c;
    }

    /**
     * Factory for an MQ topic/destination match condition.
     * Semantically identical to {@link #path(String, String)}; {@code topic} is
     * provided for rule readability in Kafka/Pulsar/JMS contexts.
     */
    public static MatchCondition topic(String operator, String value) {
        MatchCondition c = new MatchCondition();
        c.type = "topic";
        c.operator = operator;
        c.value = value;
        return c;
    }

    public static MatchCondition header(String key, String operator, String value) {
        MatchCondition c = new MatchCondition();
        c.type = "header";
        c.key = key;
        c.operator = operator;
        c.value = value;
        return c;
    }

    public static MatchCondition query(String key, String operator, String value) {
        MatchCondition c = new MatchCondition();
        c.type = "query";
        c.key = key;
        c.operator = operator;
        c.value = value;
        return c;
    }

    public static MatchCondition body(String operator, String value) {
        MatchCondition c = new MatchCondition();
        c.type = "body";
        c.operator = operator;
        c.value = value;
        return c;
    }

    /**
     * Factory for a {@code bodyJsonPath} match condition.
     *
     * @param jsonPath   the JSON path expression (e.g. {@code $.operationName}
     *                   or {@code user.address.city}); stored in the {@code key} field
     * @param operator   the operator to apply ({@code equals}, {@code contains},
     *                   {@code regex}, {@code exists}, etc.)
     * @param expectedValue the expected value for comparison operators; ignored
     *                   for {@code exists}
     */
    public static MatchCondition bodyJsonPath(String jsonPath, String operator, String expectedValue) {
        MatchCondition c = new MatchCondition();
        c.type = "bodyJsonPath";
        c.key = jsonPath;
        c.operator = operator;
        c.value = expectedValue;
        return c;
    }

    /**
     * Factory for a {@code graphqlOperationName} match condition.
     * <p>
     * Syntactic sugar for {@code bodyJsonPath} with {@code key="$.operationName"}.
     *
     * @param operator      the operator to apply ({@code equals}, {@code contains},
     *                      {@code regex}, {@code exists}, etc.)
     * @param operationName the expected GraphQL operation name (ignored for {@code exists})
     */
    public static MatchCondition graphqlOperationName(String operator, String operationName) {
        MatchCondition c = new MatchCondition();
        c.type = "graphqlOperationName";
        c.operator = operator;
        c.value = operationName;
        return c;
    }

    /**
     * Factory for a {@code graphqlOperationType} match condition.
     * <p>
     * Matches the GraphQL operation type extracted from the {@code query} field
     * of the request body. The operation type is one of {@code query},
     * {@code mutation}, or {@code subscription}. Anonymous queries (no leading
     * keyword) are treated as {@code query}.
     *
     * @param operator      the operator to apply (typically {@code equals})
     * @param operationType the expected operation type: {@code query},
     *                      {@code mutation}, or {@code subscription}
     */
    public static MatchCondition graphqlOperationType(String operator, String operationType) {
        MatchCondition c = new MatchCondition();
        c.type = "graphqlOperationType";
        c.operator = operator;
        c.value = operationType;
        return c;
    }

    // --- Getters / Setters ---

    public String getType() { return type; }
    public void setType(String type) { this.type = type; }

    public String getOperator() { return operator; }
    public void setOperator(String operator) { this.operator = operator; }

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }

    public String getValue() { return value; }
    public void setValue(String value) { this.value = value; }

    public boolean isCaseSensitive() { return caseSensitive; }
    public void setCaseSensitive(boolean caseSensitive) { this.caseSensitive = caseSensitive; }

    @Override
    public String toString() {
        return "MatchCondition{" +
                "type='" + type + '\'' +
                ", operator='" + operator + '\'' +
                ", key='" + key + '\'' +
                ", value='" + value + '\'' +
                '}';
    }
}
