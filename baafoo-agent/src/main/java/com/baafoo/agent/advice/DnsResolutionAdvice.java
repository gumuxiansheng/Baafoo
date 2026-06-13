package com.baafoo.agent.advice;

import com.baafoo.agent.GlobalRouteState;
import net.bytebuddy.asm.Advice;

import java.net.InetAddress;

/**
 * Intercepts InetAddress.getByName/getAllByName to record domain-to-IP mappings.
 *
 * <p>When an HTTP client resolves a domain like "api.example.com" to an IP
 * like "93.184.216.34", the subsequent socket connect uses the IP address,
 * making it impossible for GlobalRouteState.lookup(ip, port) to match
 * domain-based routes. This advice records the DNS resolution so that
 * SocketConnectAdvice/NioSocketConnectAdvice can look up routes by the original domain.</p>
 */
public final class DnsResolutionAdvice {

    private DnsResolutionAdvice() {}

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
