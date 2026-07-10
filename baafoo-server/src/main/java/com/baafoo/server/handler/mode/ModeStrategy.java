package com.baafoo.server.handler.mode;

import com.baafoo.core.model.EnvironmentMode;

/**
 * Resolves an {@link EnvironmentMode} into a {@link ModeOutcome} — a pure-data
 * description of what the handler should do for the current request.
 *
 * <p>P1-1: This abstraction replaces the duplicated 5-way mode dispatch trees
 * that previously appeared in {@code HttpStubHandler}, {@code GrpcUnifiedHandler},
 * {@code TcpStubHandler}, and the MQ brokers. The
 * handler keeps full control of the actual I/O (forwarding, recording, stub
 * response) — the strategy only decides <em>which</em> operations to perform.</p>
 *
 * <p>This interface lives in {@code baafoo-server} and may freely reference
 * {@code baafoo-core} types. It is NOT loadable from the Bootstrap ClassLoader
 * (it would be relocated by the agent shade rules); the agent-side mode dispatch
 * uses the static int-based helpers in {@code GlobalRouteState} instead.</p>
 */
public interface ModeStrategy {

    /**
     * Resolve the outcome for the current request.
     *
     * @param mode    the active environment mode
     * @param matched whether a rule matched the incoming request
     * @return a non-null {@link ModeOutcome} describing the actions to take
     */
    ModeOutcome resolve(EnvironmentMode mode, boolean matched);
}
