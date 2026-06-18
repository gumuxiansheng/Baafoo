package com.baafoo.core.util;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Per-rule request counter store for stateful Mock (PRD §3 R-S2 AC-13).
 *
 * <p>Maintains an {@link AtomicInteger} counter for each rule ID. Counters are
 * 1-based: the first request that matches a rule gets count=1, the second
 * count=2, and so on. This matches the PRD example semantics where
 * {@code lessThan 3} returns "pending" for requests 1 and 2.</p>
 *
 * <p>The store is shared across all {@link MatchEngine} instances within a JVM
 * via the {@link #global()} singleton, so that HTTP, TCP, and MQ handlers all
 * see the same counter for a given rule. The agent runs in a separate JVM, so
 * its counters are independent (which is correct — the agent's state is
 * separate from the server's).</p>
 *
 * <p>Counter lifecycle:
 * <ul>
 *   <li>Created lazily on first {@link #incrementAndGet(String)} call</li>
 *   <li>Reset to 0 via {@link #reset(String)} or {@link #resetAll()}</li>
 *   <li>Auto-reset when {@code requestCountReset} threshold is reached
 *       (checked by {@link MatchEngine} after each match)</li>
 * </ul>
 * </p>
 */
public class StatefulCounterStore {

    private static final StatefulCounterStore GLOBAL = new StatefulCounterStore();

    private final ConcurrentHashMap<String, AtomicInteger> counters =
            new ConcurrentHashMap<String, AtomicInteger>();

    /**
     * Returns the global singleton instance shared across all MatchEngine
     * instances in this JVM.
     */
    public static StatefulCounterStore global() {
        return GLOBAL;
    }

    /**
     * Atomically increment and return the new count for the given rule.
     *
     * <p>The count is 1-based: the first call returns 1, the second returns 2,
     * etc. This matches the PRD example where {@code lessThan 3} matches
     * requests 1 and 2.</p>
     *
     * @param ruleId the rule ID
     * @return the new count after increment (1-based)
     */
    public int incrementAndGet(String ruleId) {
        if (ruleId == null || ruleId.isEmpty()) {
            return 0;
        }
        return counters.computeIfAbsent(ruleId, k -> new AtomicInteger(0))
                .incrementAndGet();
    }

    /**
     * Get the current count for a rule without incrementing.
     *
     * @param ruleId the rule ID
     * @return the current count (0 if the rule has never been matched)
     */
    public int get(String ruleId) {
        if (ruleId == null || ruleId.isEmpty()) {
            return 0;
        }
        AtomicInteger counter = counters.get(ruleId);
        return counter == null ? 0 : counter.get();
    }

    /**
     * Reset the counter for a specific rule to 0.
     *
     * @param ruleId the rule ID
     */
    public void reset(String ruleId) {
        if (ruleId == null || ruleId.isEmpty()) {
            return;
        }
        counters.remove(ruleId);
    }

    /**
     * Reset all counters to 0.
     */
    public void resetAll() {
        counters.clear();
    }

    /**
     * Reset the counter for a rule if it has reached or exceeded the threshold.
     *
     * <p>Used to implement {@code requestCountReset} auto-reset behavior
     * (PRD R-C2 AC-01). Called after each match; if the current count has
     * reached the threshold, the counter is removed so the next request
     * starts from 1 again.</p>
     *
     * @param ruleId    the rule ID
     * @param threshold the reset threshold (count at which to reset)
     * @return true if the counter was reset
     */
    public boolean resetIfThreshold(String ruleId, int threshold) {
        if (ruleId == null || ruleId.isEmpty() || threshold <= 0) {
            return false;
        }
        AtomicInteger counter = counters.get(ruleId);
        if (counter == null) {
            return false;
        }
        if (counter.get() >= threshold) {
            counters.remove(ruleId);
            return true;
        }
        return false;
    }

    /**
     * Get the number of rules with active counters (for monitoring/testing).
     *
     * @return the number of tracked rule counters
     */
    public int size() {
        return counters.size();
    }
}
