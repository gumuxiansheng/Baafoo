package com.baafoo.core.util;

import com.baafoo.core.model.FaultInjection;
import com.baafoo.core.model.FaultInjection.Fault;

import java.util.List;
import java.util.Random;

/**
 * Fault injection evaluator (PRD §4 R-S12).
 *
 * <p>Evaluates the ordered list of {@link Fault}s on a {@link FaultInjection}
 * config. Per AC-04, faults are evaluated in declaration order; the first fault
 * whose {@code probability} is hit takes effect. If no fault is hit, the normal
 * response flow proceeds.</p>
 *
 * <p>Phase 1 supports:
 * <ul>
 *   <li>{@code HTTP_ERROR} — return one of {@code statusCodes} (uniformly random)</li>
 *   <li>{@code DELAY} — delay the response by {@code delayMs} milliseconds</li>
 * </ul>
 * </p>
 *
 * <p>Phase 2 adds:
 * <ul>
 *   <li>{@code CONNECTION_RESET} — close the connection abruptly (RST) before
 *       sending a response</li>
 *   <li>{@code READ_TIMEOUT} — never respond, let the client time out</li>
 * </ul>
 * </p>
 *
 * <p>Phase 3 adds Kafka protocol faults:
 * <ul>
 *   <li>{@code KAFKA_NOT_LEADER_FOR_PARTITION} / {@code KAFKA_OFFSET_OUT_OF_RANGE}
 *       — return a Kafka error code in Produce/Fetch responses</li>
 *   <li>{@code KAFKA_PRODUCE_THROTTLE} — set throttle_time_ms in Produce response</li>
 *   <li>{@code KAFKA_DELAY} — delay produce processing on the broker</li>
 *   <li>{@code KAFKA_CONNECTION_RESET} — close the Kafka client connection</li>
 * </ul>
 * </p>
 *
 * <p>Thread-safety: stateless, safe for concurrent use. The caller provides the
 * {@link Random} instance, allowing deterministic testing with a seeded random.</p>
 */
public final class FaultInjector {

    private FaultInjector() {
    }

    /**
     * Evaluate the fault injection config and determine what action to take.
     *
     * @param config  the fault injection config; {@code null} or empty faults → {@link FaultResult#noFault()}
     * @param random  the random source for probability rolls and status code selection
     * @return the evaluation result
     */
    public static FaultResult evaluate(FaultInjection config, Random random) {
        if (config == null || config.getFaults() == null || config.getFaults().isEmpty()) {
            return FaultResult.noFault();
        }
        if (random == null) {
            throw new IllegalArgumentException("random must not be null");
        }
        for (Fault fault : config.getFaults()) {
            if (fault == null || fault.getType() == null) {
                continue;
            }
            // L12: clamp probability to [0.0, 1.0]. nextDouble() returns a
            // value in [0.0, 1.0), so any probability <= 0.0 means "never
            // trigger" and any probability >= 1.0 means "always trigger".
            // Without the clamp, a misconfigured probability > 1.0 would
            // still always trigger (correct by accident) but a NaN would
            // make every comparison false and silently disable the fault.
            double probability = fault.getProbability();
            if (probability <= 0.0 || Double.isNaN(probability)) {
                continue;
            }
            double roll = random.nextDouble();
            if (roll >= probability) {
                continue;
            }
            // This fault is triggered
            return applyFault(fault, random);
        }
        return FaultResult.noFault();
    }

    private static FaultResult applyFault(Fault fault, Random random) {
        String type = fault.getType();
        if ("HTTP_ERROR".equals(type)) {
            int status = pickStatus(fault.getStatusCodes(), random);
            return FaultResult.httpError(status, fault);
        }
        if ("DELAY".equals(type)) {
            long delay = Math.max(0, fault.getDelayMs());
            return FaultResult.delay(delay, fault);
        }
        if ("CONNECTION_RESET".equals(type)) {
            // Phase 2: close the connection abruptly (RST) before responding.
            // The handler is responsible for calling ctx.close() or setting
            // SO_LINGER=0 to send a TCP RST. No response body is sent.
            return FaultResult.connectionReset(fault);
        }
        if ("READ_TIMEOUT".equals(type)) {
            // Phase 2: never respond. The handler simply does nothing, letting
            // the client's read timeout fire. No response, no close.
            return FaultResult.readTimeout(fault);
        }
        // Phase 3: Kafka protocol faults.
        if ("KAFKA_NOT_LEADER_FOR_PARTITION".equals(type)) {
            int code = fault.getErrorCode() != null ? fault.getErrorCode() : 6;
            return FaultResult.kafkaError(code, fault);
        }
        if ("KAFKA_OFFSET_OUT_OF_RANGE".equals(type)) {
            int code = fault.getErrorCode() != null ? fault.getErrorCode() : 1;
            return FaultResult.kafkaError(code, fault);
        }
        if ("KAFKA_PRODUCE_THROTTLE".equals(type)) {
            long delay = Math.max(0, fault.getDelayMs());
            return FaultResult.kafkaThrottle(delay, fault);
        }
        if ("KAFKA_DELAY".equals(type)) {
            long delay = Math.max(0, fault.getDelayMs());
            return FaultResult.kafkaDelay(delay, fault);
        }
        if ("KAFKA_CONNECTION_RESET".equals(type)) {
            return FaultResult.kafkaConnectionReset(fault);
        }
        // Unknown fault type — log and treat as no fault so the normal flow proceeds.
        return FaultResult.noFault();
    }

