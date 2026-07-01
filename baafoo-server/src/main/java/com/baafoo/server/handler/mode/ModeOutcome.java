package com.baafoo.server.handler.mode;

/**
 * Pure-data description of the actions a handler should take for the current
 * request, derived from the active {@link com.baafoo.core.model.EnvironmentMode}.
 *
 * <p>The handler is responsible for interpreting these flags according to its
 * own capabilities. For example, gRPC and TCP handlers do not support
 * downstream forwarding; they should treat {@code forwardToDownstream=true}
 * as "not supported, fall back to the handler-specific behavior" (e.g., gRPC
 * returns UNIMPLEMENTED).</p>
 *
 * @param forwardToDownstream whether to proxy the request to the real backend
 * @param recordRequest       whether to record the (stub) request as a RecordingEntry
 * @param sendStub            whether to send a stub response back to the client
 * @param recordUnmatched     whether to record the request even when unmatched
 * @param recordResponse      whether to record the downstream response (RECORD mode);
 *                            when false, RECORD_AND_STUB records the stub request instead
 */
public final class ModeOutcome {

    private final boolean forwardToDownstream;
    private final boolean recordRequest;
    private final boolean sendStub;
    private final boolean recordUnmatched;
    private final boolean recordResponse;

    public ModeOutcome(boolean forwardToDownstream,
                       boolean recordRequest,
                       boolean sendStub,
                       boolean recordUnmatched,
                       boolean recordResponse) {
        this.forwardToDownstream = forwardToDownstream;
        this.recordRequest = recordRequest;
        this.sendStub = sendStub;
        this.recordUnmatched = recordUnmatched;
        this.recordResponse = recordResponse;
    }

    public boolean forwardToDownstream() { return forwardToDownstream; }
    public boolean recordRequest() { return recordRequest; }
    public boolean sendStub() { return sendStub; }
    public boolean recordUnmatched() { return recordUnmatched; }
    public boolean recordResponse() { return recordResponse; }

    /**
     * Convenience: should we record at all (either request or response)?
     * Equivalent to {@code recordRequest || recordResponse || recordUnmatched}.
     */
    public boolean shouldRecord() {
        return recordRequest || recordResponse || recordUnmatched;
    }

    @Override
    public String toString() {
        return "ModeOutcome{forward=" + forwardToDownstream
                + ", recordRequest=" + recordRequest
                + ", sendStub=" + sendStub
                + ", recordUnmatched=" + recordUnmatched
                + ", recordResponse=" + recordResponse + '}';
    }
}
