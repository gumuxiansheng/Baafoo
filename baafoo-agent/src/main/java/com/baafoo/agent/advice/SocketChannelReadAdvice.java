package com.baafoo.agent.advice;

import com.baafoo.agent.GlobalRouteState;
import net.bytebuddy.asm.Advice;

import java.nio.ByteBuffer;

/**
 * Intercepts SocketChannel.read(ByteBuffer) to record incoming bytes during record mode.
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
public final class SocketChannelReadAdvice {

    private SocketChannelReadAdvice() {}

    @Advice.OnMethodExit
    public static void afterRead(@Advice.This Object channel,
                                 @Advice.Argument(0) ByteBuffer buf,
                                 @Advice.Return int bytesRead) {
        if (bytesRead <= 0) {
            return;
        }
        try {
            int channelId = System.identityHashCode(channel);
            String[] sessionInfo = GlobalRouteState.getRecordingSession(channelId);
            if (sessionInfo == null) {
                return;
            }

            // Capture bytes from the buffer's current position.
            // Cast to Buffer to call position(int) — Java 8 returns Buffer,
            // Java 9+ returns ByteBuffer (covariant override), causing
            // NoSuchMethodError if we call ByteBuffer.position(int) on JDK 8.
            int pos = buf.position();
            byte[] data = new byte[bytesRead];
            ((java.nio.Buffer) buf).position(pos - bytesRead);
            buf.get(data);
            // L2: the trailing buf.position(pos) is redundant — buf.get(data)
            // already advanced position by data.length (= bytesRead), so it is
            // back to pos. The previous restore was a no-op.

            // M5: delegate to GlobalRouteState.bytesToHex — GlobalRouteState is
            // packaged into the Bootstrap JAR, so this invokestatic resolves
            // correctly from inlined advice. The previous inline loop used
            // String.format("%02x", ...) which is ~50x slower (creates a
            // Formatter + StringBuilder per byte).
            String hex = GlobalRouteState.bytesToHex(data, 0, data.length);

            GlobalRouteState.addNioRecording(sessionInfo, "response", hex);
        } catch (Throwable t) {
            GlobalRouteState.logError("[Baafoo] SocketChannelReadAdvice error: " + t);
        }
    }
}