    private static int pickStatus(List<Integer> statusCodes, Random random) {
        if (statusCodes == null || statusCodes.isEmpty()) {
            return 500; // default to 500 if misconfigured
        }
        // L11: filter out null entries — a misconfigured fault could carry a
        // list like [500, null, 503] from lax JSON parsing. Without this
        // filter, picking the null slot would NPE on auto-unboxing to int.
        // We iterate rather than stream-collect to avoid allocating a new list
        // on the hot fault-evaluation path.
        Integer first = null;
        int nonNullCount = 0;
        for (Integer code : statusCodes) {
            if (code != null) {
                if (first == null) first = code;
                nonNullCount++;
            }
        }
        if (nonNullCount == 0) {
            return 500; // all entries null — treat as misconfigured
        }
        if (nonNullCount == 1) {
            return first;
        }
        // Pick uniformly among the non-null entries. We re-roll on null hits
        // rather than building an index map — nulls are rare (misconfig),
        // so the expected number of iterations is ~1.
        int idx = random.nextInt(statusCodes.size());
        Integer picked = statusCodes.get(idx);
        while (picked == null) {
            idx = random.nextInt(statusCodes.size());
            picked = statusCodes.get(idx);
        }
        return picked;
    }

    /**
     * Result of fault evaluation.
     *
     * <p>One of several outcomes:
     * <ul>
     *   <li>{@link Action#NO_FAULT} — proceed with normal response</li>
     *   <li>{@link Action#HTTP_ERROR} — return an error response with {@link #statusCode}</li>
     *   <li>{@link Action#DELAY} — delay the normal response by {@link #delayMs}</li>
     *   <li>{@link Action#CONNECTION_RESET} — close the connection abruptly (Phase 2)</li>
     *   <li>{@link Action#READ_TIMEOUT} — never respond, let client time out (Phase 2)</li>
     *   <li>{@link Action#KAFKA_ERROR} — return Kafka error {@link #errorCode}</li>
     *   <li>{@link Action#KAFKA_THROTTLE} — set Kafka throttle_time_ms to {@link #delayMs}</li>
     *   <li>{@link Action#KAFKA_DELAY} — delay Kafka request processing by {@link #delayMs}</li>
     *   <li>{@link Action#KAFKA_CONNECTION_RESET} — close the Kafka client connection</li>
     * </ul>
     * </p>
     */
    public static final class FaultResult {

        public enum Action {
            NO_FAULT,
            HTTP_ERROR,
            DELAY,
            CONNECTION_RESET,
            READ_TIMEOUT,
            KAFKA_ERROR,
            KAFKA_THROTTLE,
            KAFKA_DELAY,
            KAFKA_CONNECTION_RESET
        }

        private final Action action;
        private final int statusCode;
        private final int errorCode;
        private final long delayMs;
        private final Fault triggeredFault;

        private FaultResult(Action action, int statusCode, int errorCode, long delayMs, Fault triggeredFault) {
            this.action = action;
            this.statusCode = statusCode;
            this.errorCode = errorCode;
            this.delayMs = delayMs;
            this.triggeredFault = triggeredFault;
        }

        public static FaultResult noFault() {
            return new FaultResult(Action.NO_FAULT, 0, 0, 0, null);
        }

        public static FaultResult httpError(int statusCode, Fault fault) {
            return new FaultResult(Action.HTTP_ERROR, statusCode, 0, 0, fault);
        }

        public static FaultResult delay(long delayMs, Fault fault) {
            return new FaultResult(Action.DELAY, 0, 0, delayMs, fault);
        }

        public static FaultResult connectionReset(Fault fault) {
            return new FaultResult(Action.CONNECTION_RESET, 0, 0, 0, fault);
        }

        public static FaultResult readTimeout(Fault fault) {
            return new FaultResult(Action.READ_TIMEOUT, 0, 0, 0, fault);
        }

        public static FaultResult kafkaError(int errorCode, Fault fault) {
            return new FaultResult(Action.KAFKA_ERROR, 0, errorCode, 0, fault);
        }

        public static FaultResult kafkaThrottle(long delayMs, Fault fault) {
            return new FaultResult(Action.KAFKA_THROTTLE, 0, 0, delayMs, fault);
        }

        public static FaultResult kafkaDelay(long delayMs, Fault fault) {
            return new FaultResult(Action.KAFKA_DELAY, 0, 0, delayMs, fault);
        }

        public static FaultResult kafkaConnectionReset(Fault fault) {
            return new FaultResult(Action.KAFKA_CONNECTION_RESET, 0, 0, 0, fault);
        }

        public Action getAction() { return action; }

        public boolean isNoFault() { return action == Action.NO_FAULT; }

        public boolean isHttpError() { return action == Action.HTTP_ERROR; }

        public boolean isDelay() { return action == Action.DELAY; }

        public boolean isConnectionReset() { return action == Action.CONNECTION_RESET; }

        public boolean isReadTimeout() { return action == Action.READ_TIMEOUT; }

        public boolean isKafkaError() { return action == Action.KAFKA_ERROR; }

        public boolean isKafkaThrottle() { return action == Action.KAFKA_THROTTLE; }

        public boolean isKafkaDelay() { return action == Action.KAFKA_DELAY; }

        public boolean isKafkaConnectionReset() { return action == Action.KAFKA_CONNECTION_RESET; }

        /** The HTTP status code to return (only valid when {@link #isHttpError()} is true). */
        public int getStatusCode() { return statusCode; }

        /** The Kafka error code to return (only valid when {@link #isKafkaError()} is true). */
        public int getErrorCode() { return errorCode; }

        /** The delay in milliseconds (only valid when {@link #isDelay()} or {@link #isKafkaDelay()} or {@link #isKafkaThrottle()} is true). */
        public long getDelayMs() { return delayMs; }

        /** The fault that was triggered, or null if no fault was hit. */
        public Fault getTriggeredFault() { return triggeredFault; }
    }
}
