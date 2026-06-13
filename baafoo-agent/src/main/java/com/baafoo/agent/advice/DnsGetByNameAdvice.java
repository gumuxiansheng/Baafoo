package com.baafoo.agent.advice;

import com.baafoo.agent.GlobalRouteState;
import net.bytebuddy.asm.Advice;

import java.net.InetAddress;

/**
 * Intercepts InetAddress.getByName to record domain-to-IP mappings.
 *
 * <p>Must be a separate class from {@link DnsGetAllByNameAdvice} because
 * ByteBuddy's {@code Advice.to()} discovers ALL @Advice.OnMethodExit methods
 * in a class and tries to apply them to the target method, causing a
 * "Duplicate advice" error when two methods with different return types
 * are in the same class.</p>
 */
public final class DnsGetByNameAdvice {

    private DnsGetByNameAdvice() {}

    @Advice.OnMethodExit
    public static void onGetByName(
            @Advice.Argument(0) String host,
            @Advice.Return(readOnly = false) InetAddress result) {

        // Check passthrough mode (1=PASSTHROUGH)
        if (GlobalRouteState.CURRENT_MODE == 1) {
            return;
        }

        try {
            if (host == null || host.isEmpty() || result == null) {
                return;
            }

            String ip = result.getHostAddress();
            if (ip != null && !ip.isEmpty()) {
                GlobalRouteState.recordDns(host, ip);
            }
        } catch (Throwable t) {
            // Fail silently — DNS recording should not break the application
        }
    }
}
