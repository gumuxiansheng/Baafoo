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
            double roll = random.nextDouble();
            if (roll >= fault.getProbability()) {
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
        // Unknown fault type — log and treat as no fault so the normal flow proceeds.
        // (Phase 2 will add CONNECTION_RESET and READ_TIMEOUT here.)
        return FaultResult.noFault();
    }

    private static int pickStatus(List<Integer> statusCodes, Random random) {
        if (statusCodes == null || statusCodes.isEmpty()) {
            return 500; // default to 500 if misconfigured
        }
        if (statusCodes.size() == 1) {
            return statusCodes.get(0);
        }
        int idx = random.nextInt(statusCodes.size());
        return statusCodes.get(idx);
    }

    /**
     * Result of fault evaluation.
     *
     * <p>One of three outcomes:
     * <ul>
     *   <li>{@link Action#NO_FAULT} — proceed with normal response</li>
     *   <li>{@link Action#HTTP_ERROR} — return an error response with {@link #statusCode}</li>
     *   <li>{@link Action#DELAY} — delay the normal response by {@link #delayMs}</li>
     * </ul>
     * </p>
     */
    public static final class FaultResult {

        public enum Action {
            NO_FAULT,
            HTTP_ERROR,
            DELAY
        }

        private final Action action;
        private final int statusCode;
        private final long delayMs;
        private final Fault triggeredFault;

        private FaultResult(Action action, int statusCode, long delayMs, Fault triggeredFault) {
            this.action = action;
            this.statusCode = statusCode;
            this.delayMs = delayMs;
            this.triggeredFault = triggeredFault;
        }

        public static FaultResult noFault() {
            return new FaultResult(Action.NO_FAULT, 0, 0, null);
        }

        public static FaultResult httpError(int statusCode, Fault fault) {
            return new FaultResult(Action.HTTP_ERROR, statusCode, 0, fault);
        }

        public static FaultResult delay(long delayMs, Fault fault) {
            return new FaultResult(Action.DELAY, 0, delayMs, fault);
        }

        public Action getAction() { return action; }

        public boolean isNoFault() { return action == Action.NO_FAULT; }

        public boolean isHttpError() { return action == Action.HTTP_ERROR; }

        public boolean isDelay() { return action == Action.DELAY; }

        /** The HTTP status code to return (only valid when {@link #isHttpError()} is true). */
        public int getStatusCode() { return statusCode; }

        /** The delay in milliseconds (only valid when {@link #isDelay()} is true). */
        public long getDelayMs() { return delayMs; }

        /** The fault that was triggered, or null if no fault was hit. */
        public Fault getTriggeredFault() { return triggeredFault; }
    }
}
