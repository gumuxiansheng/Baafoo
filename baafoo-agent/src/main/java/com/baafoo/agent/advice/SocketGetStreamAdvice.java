package com.baafoo.agent.advice;

import com.baafoo.agent.GlobalRouteState;
import net.bytebuddy.asm.Advice;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * Intercepts Socket.getInputStream() and Socket.getOutputStream() to wrap
 * the returned streams with recording wrappers when the socket is being recorded.
 *
 * <p><b>CRITICAL</b>: Like SocketConnectAdvice, this advice is inlined into
 * java.net.Socket by ByteBuddy and runs in the Bootstrap CL context.
 * It MUST ONLY reference classes visible to the Bootstrap CL.</p>
 *
 * <p>The actual wrapping is delegated to {@link GlobalRouteState#INPUT_STREAM_WRAPPER}
 * and {@link GlobalRouteState#OUTPUT_STREAM_WRAPPER}, which are bridge functions
 * set from the App CL (BaafooAgent) with real RecordingInputStream/RecordingOutputStream
 * implementations. This avoids referencing App CL classes from Bootstrap CL code.</p>
 */
public final class SocketGetStreamAdvice {

    private SocketGetStreamAdvice() {}

    /**
     * Intercepts Socket.getInputStream() — wraps the returned InputStream
     * with a recording wrapper if the socket is being recorded.
     */
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
            // sessionInfo = { sessionId, host, portString }
            java.util.function.BiFunction<InputStream, String[], InputStream> wrapper = GlobalRouteState.INPUT_STREAM_WRAPPER;
            if (wrapper != null) {
                InputStream wrapped = wrapper.apply(result, sessionInfo);
                if (wrapped != null) {
                    result = wrapped;
                }
            }
        } catch (Throwable t) {
            GlobalRouteState.logError("[Baafoo] SocketGetStreamAdvice.getInputStream error: " + t);
        }
    }

    /**
     * Intercepts Socket.getOutputStream() — wraps the returned OutputStream
     * with a recording wrapper if the socket is being recorded.
     */
    @Advice.OnMethodExit
    public static void afterGetOutputStream(@Advice.This Object socket,
                                            @Advice.Return(readOnly = false) OutputStream result) {
        if (result == null) {
            return;
        }
        try {
            int socketId = System.identityHashCode(socket);
            String[] sessionInfo = GlobalRouteState.getRecordingSession(socketId);
            if (sessionInfo == null) {
                return;
            }
            // sessionInfo = { sessionId, host, portString }
            java.util.function.BiFunction<OutputStream, String[], OutputStream> wrapper = GlobalRouteState.OUTPUT_STREAM_WRAPPER;
            if (wrapper != null) {
                OutputStream wrapped = wrapper.apply(result, sessionInfo);
                if (wrapped != null) {
                    result = wrapped;
                }
            }
        } catch (Throwable t) {
            GlobalRouteState.logError("[Baafoo] SocketGetStreamAdvice.getOutputStream error: " + t);
        }
    }
}
