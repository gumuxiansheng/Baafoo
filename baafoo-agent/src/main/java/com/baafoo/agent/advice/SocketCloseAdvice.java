package com.baafoo.agent.advice;

import com.baafoo.agent.GlobalRouteState;
import net.bytebuddy.asm.Advice;

/**
 * Intercepts Socket.close() to clean up recording session tracking.
 *
 * <p><b>CRITICAL</b>: Like other Socket advice, this is inlined into
 * java.net.Socket by ByteBuddy and runs in the Bootstrap CL context.</p>
 */
public final class SocketCloseAdvice {

    private SocketCloseAdvice() {}

    @Advice.OnMethodEnter
    public static void beforeClose(@Advice.This Object socket) {
        try {
            int socketId = System.identityHashCode(socket);
            GlobalRouteState.stopRecording(socketId);
        } catch (Throwable t) {
            GlobalRouteState.logError("[Baafoo] SocketCloseAdvice error: " + t);
        }
    }
}
