package com.baafoo.core.model;

import java.util.Collections;
import java.util.List;

/**
 * A single round in a multi-round TCP interaction.
 *
 * <p>Each round defines its own request matching criteria and response.
 * The state machine advances through rounds as the client sends requests.</p>
 *
 * <p>Matching priority: offset &gt; pattern (regex) &gt; prefixHex &gt; conditions.
 * If multiple matchers are specified, ALL must match (AND logic).</p>
 */
public class TcpRound {

    /** Round name (e.g., "handshake", "auth", "data") */
    private String name;

    /** Regex pattern applied to the hex string of the request bytes (R-S3 AC-02) */
    private String pattern;

    /** Prefix hex string for matching the beginning of request bytes */
    private String prefixHex;

    /** Byte offset start (inclusive, 0-based) for offset matching (R-S3 AC-05) */
    private int offsetStart;

    /** Byte offset end (exclusive, 0-based) for offset matching */
    private int offsetEnd;

    /** Expected hex value at the byte offset range */
    private String offsetHex;

    /** Additional match conditions (AND-ed with pattern/prefixHex/offset) */
    private List<MatchCondition> conditions;

    /** Response for this round */
    private ResponseEntry response;

    public TcpRound() {
        this.conditions = Collections.emptyList();
        this.offsetStart = -1;
        this.offsetEnd = -1;
    }

    // --- Getters / Setters ---

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getPattern() { return pattern; }
    public void setPattern(String pattern) { this.pattern = pattern; }

    public String getPrefixHex() { return prefixHex; }
    public void setPrefixHex(String prefixHex) { this.prefixHex = prefixHex; }

    public int getOffsetStart() { return offsetStart; }
    public void setOffsetStart(int offsetStart) { this.offsetStart = offsetStart; }

    public int getOffsetEnd() { return offsetEnd; }
    public void setOffsetEnd(int offsetEnd) { this.offsetEnd = offsetEnd; }

    public String getOffsetHex() { return offsetHex; }
    public void setOffsetHex(String offsetHex) { this.offsetHex = offsetHex; }

    public List<MatchCondition> getConditions() { return conditions; }
    public void setConditions(List<MatchCondition> conditions) { this.conditions = conditions; }

    public ResponseEntry getResponse() { return response; }
    public void setResponse(ResponseEntry response) { this.response = response; }

    @Override
    public String toString() {
        return "TcpRound{" +
                "name='" + name + '\'' +
                ", pattern='" + pattern + '\'' +
                ", prefixHex='" + prefixHex + '\'' +
                ", offsetStart=" + offsetStart +
                ", offsetEnd=" + offsetEnd +
                ", offsetHex='" + offsetHex + '\'' +
                '}';
    }
}
