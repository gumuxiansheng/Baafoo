package com.baafoo.server.handler.mode;

import com.baafoo.core.model.EnvironmentMode;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Verifies the mode → outcome decision matrix for {@link DefaultModeStrategy}.
 * P1-1: ensures the consolidated dispatch matches the per-handler decision
 * trees documented in the architecture review.
 */
public class DefaultModeStrategyTest {

    private final ModeStrategy strategy = DefaultModeStrategy.INSTANCE;

    // ---- Matched rule ----

    @Test
    public void matchedStub() {
        ModeOutcome o = strategy.resolve(EnvironmentMode.STUB, true);
        assertFalse("STUB should not forward", o.forwardToDownstream());
        assertFalse("STUB should not record request", o.recordRequest());
        assertTrue("STUB should send stub response", o.sendStub());
        assertFalse("STUB should not record unmatched", o.recordUnmatched());
        assertFalse("STUB should not record response", o.recordResponse());
    }

    @Test
    public void matchedPassthrough() {
        ModeOutcome o = strategy.resolve(EnvironmentMode.PASSTHROUGH, true);
        assertTrue("PASSTHROUGH should forward", o.forwardToDownstream());
        assertFalse(o.recordRequest());
        assertFalse(o.sendStub());
        assertFalse(o.recordUnmatched());
        assertFalse(o.recordResponse());
    }

    @Test
    public void matchedRecord() {
        ModeOutcome o = strategy.resolve(EnvironmentMode.RECORD, true);
        assertTrue("RECORD should forward", o.forwardToDownstream());
        assertFalse(o.recordRequest());
        assertFalse(o.sendStub());
        assertFalse(o.recordUnmatched());
        assertTrue("RECORD should record the downstream response", o.recordResponse());
    }

    @Test
    public void matchedRecordAndStub() {
        ModeOutcome o = strategy.resolve(EnvironmentMode.RECORD_AND_STUB, true);
        assertFalse("RECORD_AND_STUB should not forward", o.forwardToDownstream());
        assertTrue("RECORD_AND_STUB should record the stub request", o.recordRequest());
        assertTrue("RECORD_AND_STUB should send stub response", o.sendStub());
        assertFalse(o.recordUnmatched());
        assertFalse(o.recordResponse());
    }

    @Test
    public void matchedRecordAll() {
        // Matched traffic in RECORD_ALL behaves like RECORD_AND_STUB.
        ModeOutcome o = strategy.resolve(EnvironmentMode.RECORD_ALL, true);
        assertFalse(o.forwardToDownstream());
        assertTrue(o.recordRequest());
        assertTrue(o.sendStub());
        assertFalse(o.recordUnmatched());
        assertFalse(o.recordResponse());
    }

    // ---- Unmatched ----

    @Test
    public void unmatchedStub() {
        ModeOutcome o = strategy.resolve(EnvironmentMode.STUB, false);
        assertFalse(o.forwardToDownstream());
        assertFalse(o.recordRequest());
        assertFalse(o.sendStub());
        assertFalse(o.recordUnmatched());
        assertFalse(o.recordResponse());
    }

    @Test
    public void unmatchedRecordAll() {
        // RECORD_ALL is the only mode that records unmatched traffic.
        ModeOutcome o = strategy.resolve(EnvironmentMode.RECORD_ALL, false);
        assertTrue("RECORD_ALL unmatched should forward", o.forwardToDownstream());
        assertFalse(o.recordRequest());
        assertFalse(o.sendStub());
        assertTrue("RECORD_ALL unmatched should record", o.recordUnmatched());
        assertFalse(o.recordResponse());
    }

    @Test
    public void unmatchedRecordDoesNotRecord() {
        // RECORD mode only records matched-request responses, not unmatched traffic.
        ModeOutcome o = strategy.resolve(EnvironmentMode.RECORD, false);
        assertFalse(o.forwardToDownstream());
        assertFalse(o.recordUnmatched());
        assertFalse("RECORD should not record unmatched traffic", o.shouldRecord());
    }

    // ---- shouldRecord convenience ----

    @Test
    public void shouldRecordTrueForAllRecordingModesWhenMatched() {
        assertTrue(strategy.resolve(EnvironmentMode.RECORD, true).shouldRecord());
        assertTrue(strategy.resolve(EnvironmentMode.RECORD_AND_STUB, true).shouldRecord());
        assertTrue(strategy.resolve(EnvironmentMode.RECORD_ALL, true).shouldRecord());
    }

    @Test
    public void shouldRecordFalseForStubAndPassthroughWhenMatched() {
        assertFalse(strategy.resolve(EnvironmentMode.STUB, true).shouldRecord());
        assertFalse(strategy.resolve(EnvironmentMode.PASSTHROUGH, true).shouldRecord());
    }

    @Test
    public void nullModeTreatedAsStub() {
        ModeOutcome o = strategy.resolve(null, true);
        assertTrue(o.sendStub());
        assertFalse(o.forwardToDownstream());
    }
}
