package com.baafoo.agent.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Lightweight circuit breaker for ControlChannel HTTP calls.
 *
 * <p>States:
 * <ul>
 *   <li><b>CLOSED</b> — requests flow normally. Each failure increments the counter;
 *       at {@code failureThreshold} the breaker opens.</li>
 *   <li><b>OPEN</b> — requests are rejected immediately for {@code openTimeoutMs},
 *       giving the server time to recover. After the timeout, the breaker enters
 *       HALF_OPEN and allows a single probe request.</li>
 *   <li><b>HALF_OPEN</b> — one request is allowed through. On success the breaker
 *       closes; on failure it re-opens for another {@code openTimeoutMs}.</li>
 * </ul></p>
 *
 * <p>This prevents the 2-thread scheduler in {@link ControlChannel} from being
 * saturated by blocking HttpURLConnection calls when the server is down or slow.
 * Without the breaker, each scheduled heartbeat/poll would block a full
 * readTimeout (5-30s) on both scheduler threads, causing tasks to queue
 * indefinitely and the agent to become unresponsive.</p>
 *
 * <p>Thread-safe: all state is maintained in atomics.</p>
 */
public class CircuitBreaker {

    private static final Logger log = LoggerFactory.getLogger(CircuitBreaker.class);

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final String name;
    private final int failureThreshold;
    private final long openTimeoutMs;

    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
    private volatile long openedAt = 0;

    /**
     * @param name             human-readable label for logging
     * @param failureThreshold consecutive failures before opening
     * @param openTimeoutMs    time to stay open before allowing a probe
     */
    public CircuitBreaker(String name, int failureThreshold, long openTimeoutMs) {
        this.name = name;
        this.failureThreshold = failureThreshold;
        this.openTimeoutMs = openTimeoutMs;
    }

    /**
     * Check whether a request is allowed to proceed.
     *
     * <p>If the breaker is OPEN and the timeout has elapsed, atomically
     * transition to HALF_OPEN and allow exactly one probe request.</p>
     *
     * @return true if the request may proceed
     */
    public boolean allowRequest() {
        State current = state.get();
        if (current == State.CLOSED) {
            return true;
        }
        if (current == State.OPEN) {
            // Check if the open timeout has elapsed
            if (System.currentTimeMillis() - openedAt >= openTimeoutMs) {
                if (state.compareAndSet(State.OPEN, State.HALF_OPEN)) {
                    log.info("Circuit breaker [{}] transitioning OPEN → HALF_OPEN, allowing probe request", name);
                    return true;
                }
                // Lost the CAS race — another thread already transitioned
                return state.get() == State.CLOSED;
            }
            return false;
        }
        // HALF_OPEN: only the thread that won the OPEN→HALF_OPEN CAS may proceed.
        // Subsequent callers are rejected until the probe completes.
        return false;
    }

    /**
     * Report a successful request. Resets the failure counter and closes
     * the breaker if it was HALF_OPEN.
     */
    public void recordSuccess() {
        State current = state.get();
        if (current != State.CLOSED) {
            log.info("Circuit breaker [{}] closing (was {})", name, current);
            state.set(State.CLOSED);
        }
        consecutiveFailures.set(0);
    }

    /**
     * Report a failed request. Increments the failure counter and opens
     * the breaker if the threshold is reached.
     */
    public void recordFailure() {
        int failures = consecutiveFailures.incrementAndGet();
        State current = state.get();
        if (current == State.HALF_OPEN) {
            // Probe failed — re-open
            log.warn("Circuit breaker [{}] probe failed, re-opening (HALF_OPEN → OPEN)", name);
            openedAt = System.currentTimeMillis();
            state.set(State.OPEN);
        } else if (current == State.CLOSED && failures >= failureThreshold) {
            log.warn("Circuit breaker [{}] opening after {} consecutive failures", name, failures);
            openedAt = System.currentTimeMillis();
            state.set(State.OPEN);
        }
    }

    /**
     * @return current breaker state (for monitoring/diagnostics)
     */
    public State getState() {
        // Lazily transition OPEN → HALF_OPEN for accurate reporting
        State current = state.get();
        if (current == State.OPEN && System.currentTimeMillis() - openedAt >= openTimeoutMs) {
            // Don't actually transition here (that's allowRequest's job), just report
            return State.HALF_OPEN;
        }
        return current;
    }

    public int getConsecutiveFailures() {
        return consecutiveFailures.get();
    }
}
