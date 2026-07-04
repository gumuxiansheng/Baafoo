package com.baafoo.agent.advice;

import com.baafoo.agent.GlobalRouteState;
import net.bytebuddy.asm.Advice;

import java.net.InetAddress;

/**
 * Intercepts InetAddress.getAllByName (multi-result variant) for Consul/Nacos
 * service-name redirection.
 *
 * <p><b>CRITICAL</b>: Must be a separate class from ConsulDnsGetByNameAdvice
 * because ByteBuddy's {@code Advice.to()} discovers ALL @Advice.OnMethodExit
 * methods in a class and tries to apply them to the target method, causing a
 * "Duplicate advice" error when two methods with different return types
 * (InetAddress vs InetAddress[]) are in the same class.</p>
 */
public final class ConsulDnsGetAllByNameAdvice {

    private ConsulDnsGetAllByNameAdvice() {}

    @Advice.OnMethodExit
    public static void onGetAllByName(
            @Advice.Argument(0) String host,
            @Advice.Return(readOnly = false) InetAddress[] result) {

        if (GlobalRouteState.isPassthrough()) {
            return;
        }

        // Re-entry guard lives in GlobalRouteState — see ConsulDnsGetByNameAdvice
        // for rationale (Bootstrap CL visibility for inlined advice).
        // Null (unset) is treated as false.
        if (Boolean.TRUE.equals(GlobalRouteState.CONSUL_DNS_REENTRY_GUARD.get())) {
            return;
        }

        try {
            if (host == null || host.isEmpty()) {
                return;
            }

            // Record DNS resolution for IP-based route lookup fallback
            if (result != null) {
                for (InetAddress addr : result) {
                    if (addr != null) {
                        String ip = addr.getHostAddress();
                        if (ip != null && !ip.isEmpty()) {
                            GlobalRouteState.recordDns(host, ip);
                        }
                    }
                }
            }

            // Service-name / host redirection: check serviceName first, then
            // fall back to host-based lookup (parity with ConsulDnsGetByNameAdvice).
            // Some HTTP clients use getAllByName instead of getByName, so
            // host-based routes must work for both call paths.
            GlobalRouteState.HostPort target = GlobalRouteState.lookupService(host);
            if (target == null) {
                target = GlobalRouteState.lookupByHost(host);
            }
            if (target != null) {
                GlobalRouteState.logInfo("[Baafoo] ConsulDns redirect (getAllByName): " + host + " -> " + target.host);
                GlobalRouteState.CONSUL_DNS_REENTRY_GUARD.set(Boolean.TRUE);
                try {
                    InetAddress stubAddr = InetAddress.getByName(target.host);
                    result = new InetAddress[]{stubAddr};
                } finally {
                    GlobalRouteState.CONSUL_DNS_REENTRY_GUARD.set(Boolean.FALSE);
                }
            }
        } catch (Throwable t) {
            GlobalRouteState.CONSUL_DNS_REENTRY_GUARD.set(Boolean.FALSE);
            GlobalRouteState.logInfo("[Baafoo] ConsulDns (getAllByName) error: " + t.getMessage());
        }
    }
}
