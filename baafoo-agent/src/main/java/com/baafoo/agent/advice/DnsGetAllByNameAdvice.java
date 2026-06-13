package com.baafoo.agent.advice;

import com.baafoo.agent.GlobalRouteState;
import net.bytebuddy.asm.Advice;

import java.net.InetAddress;

/**
 * Intercepts InetAddress.getAllByName to record domain-to-IP mappings.
 *
 * <p>Must be a separate class from {@link DnsGetByNameAdvice} because
 * ByteBuddy's {@code Advice.to()} discovers ALL @Advice.OnMethodExit methods
 * in a class and tries to apply them to the target method, causing a
 * "Duplicate advice" error when two methods with different return types
 * are in the same class.</p>
 */
public final class DnsGetAllByNameAdvice {

    private DnsGetAllByNameAdvice() {}

    @Advice.OnMethodExit
    public static void onGetAllByName(
            @Advice.Argument(0) String host,
            @Advice.Return(readOnly = false) InetAddress[] result) {

        // Check passthrough mode (1=PASSTHROUGH)
        if (GlobalRouteState.CURRENT_MODE == 1) {
            return;
        }

        try {
            if (host == null || host.isEmpty() || result == null || result.length == 0) {
                return;
            }

            for (InetAddress addr : result) {
                if (addr != null) {
                    String ip = addr.getHostAddress();
                    if (ip != null && !ip.isEmpty()) {
                        GlobalRouteState.recordDns(host, ip);
                    }
                }
            }
        } catch (Throwable t) {
            // Fail silently
        }
    }
}
