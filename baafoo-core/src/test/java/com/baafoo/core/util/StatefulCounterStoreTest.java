package com.baafoo.core.util;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for {@link StatefulCounterStore}.
 *
 * <p>Covers the per-rule request counter used by stateful Mock
 * (PRD §3 R-S2 AC-13). Each test resets the global store beforehand to
 * ensure isolation.</p>
 */
public class StatefulCounterStoreTest {

    private StatefulCounterStore store;

    @Before
    public void setUp() {
        store = StatefulCounterStore.global();
        store.resetAll();
    }

    @After
    public void tearDown() {
        store.resetAll();
    }

    @Test
    public void testIncrementAndGetIsOneBased() {
        assertEquals(1, store.incrementAndGet("rule-1"));
        assertEquals(2, store.incrementAndGet("rule-1"));
        assertEquals(3, store.incrementAndGet("rule-1"));
    }

    @Test
    public void testIndependentCountersPerRule() {
        assertEquals(1, store.incrementAndGet("rule-a"));
        assertEquals(1, store.incrementAndGet("rule-b"));
        assertEquals(2, store.incrementAndGet("rule-a"));
        assertEquals(2, store.incrementAndGet("rule-b"));
    }

    @Test
    public void testGetWithoutIncrement() {
        assertEquals(0, store.get("rule-1"));
        store.incrementAndGet("rule-1");
        store.incrementAndGet("rule-1");
        assertEquals(2, store.get("rule-1"));
    }

    @Test
    public void testGetNullRuleId() {
        assertEquals(0, store.get(null));
        assertEquals(0, store.get(""));
    }

    @Test
    public void testIncrementNullRuleId() {
        assertEquals(0, store.incrementAndGet(null));
        assertEquals(0, store.incrementAndGet(""));
    }

    @Test
    public void testResetSpecificRule() {
        store.incrementAndGet("rule-1");
        store.incrementAndGet("rule-1");
        store.incrementAndGet("rule-2");

        store.reset("rule-1");

        assertEquals(0, store.get("rule-1"));
        assertEquals(1, store.get("rule-2"));
    }

    @Test
    public void testResetAll() {
        store.incrementAndGet("rule-1");
        store.incrementAndGet("rule-2");
        store.incrementAndGet("rule-3");

        store.resetAll();

        assertEquals(0, store.get("rule-1"));
        assertEquals(0, store.get("rule-2"));
        assertEquals(0, store.get("rule-3"));
        assertEquals(0, store.size());
    }

    @Test
    public void testResetIfThresholdNotReached() {
        store.incrementAndGet("rule-1"); // count=1
        boolean reset = store.resetIfThreshold("rule-1", 5);
        assertFalse(reset);
        assertEquals(1, store.get("rule-1"));
    }

    @Test
    public void testResetIfThresholdReached() {
        store.incrementAndGet("rule-1"); // count=1
        store.incrementAndGet("rule-1"); // count=2
        store.incrementAndGet("rule-1"); // count=3
        boolean reset = store.resetIfThreshold("rule-1", 3);
        assertTrue(reset);
        assertEquals(0, store.get("rule-1"));
    }

    @Test
    public void testResetIfThresholdExceeded() {
        store.incrementAndGet("rule-1"); // count=1
        store.incrementAndGet("rule-1"); // count=2
        store.incrementAndGet("rule-1"); // count=3
        store.incrementAndGet("rule-1"); // count=4
        boolean reset = store.resetIfThreshold("rule-1", 3);
        assertTrue(reset);
        assertEquals(0, store.get("rule-1"));
    }

    @Test
    public void testResetIfThresholdZeroOrNegative() {
        store.incrementAndGet("rule-1");
        assertFalse(store.resetIfThreshold("rule-1", 0));
        assertFalse(store.resetIfThreshold("rule-1", -1));
    }

    @Test
    public void testResetIfThresholdNullRuleId() {
        assertFalse(store.resetIfThreshold(null, 3));
        assertFalse(store.resetIfThreshold("", 3));
    }

    @Test
    public void testResetIfThresholdNoCounter() {
        assertFalse(store.resetIfThreshold("nonexistent", 3));
    }

    @Test
    public void testSize() {
        assertEquals(0, store.size());
        store.incrementAndGet("rule-1");
        assertEquals(1, store.size());
        store.incrementAndGet("rule-2");
        assertEquals(2, store.size());
        store.reset("rule-1");
        assertEquals(1, store.size());
    }

    @Test
    public void testCounterContinuesAfterReset() {
        store.incrementAndGet("rule-1"); // count=1
        store.incrementAndGet("rule-1"); // count=2
        store.reset("rule-1");
        assertEquals(1, store.incrementAndGet("rule-1")); // starts from 1 again
    }
}
