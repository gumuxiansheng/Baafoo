package com.baafoo.agent.advice;

import com.baafoo.agent.GlobalRouteState;
import net.bytebuddy.asm.Advice;

import java.net.InetAddress;

/**
 * Intercepts InetAddress.getByName/getAllByName to:
 * 1. Record domain-to-IP mappings for DNS cache fallback in socket interception
 * 2. Redirect Consul service lookups to the stub server (when consulEnabled)
 *
 * <p><b>CRITICAL</b>: This advice is inlined into java.net.InetAddress by ByteBuddy.
 * Since InetAddress is loaded by the Bootstrap ClassLoader, the inlined code
 * runs in the Bootstrap CL context. Only reference Bootstrap CL-visible classes.</p>
 */
public final class ConsulDnsAdvice {

    private ConsulDnsAdvice() {}

    @Advice.OnMethodExit
    public static void onGetByName(
            @Advice.Argument(0) String host,
            @Advice.Return(readOnly = false) InetAddress result) {

        if (GlobalRouteState.isPassthrough()) {
            return;
        }

        try {
            if (host == null || host.isEmpty()) {
                return;
            }

            // Record DNS resolution for IP-based route lookup fallback
            if (result != null) {
                String ip = result.getHostAddress();
                if (ip != null && !ip.isEmpty()) {
                    GlobalRouteState.recordDns(host, ip);
                }
            }

            // Consul service name redirection
            GlobalRouteState.HostPort target = GlobalRouteState.lookupService(host);

            if (target != null) {
                result = InetAddress.getByName(target.host);
            }
        } catch (Throwable t) {
        }
    }

    @Advice.OnMethodExit
    public static void onGetAllByName(
            @Advice.Argument(0) String host,
            @Advice.Return(readOnly = false) InetAddress[] result) {

        if (GlobalRouteState.isPassthrough()) {
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

            // Consul service name redirection
            GlobalRouteState.HostPort target = GlobalRouteState.lookupService(host);

            if (target != null) {
                InetAddress stubAddr = InetAddress.getByName(target.host);
                result = new InetAddress[]{stubAddr};
            }
        } catch (Throwable t) {
        }
    }
}
