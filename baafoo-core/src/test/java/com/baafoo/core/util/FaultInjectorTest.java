package com.baafoo.core.util;

import com.baafoo.core.model.FaultInjection;
import com.baafoo.core.model.FaultInjection.Fault;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.Random;

import static org.junit.Assert.*;

/**
 * Unit tests for {@link FaultInjector} (PRD §4 R-S12 Phase 1).
 *
 * <p>Tests cover HTTP_ERROR and DELAY fault types, probability evaluation,
 * first-hit-wins ordering, and edge cases.</p>
 */
public class FaultInjectorTest {

    // ===== Null / empty config =====

    @Test
    public void testNullConfigReturnsNoFault() {
        FaultInjector.FaultResult result = FaultInjector.evaluate(null, new Random(42));
        assertTrue(result.isNoFault());
        assertNull(result.getTriggeredFault());
    }

    @Test
    public void testEmptyFaultsReturnsNoFault() {
        FaultInjection config = new FaultInjection();
        config.setFaults(Collections.<Fault>emptyList());
        FaultInjector.FaultResult result = FaultInjector.evaluate(config, new Random(42));
        assertTrue(result.isNoFault());
    }

    @Test
    public void testNullFaultsListReturnsNoFault() {
        FaultInjection config = new FaultInjection();
        config.setFaults(null);
        FaultInjector.FaultResult result = FaultInjector.evaluate(config, new Random(42));
        assertTrue(result.isNoFault());
    }

    @Test(expected = IllegalArgumentException.class)
    public void testNullRandomThrowsException() {
        FaultInjection config = new FaultInjection();
        Fault fault = new Fault();
        fault.setType("HTTP_ERROR");
        fault.setProbability(1.0);
        fault.setStatusCodes(Arrays.asList(503));
        config.setFaults(Arrays.asList(fault));
        FaultInjector.evaluate(config, null);
    }

    // ===== HTTP_ERROR =====

    @Test
    public void testHttpErrorAlwaysTriggersWithProbabilityOne() {
        FaultInjection config = buildHttpErrorConfig(1.0, Arrays.asList(503));
        // Run multiple times — probability 1.0 should always trigger
        Random random = new Random(0);
        for (int i = 0; i < 20; i++) {
            FaultInjector.FaultResult result = FaultInjector.evaluate(config, random);
            assertTrue("Iteration " + i + " should trigger HTTP_ERROR", result.isHttpError());
            assertEquals(503, result.getStatusCode());
            assertNotNull(result.getTriggeredFault());
            assertEquals("HTTP_ERROR", result.getTriggeredFault().getType());
        }
    }

    @Test
    public void testHttpErrorNeverTriggersWithProbabilityZero() {
        FaultInjection config = buildHttpErrorConfig(0.0, Arrays.asList(503));
        Random random = new Random(0);
        for (int i = 0; i < 20; i++) {
            FaultInjector.FaultResult result = FaultInjector.evaluate(config, random);
            assertTrue(result.isNoFault());
        }
    }

    @Test
    public void testHttpErrorPicksFromMultipleStatusCodes() {
        FaultInjection config = buildHttpErrorConfig(1.0, Arrays.asList(503, 504));
        Random random = new Random(123);
        boolean got503 = false, got504 = false;
        for (int i = 0; i < 100; i++) {
            FaultInjector.FaultResult result = FaultInjector.evaluate(config, random);
            assertTrue(result.isHttpError());
            int code = result.getStatusCode();
            assertTrue("Status code should be 503 or 504, got " + code,
                    code == 503 || code == 504);
            if (code == 503) got503 = true;
            if (code == 504) got504 = true;
        }
        assertTrue("Should have seen 503 at least once", got503);
        assertTrue("Should have seen 504 at least once", got504);
    }

    @Test
    public void testHttpErrorSingleStatusCode() {
        FaultInjection config = buildHttpErrorConfig(1.0, Arrays.asList(429));
        FaultInjector.FaultResult result = FaultInjector.evaluate(config, new Random(0));
        assertTrue(result.isHttpError());
        assertEquals(429, result.getStatusCode());
    }

