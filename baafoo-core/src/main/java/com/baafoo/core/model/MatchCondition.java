package com.baafoo.core.model;

/**
 * A match condition within a rule.
 * Multiple conditions in a rule are AND-ed together.
 */
public class MatchCondition {

    /** Condition type: method, path, header, query, body, bodyJsonPath, bodyContains */
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
