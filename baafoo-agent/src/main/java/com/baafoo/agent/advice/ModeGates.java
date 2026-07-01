package com.baafoo.agent.advice;

import com.baafoo.core.model.EnvironmentMode;

/**
 * Static gate helpers for the agent's App-CL advice classes (Kafka / JMS /
 * Pulsar / gRPC). These advice classes all share an identical 4-line idiom:
 * check PASSTHROUGH early-return, then check whether any routes exist for the
 * protocol before consulting the plugin and redirecting.
 *
 * <p>P1-1: consolidates the duplicated {@code if (mode == PASSTHROUGH) return;}
 * idiom into a single named helper so the intent is explicit. A full Strategy
 * object is not warranted here — the App-CL advice deliberately defers the
 * record-vs-stub distinction to the server-side handler.</p>
 *
 * <p>This class lives in {@code baafoo-agent} (App CL) and may reference
 * {@code baafoo-core}'s {@link EnvironmentMode} directly (the agent's App CL
 * sees the relocated {@code com.baafoo.agent.shaded.core.model.EnvironmentMode}
 * — but because the agent is the only consumer of these helpers, the
 * relocation is consistent within the agent JAR).</p>
 */
public final class ModeGates {

    private ModeGates() {}

    /**
     * @return {@code true} if the agent should intercept the connection in the
     *         given mode (i.e., the mode is anything other than PASSTHROUGH).
     */
    public static boolean shouldIntercept(EnvironmentMode mode) {
        return mode != EnvironmentMode.PASSTHROUGH;
    }
}