    @Test
    public void testHttpErrorEmptyStatusCodesDefaultsTo500() {
        FaultInjection config = buildHttpErrorConfig(1.0, Collections.<Integer>emptyList());
        FaultInjector.FaultResult result = FaultInjector.evaluate(config, new Random(0));
        assertTrue(result.isHttpError());
        assertEquals(500, result.getStatusCode());
    }

    @Test
    public void testHttpErrorNullStatusCodesDefaultsTo500() {
        FaultInjection config = buildHttpErrorConfig(1.0, null);
        FaultInjector.FaultResult result = FaultInjector.evaluate(config, new Random(0));
        assertTrue(result.isHttpError());
        assertEquals(500, result.getStatusCode());
    }

    // ===== DELAY =====

    @Test
    public void testDelayAlwaysTriggersWithProbabilityOne() {
        FaultInjection config = buildDelayConfig(1.0, 2000);
        Random random = new Random(0);
        for (int i = 0; i < 20; i++) {
            FaultInjector.FaultResult result = FaultInjector.evaluate(config, random);
            assertTrue("Iteration " + i + " should trigger DELAY", result.isDelay());
            assertEquals(2000, result.getDelayMs());
            assertNotNull(result.getTriggeredFault());
            assertEquals("DELAY", result.getTriggeredFault().getType());
        }
    }

    @Test
    public void testDelayNeverTriggersWithProbabilityZero() {
        FaultInjection config = buildDelayConfig(0.0, 2000);
        Random random = new Random(0);
        for (int i = 0; i < 20; i++) {
            FaultInjector.FaultResult result = FaultInjector.evaluate(config, random);
            assertTrue(result.isNoFault());
        }
    }

    @Test
    public void testDelayNegativeClampedToZero() {
        FaultInjection config = buildDelayConfig(1.0, -100);
        FaultInjector.FaultResult result = FaultInjector.evaluate(config, new Random(0));
        assertTrue(result.isDelay());
        assertEquals(0, result.getDelayMs());
    }

    @Test
    public void testDelayZeroMs() {
        FaultInjection config = buildDelayConfig(1.0, 0);
        FaultInjector.FaultResult result = FaultInjector.evaluate(config, new Random(0));
        assertTrue(result.isDelay());
        assertEquals(0, result.getDelayMs());
    }

    // ===== First-hit-wins ordering =====

    @Test
    public void testFirstHitWinsHttpErrorBeforeDelay() {
        // HTTP_ERROR (p=1.0) before DELAY (p=1.0) → HTTP_ERROR wins
        Fault httpError = new Fault();
        httpError.setType("HTTP_ERROR");
        httpError.setProbability(1.0);
        httpError.setStatusCodes(Arrays.asList(503));

        Fault delay = new Fault();
        delay.setType("DELAY");
        delay.setProbability(1.0);
        delay.setDelayMs(2000);

        FaultInjection config = new FaultInjection();
        config.setFaults(Arrays.asList(httpError, delay));

        FaultInjector.FaultResult result = FaultInjector.evaluate(config, new Random(0));
        assertTrue(result.isHttpError());
        assertEquals(503, result.getStatusCode());
    }

    @Test
    public void testFirstHitWinsDelayWhenHttpErrorMisses() {
        // HTTP_ERROR (p=0.0) before DELAY (p=1.0) → DELAY wins
        Fault httpError = new Fault();
        httpError.setType("HTTP_ERROR");
        httpError.setProbability(0.0);
        httpError.setStatusCodes(Arrays.asList(503));

        Fault delay = new Fault();
        delay.setType("DELAY");
        delay.setProbability(1.0);
        delay.setDelayMs(2000);

        FaultInjection config = new FaultInjection();
        config.setFaults(Arrays.asList(httpError, delay));

        FaultInjector.FaultResult result = FaultInjector.evaluate(config, new Random(0));
        assertTrue(result.isDelay());
        assertEquals(2000, result.getDelayMs());
    }

