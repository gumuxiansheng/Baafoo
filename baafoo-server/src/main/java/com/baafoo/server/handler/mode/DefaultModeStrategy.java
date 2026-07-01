package com.baafoo.server.handler.mode;

import com.baafoo.core.model.EnvironmentMode;

/**
 * Default {@link ModeStrategy} implementation covering all five environment
 * modes. The decision matrix:
 *
 * <table border="1">
 * <tr><th>Mode</th><th>matched?</th><th>forward</th><th>recordRequest</th><th>sendStub</th><th>recordUnmatched</th><th>recordResponse</th></tr>
 * <tr><td>STUB</td><td>yes</td><td>no</td><td>no</td><td>yes</td><td>no</td><td>no</td></tr>
 * <tr><td>PASSTHROUGH</td><td>yes</td><td>yes</td><td>no</td><td>no</td><td>no</td><td>no</td></tr>
 * <tr><td>RECORD</td><td>yes</td><td>yes</td><td>no</td><td>no</td><td>no</td><td>yes</td></tr>
 * <tr><td>RECORD_AND_STUB</td><td>yes</td><td>no</td><td>yes</td><td>yes</td><td>no</td><td>no</td></tr>
 * <tr><td>RECORD_ALL</td><td>yes</td><td>no</td><td>yes</td><td>yes</td><td>no</td><td>no</td></tr>
 * <tr><td>RECORD_ALL</td><td>no</td><td>yes</td><td>no</td><td>no</td><td>yes</td><td>no</td></tr>
 * <tr><td>(any other)</td><td>no</td><td>no</td><td>no</td><td>no</td><td>no</td><td>no</td></tr>
 * </table>
 *
 * <p>Handlers that cannot perform downstream forwarding (gRPC, TCP) should
 * detect {@code outcome.forwardToDownstream()} and substitute their own
 * fallback behavior (e.g., gRPC returns UNIMPLEMENTED).</p>
 */
public class DefaultModeStrategy implements ModeStrategy {

    public static final DefaultModeStrategy INSTANCE = new DefaultModeStrategy();

    @Override
    public ModeOutcome resolve(EnvironmentMode mode, boolean matched) {
        if (mode == null) {
            // Defensive: treat null as STUB.
            mode = EnvironmentMode.STUB;
        }

        if (matched) {
            switch (mode) {
                case PASSTHROUGH:
                    return new ModeOutcome(true, false, false, false, false);
                case RECORD:
                    // Forward to real backend, record the response.
                    return new ModeOutcome(true, false, false, false, true);
                case RECORD_AND_STUB:
                    // Stub the response, but record the stub request as well.
                    return new ModeOutcome(false, true, true, false, false);
                case RECORD_ALL:
                    // For matched traffic, RECORD_ALL behaves like RECORD_AND_STUB.
                    return new ModeOutcome(false, true, true, false, false);
                case STUB:
                default:
                    return new ModeOutcome(false, false, true, false, false);
            }
        } else {
            // Unmatched traffic.
            if (mode == EnvironmentMode.RECORD_ALL) {
                // RECORD_ALL is the only mode that records unmatched traffic.
                // Forward to real backend + record as unmatched.
                return new ModeOutcome(true, false, false, true, false);
            }
            // All other modes: no recording of unmatched traffic.
            // The handler decides between 404 and passthrough based on
            // unmatchedDefault config (not part of the mode decision).
            return new ModeOutcome(false, false, false, false, false);
        }
    }
}
