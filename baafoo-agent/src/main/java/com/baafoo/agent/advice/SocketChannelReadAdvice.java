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
            ((java.nio.Buffer) buf).position(pos);

            // Inline bytesToHex — cannot call private method from inlined advice
            StringBuilder sb = new StringBuilder(data.length * 2);
            for (int i = 0; i < data.length; i++) {
                sb.append(String.format("%02x", data[i] & 0xff));
            }
            String hex = sb.toString();

            GlobalRouteState.addNioRecording(sessionInfo, "response", hex);
        } catch (Throwable t) {
            GlobalRouteState.logError("[Baafoo] SocketChannelReadAdvice error: " + t);
        }
    }
}
