package com.baafoo.agent.advice;

import com.baafoo.agent.GlobalRouteState;
import net.bytebuddy.asm.Advice;

import java.nio.ByteBuffer;

/**
 * Intercepts SocketChannel.write(ByteBuffer) to record outgoing bytes during record mode.
 *
 * <p><b>CRITICAL</b>: This advice is inlined into sun.nio.ch.SocketChannelImpl
 * by ByteBuddy. Since SocketChannelImpl is loaded by the Bootstrap ClassLoader, the
 * inlined code runs in the Bootstrap CL context. This class MUST ONLY reference
 * classes visible to the Bootstrap CL (GlobalRouteState is added via
 * appendToBootstrapClassLoaderSearch).</p>
 *
 * <p>All helper logic MUST be inlined directly in the advice method — ByteBuddy
 * does NOT inline calls to private helper methods, so they would cause
 * NoClassDefFoundError at runtime.</p>
 */
public final class SocketChannelWriteAdvice {

    private SocketChannelWriteAdvice() {}

    @Advice.OnMethodExit
    public static void afterWrite(@Advice.This Object channel,
                                  @Advice.Argument(0) ByteBuffer buf,
                                  @Advice.Return int bytesWritten) {
        if (bytesWritten <= 0) {
            return;
        }
        try {
            int channelId = System.identityHashCode(channel);
            String[] sessionInfo = GlobalRouteState.getRecordingSession(channelId);
            if (sessionInfo == null) {
                return;
            }

            // Capture bytes that were written.
            // Cast to Buffer to call position(int) — Java 8 returns Buffer,
            // Java 9+ returns ByteBuffer (covariant override), causing
            // NoSuchMethodError if we call ByteBuffer.position(int) on JDK 8.
            int pos = buf.position();
            byte[] data = new byte[bytesWritten];
            ((java.nio.Buffer) buf).position(pos - bytesWritten);
            buf.get(data);
            // L2: the trailing buf.position(pos) is redundant — buf.get(data)
            // already advanced position by data.length (= bytesWritten), so it
            // is back to pos. The previous restore was a no-op.

            // M5: delegate to GlobalRouteState.bytesToHex — see SocketChannelReadAdvice
            // for rationale (Bootstrap-CL-visible static, ~50x faster than String.format).
            String hex = GlobalRouteState.bytesToHex(data, 0, data.length);

            GlobalRouteState.addNioRecording(sessionInfo, "request", hex);
        } catch (Throwable t) {
            GlobalRouteState.logError("[Baafoo] SocketChannelWriteAdvice error: " + t);
        }
    }
}