    @Test
    public void testAllFaultsMissReturnsNoFault() {
        Fault httpError = new Fault();
        httpError.setType("HTTP_ERROR");
        httpError.setProbability(0.0);
        httpError.setStatusCodes(Arrays.asList(503));

        Fault delay = new Fault();
        delay.setType("DELAY");
        delay.setProbability(0.0);
        delay.setDelayMs(2000);

        FaultInjection config = new FaultInjection();
        config.setFaults(Arrays.asList(httpError, delay));

        FaultInjector.FaultResult result = FaultInjector.evaluate(config, new Random(0));
        assertTrue(result.isNoFault());
    }

    // ===== Edge cases =====

    @Test
    public void testUnknownFaultTypeReturnsNoFault() {
        Fault unknown = new Fault();
        unknown.setType("UNKNOWN_TYPE");
        unknown.setProbability(1.0);

        FaultInjection config = new FaultInjection();
        config.setFaults(Arrays.asList(unknown));

        FaultInjector.FaultResult result = FaultInjector.evaluate(config, new Random(0));
        assertTrue(result.isNoFault());
    }

    @Test
    public void testNullFaultInListSkipped() {
        Fault delay = new Fault();
        delay.setType("DELAY");
        delay.setProbability(1.0);
        delay.setDelayMs(500);

        FaultInjection config = new FaultInjection();
        config.setFaults(Arrays.asList(null, delay));

        FaultInjector.FaultResult result = FaultInjector.evaluate(config, new Random(0));
        assertTrue(result.isDelay());
        assertEquals(500, result.getDelayMs());
    }

    @Test
    public void testFaultWithNullTypeSkipped() {
        Fault nullType = new Fault();
        nullType.setType(null);
        nullType.setProbability(1.0);

        Fault delay = new Fault();
        delay.setType("DELAY");
        delay.setProbability(1.0);
        delay.setDelayMs(500);

        FaultInjection config = new FaultInjection();
        config.setFaults(Arrays.asList(nullType, delay));

        FaultInjector.FaultResult result = FaultInjector.evaluate(config, new Random(0));
        assertTrue(result.isDelay());
        assertEquals(500, result.getDelayMs());
    }

    @Test
    public void testSeededRandomDeterministic() {
        FaultInjection config = buildHttpErrorConfig(1.0, Arrays.asList(503, 504, 500));

        // Same seed → same sequence of results
        FaultInjector.FaultResult r1 = FaultInjector.evaluate(config, new Random(42));
        FaultInjector.FaultResult r2 = FaultInjector.evaluate(config, new Random(42));
        assertEquals(r1.getStatusCode(), r2.getStatusCode());
    }

    @Test
    public void testProbabilityBoundaryHalf() {
        // With probability 0.5, roughly half should trigger over many runs
        FaultInjection config = buildHttpErrorConfig(0.5, Arrays.asList(503));
        Random random = new Random(0);
        int triggered = 0;
        int total = 1000;
        for (int i = 0; i < total; i++) {
            FaultInjector.FaultResult result = FaultInjector.evaluate(config, random);
            if (result.isHttpError()) triggered++;
        }
        // Should be roughly 500 ± 50 (allowing statistical variance)
        assertTrue("Triggered count " + triggered + " should be near 500",
                triggered > 400 && triggered < 600);
    }

    @Test
    public void testFaultResultActionEnum() {
        assertEquals(FaultInjector.FaultResult.Action.NO_FAULT,
                FaultInjector.FaultResult.noFault().getAction());
        assertEquals(FaultInjector.FaultResult.Action.HTTP_ERROR,
                FaultInjector.FaultResult.httpError(503, null).getAction());
        assertEquals(FaultInjector.FaultResult.Action.DELAY,
                FaultInjector.FaultResult.delay(100, null).getAction());
    }

