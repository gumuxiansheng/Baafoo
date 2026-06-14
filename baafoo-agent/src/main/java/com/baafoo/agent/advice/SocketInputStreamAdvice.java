package com.baafoo.agent.advice;

import com.baafoo.agent.GlobalRouteState;
import net.bytebuddy.asm.Advice;

import java.io.InputStream;

/**
 * Intercepts Socket.getInputStream() to wrap the returned stream
 * with a recording wrapper when the socket is being recorded.
 *
 * <p><b>CRITICAL</b>: This advice is inlined into java.net.Socket by ByteBuddy
 * and runs in the Bootstrap CL context.
 * It MUST ONLY reference classes visible to the Bootstrap CL.</p>
 */
public final class SocketInputStreamAdvice {

    private SocketInputStreamAdvice() {}

    @Advice.OnMethodExit
    public static void afterGetInputStream(@Advice.This Object socket,
                                           @Advice.Return(readOnly = false) InputStream result) {
        if (result == null) {
            return;
        }
        try {
            int socketId = System.identityHashCode(socket);
            String[] sessionInfo = GlobalRouteState.getRecordingSession(socketId);
            if (sessionInfo == null) {
                return;
            }
            java.util.function.BiFunction<InputStream, String[], InputStream> wrapper = GlobalRouteState.INPUT_STREAM_WRAPPER;
            if (wrapper != null) {
                InputStream wrapped = wrapper.apply(result, sessionInfo);
                if (wrapped != null) {
                    result = wrapped;
                }
            }
        } catch (Throwable t) {
            GlobalRouteState.logError("[Baafoo] SocketInputStreamAdvice error: " + t);
        }
    }
}
