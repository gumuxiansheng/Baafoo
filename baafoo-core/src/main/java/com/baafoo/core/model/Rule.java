package com.baafoo.core.model;

import java.util.List;
import java.util.Collections;

/**
 * A single stub/mock rule that defines how to match and respond to a request.
 */
public class Rule {

    /** Unique rule ID */
    private String id;

    /** Rule name (human-readable) */
    private String name;

    /** Protocol this rule applies to */
    private String protocol;

    /** Target service name (for Consul-based rules) */
    private String serviceName;

    /** Target host (for direct host:port rules) */
    private String host;

    /** Target port (for direct host:port rules) */
    private Integer port;

    /** Match conditions (all must match for rule to apply) */
    private List<MatchCondition> conditions;

    /** Response configurations (for parameterized rules) */
    private List<ResponseEntry> responses;

    /** Whether this rule is enabled */
    private boolean enabled;

    /** Rule priority (lower = higher priority, default 100) */
    private int priority;

    /** Rule tags */
    private List<String> tags;

    /** Environments where this rule is active (empty = not active anywhere, must explicitly associate) */
    private List<String> environments;

    /** TCP-specific: multi-round interaction rounds (R-S3 AC-03) */
    private List<TcpRound> tcpRounds;

    /** TCP-specific: whether to loop back to round 1 after all rounds are exhausted (default: false = close) */
    private boolean tcpLoop;

    /** TCP-specific: regex pattern applied to hex string of request bytes (R-S3 AC-02) */
    private String tcpPattern;

    /** TCP-specific: prefix hex matching for single-round rules */
    private String tcpPrefixHex;

    /** TCP-specific: byte offset start (inclusive) for offset matching (R-S3 AC-05) */
    private int tcpOffsetStart;

    /** TCP-specific: byte offset end (exclusive) for offset matching */
    private int tcpOffsetEnd;

    /** TCP-specific: expected hex value at the byte offset range */
    private String tcpOffsetHex;

    /**
     * Optional seed for deterministic Faker output (R-C2 AC-01).
     *
     * <p>When set, all {@code {{faker.xxx}}} template variables in this rule's
     * responses use a deterministic {@link java.util.Random} seeded with this
     * value, so the same seed produces the same sequence of values across
     * requests. When null, a cryptographically strong random source is used.</p>
     */
    private Long fakerSeed;

    /**
     * Optional auto-reset threshold for the per-rule request counter
     * (R-C2 extension, R-S2 AC-13 stateful mock).
     *
     * <p>When set to a positive integer N, the rule's request counter resets to 0
     * after reaching N, enabling cyclic "first N requests differ" behavior.
     * When null or {@code <= 0}, the counter increments indefinitely.</p>
     */
    private Integer requestCountReset;

    /**
     * Optional fault injection configuration (R-S12).
     *
     * <p>When set, faults are evaluated in declaration order; the first fault
     * whose {@code probability} is hit takes effect. If no fault is hit, the
     * normal response flow proceeds.</p>
     */
    private FaultInjection faultInjection;

    /**
     * Optional charset for decoding the inbound request body (null = UTF-8).
     *
     * <p>Used by TCP/Kafka/Pulsar/JMS handlers when the client sends payloads
     * in a non-UTF-8 encoding (e.g., GBK, GB2312, Big5). Matching is always
     * performed with UTF-8 first (preserving existing behaviour); after a rule
     * matches, the request bytes are re-decoded using this charset so that
     * {@code {{request.body}}} template variables and recording capture the
     * correct text. HTTP infers the request charset from the
     * {@code Content-Type} header and does not need this field.</p>
     */
    private String requestCharset;

    /** Rule version for undo support */
    private int version;

    /** Created timestamp */
    private long createdAt;

    /** Updated timestamp */
    private long updatedAt;

