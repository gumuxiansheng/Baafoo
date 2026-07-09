package com.baafoo.agent.advice;

import com.baafoo.core.model.EnvironmentMode;
import org.junit.Test;

import static org.junit.Assert.*;

/**
 * Tests for {@link ModeGates#shouldIntercept(EnvironmentMode)}.
 *
 * <p>ModeGates is a pure predicate — no static state, no side effects.
 * The logic is simple but critical: PASSTHROUGH means "don't intercept",
 * all other modes mean "intercept". Getting this wrong would break every
 * advice class that relies on it.</p>
 */
public class ModeGatesTest {

    @Test
    public void passthrough_returnsFalse() {
        assertFalse(ModeGates.shouldIntercept(EnvironmentMode.PASSTHROUGH));
    }

    @Test
    public void stub_returnsTrue() {
        assertTrue(ModeGates.shouldIntercept(EnvironmentMode.STUB));
    }

    @Test
    public void record_returnsTrue() {
        assertTrue(ModeGates.shouldIntercept(EnvironmentMode.RECORD));
    }

    @Test
    public void recordAndStub_returnsTrue() {
        assertTrue(ModeGates.shouldIntercept(EnvironmentMode.RECORD_AND_STUB));
    }

    @Test
    public void recordAll_returnsTrue() {
        assertTrue(ModeGates.shouldIntercept(EnvironmentMode.RECORD_ALL));
    }

    @Test
    public void null_returnsTrue() {
        // Null mode should be treated as "intercept" (defensive — don't skip interception
        // just because mode is somehow null). The calling advice should have already
        // checked for null, but ModeGates itself should not NPE.
        assertTrue(ModeGates.shouldIntercept(null));
    }
}
