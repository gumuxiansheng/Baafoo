package com.baafoo.core.model;

import java.util.Collections;
import java.util.List;

/**
 * Fault injection configuration for a rule (R-S12).
 *
 * <p>Contains a list of {@link Fault} entries that are evaluated in declaration
 * order. The first fault whose {@code probability} is hit takes effect; if no
 * fault is hit, the normal response flow proceeds.</p>
 *
 * <p>Phase 1 (P1) supports HTTP {@code HTTP_ERROR} and {@code DELAY} faults.
 * Phase 2 (P1) adds {@code CONNECTION_RESET} and {@code READ_TIMEOUT}.
 * Phase 3 (P2) extends to Kafka/Pulsar protocols, adding Kafka protocol
 * faults such as {@code KAFKA_NOT_LEADER_FOR_PARTITION},
 * {@code KAFKA_OFFSET_OUT_OF_RANGE}, {@code KAFKA_PRODUCE_THROTTLE},
 * {@code KAFKA_DELAY} and {@code KAFKA_CONNECTION_RESET}.</p>
 */
public class FaultInjection {

    /** Ordered list of faults to evaluate. */
    private List<Fault> faults;

    public FaultInjection() {
        this.faults = Collections.emptyList();
    }

    public List<Fault> getFaults() { return faults; }
    public void setFaults(List<Fault> faults) { this.faults = faults; }

    @Override
    public String toString() {
        return "FaultInjection{faults=" + (faults != null ? faults.size() : 0) + "}";
    }

    /**
     * A single fault entry.
     *
     * <p>Each fault has a {@code type}, a {@code probability} (0.0–1.0) of
     * triggering on any given request, and type-specific parameters.</p>
     */
    public static class Fault {

        /**
         * Fault type. Supported values:
         * <ul>
         *   <li>{@code HTTP_ERROR} — return one of {@code statusCodes} (Phase 1)</li>
         *   <li>{@code DELAY} — delay the response by {@code delayMs} (Phase 1)</li>
         *   <li>{@code CONNECTION_RESET} — close the connection with RST (Phase 2)</li>
         *   <li>{@code READ_TIMEOUT} — never respond, let the client time out (Phase 2)</li>
         *   <li>{@code KAFKA_NOT_LEADER_FOR_PARTITION} — return Kafka error code 6 (Phase 3)</li>
         *   <li>{@code KAFKA_OFFSET_OUT_OF_RANGE} — return Kafka error code 1 (Phase 3)</li>
         *   <li>{@code KAFKA_PRODUCE_THROTTLE} — throttle produce by {@code delayMs} ms (Phase 3)</li>
         *   <li>{@code KAFKA_DELAY} — delay produce processing by {@code delayMs} ms (Phase 3)</li>
         *   <li>{@code KAFKA_CONNECTION_RESET} — close the Kafka connection (Phase 3)</li>
         * </ul>
         */
        private String type;

        /**
         * Trigger probability for this fault, in the range [0.0, 1.0].
         * Each fault's probability is an independent condition — it is NOT
         * a partition of the request space across all faults.
         */
        private double probability;

        /**
         * For {@code HTTP_ERROR}: the candidate status codes. One is chosen
         * uniformly at random when the fault triggers.
         * Example: {@code [503, 504]} → each has 50% chance.
         */
        private List<Integer> statusCodes;

        /**
         * For {@code DELAY}: the delay in milliseconds. Phase 3 may add
         * {@code delayStdDevMs} for normal-distribution delay.
         */
        private long delayMs;

        /**
         * For {@code DELAY} Phase 3: the standard deviation of the delay.
         * When set, the actual delay is sampled from a normal distribution
         * with mean {@code delayMs} and this standard deviation, truncated
         * to {@code >= 0}. Phase 1 ignores this field.
         */
        private long delayStdDevMs;

        /**
         * For Kafka protocol faults: the Kafka error code to return.
         * Used by {@code KAFKA_NOT_LEADER_FOR_PARTITION} (6),
         * {@code KAFKA_OFFSET_OUT_OF_RANGE} (1) and other custom error faults.
         * When not set, a type-specific default is used.
         */
        private Integer errorCode;

        public Fault() {
            this.probability = 1.0;
        }

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }

        public double getProbability() { return probability; }
        public void setProbability(double probability) { this.probability = probability; }

        public List<Integer> getStatusCodes() { return statusCodes; }
        public void setStatusCodes(List<Integer> statusCodes) { this.statusCodes = statusCodes; }

        public long getDelayMs() { return delayMs; }
        public void setDelayMs(long delayMs) { this.delayMs = delayMs; }

        public long getDelayStdDevMs() { return delayStdDevMs; }
        public void setDelayStdDevMs(long delayStdDevMs) { this.delayStdDevMs = delayStdDevMs; }

        public Integer getErrorCode() { return errorCode; }
        public void setErrorCode(Integer errorCode) { this.errorCode = errorCode; }

        @Override
        public String toString() {
            return "Fault{type='" + type + "', probability=" + probability + "}";
        }
    }
}