    public Rule() {
        this.conditions = Collections.emptyList();
        this.responses = Collections.emptyList();
        this.tags = Collections.emptyList();
        this.environments = Collections.emptyList();
        this.tcpRounds = Collections.emptyList();
        this.enabled = true;
        this.priority = 100;
        this.version = 1;
        this.tcpOffsetStart = -1;
        this.tcpOffsetEnd = -1;
    }

    // --- Getters / Setters ---

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getProtocol() { return protocol; }
    public void setProtocol(String protocol) { this.protocol = protocol; }

    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public Integer getPort() { return port; }
    public void setPort(Integer port) { this.port = port; }

    public List<MatchCondition> getConditions() { return conditions; }
    public void setConditions(List<MatchCondition> conditions) { this.conditions = conditions; }

    public List<ResponseEntry> getResponses() { return responses; }
    public void setResponses(List<ResponseEntry> responses) { this.responses = responses; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getPriority() { return priority; }
    public void setPriority(int priority) { this.priority = priority; }

    public List<String> getTags() { return tags; }
    public void setTags(List<String> tags) { this.tags = tags; }

    public List<String> getEnvironments() { return environments; }
    public void setEnvironments(List<String> environments) { this.environments = environments; }

    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }

    public long getCreatedAt() { return createdAt; }
    public void setCreatedAt(long createdAt) { this.createdAt = createdAt; }

    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long updatedAt) { this.updatedAt = updatedAt; }

    public List<TcpRound> getTcpRounds() { return tcpRounds; }
    public void setTcpRounds(List<TcpRound> tcpRounds) { this.tcpRounds = tcpRounds; }

    public boolean isTcpLoop() { return tcpLoop; }
    public void setTcpLoop(boolean tcpLoop) { this.tcpLoop = tcpLoop; }

    public String getTcpPattern() { return tcpPattern; }
    public void setTcpPattern(String tcpPattern) { this.tcpPattern = tcpPattern; }

    public String getTcpPrefixHex() { return tcpPrefixHex; }
    public void setTcpPrefixHex(String tcpPrefixHex) { this.tcpPrefixHex = tcpPrefixHex; }

    public int getTcpOffsetStart() { return tcpOffsetStart; }
    public void setTcpOffsetStart(int tcpOffsetStart) { this.tcpOffsetStart = tcpOffsetStart; }

    public int getTcpOffsetEnd() { return tcpOffsetEnd; }
    public void setTcpOffsetEnd(int tcpOffsetEnd) { this.tcpOffsetEnd = tcpOffsetEnd; }

    public String getTcpOffsetHex() { return tcpOffsetHex; }
    public void setTcpOffsetHex(String tcpOffsetHex) { this.tcpOffsetHex = tcpOffsetHex; }

    public Long getFakerSeed() { return fakerSeed; }
    public void setFakerSeed(Long fakerSeed) { this.fakerSeed = fakerSeed; }

    public Integer getRequestCountReset() { return requestCountReset; }
    public void setRequestCountReset(Integer requestCountReset) { this.requestCountReset = requestCountReset; }

    public FaultInjection getFaultInjection() { return faultInjection; }
    public void setFaultInjection(FaultInjection faultInjection) { this.faultInjection = faultInjection; }

    public String getRequestCharset() { return requestCharset; }
    public void setRequestCharset(String requestCharset) { this.requestCharset = requestCharset; }

    @Override
    public String toString() {
        // L9: include priority, environment count, and condition count so the
        // toString is useful for diagnosing rule-sort / env-association issues
        // in logs. The conditions / responses / tags lists themselves are NOT
        // expanded to avoid dumping large collections into log lines.
        return "Rule{" +
                "id='" + id + '\'' +
                ", name='" + name + '\'' +
                ", protocol='" + protocol + '\'' +
                ", enabled=" + enabled +
                ", priority=" + priority +
                ", conditions=" + (conditions != null ? conditions.size() : 0) +
                ", responses=" + (responses != null ? responses.size() : 0) +
                ", environments=" + environments +
                '}';
    }
}
