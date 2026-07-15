package com.baafoo.agent.channel;

import org.junit.Test;

import static org.junit.Assert.*;

/**
 * CircuitBreaker 单元测试。
 *
 * <p>覆盖三态机（CLOSED/OPEN/HALF_OPEN）的全部转移路径，包括：
 * <ul>
 *   <li>CLOSED 状态下允许请求 + 失败计数累积到阈值触发 OPEN</li>
 *   <li>OPEN 状态下拒绝请求 + 超时后允许单次探针（HALF_OPEN）</li>
 *   <li>HALF_OPEN 探针成功 → CLOSED</li>
 *   <li>HALF_OPEN 探针失败 → 重新 OPEN</li>
 *   <li>成功调用重置失败计数</li>
 *   <li>getState() 在超时后的惰性 HALF_OPEN 报告</li>
 * </ul></p>
 *
 * <p>使用极短的 openTimeoutMs（如 50ms）+ Thread.sleep 验证时间触发路径。
 * 测试串行执行（无并发），避免 AtomicReference CAS 竞争。</p>
 */
public class CircuitBreakerTest {

    @Test
    public void startsInClosedStateAndAllowsRequests() {
        CircuitBreaker cb = new CircuitBreaker("test", 3, 1000);
        assertEquals(CircuitBreaker.State.CLOSED, cb.getState());
        assertTrue("CLOSED state should allow requests", cb.allowRequest());
        assertEquals(0, cb.getConsecutiveFailures());
    }

    @Test
    public void staysClosedBelowFailureThreshold() {
        CircuitBreaker cb = new CircuitBreaker("test", 3, 1000);
        cb.recordFailure();
        cb.recordFailure();
        assertEquals(CircuitBreaker.State.CLOSED, cb.getState());
        assertTrue("Should still allow requests below threshold", cb.allowRequest());
        assertEquals(2, cb.getConsecutiveFailures());
    }

    @Test
    public void opensAtFailureThreshold() {
        CircuitBreaker cb = new CircuitBreaker("test", 3, 1000);
        cb.recordFailure();
        cb.recordFailure();
        cb.recordFailure();
        assertEquals("Third failure should open the breaker",
                CircuitBreaker.State.OPEN, cb.getState());
        assertFalse("OPEN state should reject requests", cb.allowRequest());
    }

    @Test
    public void successResetsFailureCounterInClosedState() {
        CircuitBreaker cb = new CircuitBreaker("test", 3, 1000);
        cb.recordFailure();
        cb.recordFailure();
        cb.recordSuccess();
        assertEquals(0, cb.getConsecutiveFailures());
        assertEquals(CircuitBreaker.State.CLOSED, cb.getState());
    }

    @Test
    public void openRejectsAllRequestsBeforeTimeout() {
        CircuitBreaker cb = new CircuitBreaker("test", 1, 10000);
        cb.recordFailure(); // opens immediately
        assertEquals(CircuitBreaker.State.OPEN, cb.getState());
        assertFalse(cb.allowRequest());
        assertFalse(cb.allowRequest());
        assertFalse(cb.allowRequest());
    }

    @Test
    public void openTransitionsToHalfOpenAfterTimeout() throws InterruptedException {
        CircuitBreaker cb = new CircuitBreaker("test", 1, 50);
        cb.recordFailure(); // opens
        assertEquals(CircuitBreaker.State.OPEN, cb.getState());

        // Wait for timeout to elapse
        Thread.sleep(80);

        // First allowRequest() should win the CAS and transition to HALF_OPEN
        assertTrue("After timeout, first request should be allowed as probe",
                cb.allowRequest());
        // Subsequent requests should be rejected while in HALF_OPEN
        assertFalse("HALF_OPEN should reject subsequent requests until probe completes",
                cb.allowRequest());
    }

    @Test
    public void halfOpenProbeSuccessClosesBreaker() throws InterruptedException {
        CircuitBreaker cb = new CircuitBreaker("test", 1, 50);
        cb.recordFailure(); // opens
        Thread.sleep(80);

        assertTrue(cb.allowRequest()); // probe allowed, transitions to HALF_OPEN
        cb.recordSuccess();

        assertEquals("Probe success should close the breaker",
                CircuitBreaker.State.CLOSED, cb.getState());
        assertTrue("CLOSED should allow requests again", cb.allowRequest());
        assertEquals(0, cb.getConsecutiveFailures());
    }

    @Test
    public void halfOpenProbeFailureReopensBreaker() throws InterruptedException {
        CircuitBreaker cb = new CircuitBreaker("test", 1, 50);
        cb.recordFailure(); // opens
        Thread.sleep(80);

        assertTrue(cb.allowRequest()); // probe allowed, transitions to HALF_OPEN
        cb.recordFailure(); // probe fails

        assertEquals("Probe failure should re-open the breaker",
                CircuitBreaker.State.OPEN, cb.getState());
        assertFalse("Re-opened breaker should reject requests", cb.allowRequest());
    }

    @Test
    public void getStateReportsHalfOpenAfterTimeoutEvenWithoutAllowRequest() throws InterruptedException {
        CircuitBreaker cb = new CircuitBreaker("test", 1, 50);
        cb.recordFailure(); // opens
        assertEquals(CircuitBreaker.State.OPEN, cb.getState());

        Thread.sleep(80);

        // getState() should lazily report HALF_OPEN without actually transitioning
        assertEquals("getState should report HALF_OPEN after timeout",
                CircuitBreaker.State.HALF_OPEN, cb.getState());
    }

    @Test
    public void fullRecoveryCycle() throws InterruptedException {
        CircuitBreaker cb = new CircuitBreaker("test", 2, 50);

        // CLOSED → OPEN (2 failures)
        cb.recordFailure();
        cb.recordFailure();
        assertEquals(CircuitBreaker.State.OPEN, cb.getState());
        assertFalse(cb.allowRequest());

        // OPEN → HALF_OPEN (timeout)
        Thread.sleep(80);
        assertTrue(cb.allowRequest());

        // HALF_OPEN → CLOSED (probe success)
        cb.recordSuccess();
        assertEquals(CircuitBreaker.State.CLOSED, cb.getState());

        // Verify normal operation resumed
        assertTrue(cb.allowRequest());
        cb.recordSuccess();
        assertEquals(0, cb.getConsecutiveFailures());
    }

    @Test
    public void failuresInClosedStateDoNotExceedThresholdRepeatedly() {
        CircuitBreaker cb = new CircuitBreaker("test", 3, 1000);
        // Two failures then success, repeated — should never open
        cb.recordFailure();
        cb.recordFailure();
        cb.recordSuccess();
        cb.recordFailure();
        cb.recordFailure();
        cb.recordSuccess();
        assertEquals(CircuitBreaker.State.CLOSED, cb.getState());
        assertTrue(cb.allowRequest());
    }

    @Test
    public void thresholdOfOneOpensOnFirstFailure() {
        CircuitBreaker cb = new CircuitBreaker("test", 1, 1000);
        cb.recordFailure();
        assertEquals(CircuitBreaker.State.OPEN, cb.getState());
        assertFalse(cb.allowRequest());
    }
}