    @Test
    public void testFaultResultConvenienceMethods() {
        FaultInjector.FaultResult noFault = FaultInjector.FaultResult.noFault();
        assertTrue(noFault.isNoFault());
        assertFalse(noFault.isHttpError());
        assertFalse(noFault.isDelay());
        assertEquals(0, noFault.getStatusCode());
        assertEquals(0, noFault.getDelayMs());
        assertNull(noFault.getTriggeredFault());

        Fault dummy = new Fault();
        FaultInjector.FaultResult httpError = FaultInjector.FaultResult.httpError(503, dummy);
        assertFalse(httpError.isNoFault());
        assertTrue(httpError.isHttpError());
        assertEquals(503, httpError.getStatusCode());
        assertEquals(0, httpError.getDelayMs());
        assertSame(dummy, httpError.getTriggeredFault());

        FaultInjector.FaultResult delay = FaultInjector.FaultResult.delay(2000, dummy);
        assertFalse(delay.isNoFault());
        assertTrue(delay.isDelay());
        assertEquals(0, delay.getStatusCode());
        assertEquals(2000, delay.getDelayMs());
        assertSame(dummy, delay.getTriggeredFault());
    }

    // ===== PRD example scenario =====

    @Test
    public void testPrdExampleScenario() {
        // PRD §4 R-C2 example:
        //   HTTP_ERROR probability: 0.2, statusCodes: [503, 504]
        //   DELAY probability: 0.5, delayMs: 2000
        // With first-hit-wins: ~20% HTTP_ERROR, ~40% DELAY (0.5 * 0.8), ~40% normal
        Fault httpError = new Fault();
        httpError.setType("HTTP_ERROR");
        httpError.setProbability(0.2);
        httpError.setStatusCodes(Arrays.asList(503, 504));

        Fault delay = new Fault();
        delay.setType("DELAY");
        delay.setProbability(0.5);
        delay.setDelayMs(2000);

        FaultInjection config = new FaultInjection();
        config.setFaults(Arrays.asList(httpError, delay));

        Random random = new Random(0);
        int httpErrorCount = 0, delayCount = 0, noFaultCount = 0;
        int total = 10000;
        for (int i = 0; i < total; i++) {
            FaultInjector.FaultResult result = FaultInjector.evaluate(config, random);
            if (result.isHttpError()) {
                httpErrorCount++;
                int code = result.getStatusCode();
                assertTrue(code == 503 || code == 504);
            } else if (result.isDelay()) {
                delayCount++;
                assertEquals(2000, result.getDelayMs());
            } else {
                noFaultCount++;
            }
        }
        // Expected: ~2000 HTTP_ERROR, ~4000 DELAY, ~4000 no-fault
        // Allow ±10% tolerance
        assertTrue("HTTP_ERROR count " + httpErrorCount + " should be near 2000",
                httpErrorCount > 1500 && httpErrorCount < 2500);
        assertTrue("DELAY count " + delayCount + " should be near 4000",
                delayCount > 3500 && delayCount < 4500);
        assertTrue("No-fault count " + noFaultCount + " should be near 4000",
                noFaultCount > 3500 && noFaultCount < 4500);
    }

    // ===== Helper methods =====

    private FaultInjection buildHttpErrorConfig(double probability, java.util.List<Integer> statusCodes) {
        Fault fault = new Fault();
        fault.setType("HTTP_ERROR");
        fault.setProbability(probability);
        fault.setStatusCodes(statusCodes);
        FaultInjection config = new FaultInjection();
        config.setFaults(Arrays.asList(fault));
        return config;
    }

    private FaultInjection buildDelayConfig(double probability, long delayMs) {
        Fault fault = new Fault();
        fault.setType("DELAY");
        fault.setProbability(probability);
        fault.setDelayMs(delayMs);
        FaultInjection config = new FaultInjection();
        config.setFaults(Arrays.asList(fault));
        return config;
    }
}
