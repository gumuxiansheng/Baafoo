package com.baafoo.agent.advice;

import com.baafoo.agent.GlobalRouteState;
import net.bytebuddy.asm.Advice;

import java.net.InetAddress;

/**
 * Intercepts InetAddress.getAllByName to:
 * 1. Record domain-to-IP mappings for DNS cache fallback in socket interception
 * 2. Redirect DNS resolution for hostnames that match routes (when DNS would fail)
 *
 * <p>Must be a separate class from {@link DnsGetByNameAdvice} because
 * ByteBuddy's {@code Advice.to()} discovers ALL @Advice.OnMethodExit methods
 * in a class and tries to apply them to the target method, causing a
 * "Duplicate advice" error when two methods with different return types
 * are in the same class.</p>
 *
 * <p><b>CRITICAL</b>: This advice is inlined into java.net.InetAddress by ByteBuddy.
 * Since InetAddress is loaded by the Bootstrap ClassLoader, the inlined code
 * runs in the Bootstrap CL context. Only reference Bootstrap CL-visible classes.</p>
 */
public final class DnsGetAllByNameAdvice {

    private DnsGetAllByNameAdvice() {}

    @Advice.OnMethodEnter
    public static boolean onGetAllByNameEnter(@Advice.Argument(0) String host) {
        // Check passthrough mode (1=PASSTHROUGH)
        if (GlobalRouteState.CURRENT_MODE == 1) {
            return false;
        }
        try {
            if (host == null || host.isEmpty()) {
                return false;
            }
            // Skip internal hosts
            if ("127.0.0.1".equals(host) || "localhost".equals(host)) {
                return false;
            }
            // Check if this hostname matches any route — if so, we'll handle it in onExit
            // even if DNS resolution fails
            GlobalRouteState.HostPort target = GlobalRouteState.lookupByHost(host);
            if (target != null) {
                // Store the target for use in onMethodExit
                GlobalRouteState.DNS_REDIRECT_TARGET.set(target.host);
                // Return true to signal that we should check in onMethodExit
                return true;
            }
        } catch (Throwable t) {
            // Fail silently
        }
        return false;
    }

    @Advice.OnMethodExit(onThrowable = Throwable.class)
    public static void onGetAllByName(
            @Advice.Argument(0) String host,
            @Advice.Return(readOnly = false) InetAddress[] result,
            @Advice.Thrown(readOnly = false) Throwable thrown,
            @Advice.Enter boolean hasRoute) {

        // Check passthrough mode (1=PASSTHROUGH)
        if (GlobalRouteState.CURRENT_MODE == 1) {
            return;
        }

        try {
            if (host == null || host.isEmpty()) {
                return;
            }

            // Record successful DNS resolution
            if (result != null && result.length > 0) {
                for (InetAddress addr : result) {
                    if (addr != null) {
                        String ip = addr.getHostAddress();
                        if (ip != null && !ip.isEmpty()) {
                            GlobalRouteState.recordDns(host, ip);
                        }
                    }
                }
            }

            // If hostname matches a route and DNS failed, provide a fake resolution
            if (hasRoute && (result == null || result.length == 0) && thrown != null) {
                String targetHost = GlobalRouteState.DNS_REDIRECT_TARGET.get();
                GlobalRouteState.DNS_REDIRECT_TARGET.remove();
                if (targetHost != null) {
                    // Resolve the target host (usually 127.0.0.1)
                    try {
                        InetAddress fakeAddr = InetAddress.getByName(targetHost);
                        result = new InetAddress[]{fakeAddr};
                        thrown = null; // Suppress the exception
                        // Record the fake DNS mapping for socket-level fallback
                        String fakeIp = fakeAddr.getHostAddress();
                        if (fakeIp != null && !fakeIp.isEmpty()) {
                            GlobalRouteState.recordDns(host, fakeIp);
                        }
                        GlobalRouteState.logInfo("[Baafoo] DNS redirect (getAllByName): " + host + " -> " + targetHost + " (" + fakeIp + ")");
                    } catch (Exception e) {
                        // Can't resolve target host, let original exception propagate
                    }
                }
            } else if (hasRoute) {
                GlobalRouteState.DNS_REDIRECT_TARGET.remove();
            }
        } catch (Throwable t) {
            // Fail silently — DNS recording should not break the application
        }
    }
}
