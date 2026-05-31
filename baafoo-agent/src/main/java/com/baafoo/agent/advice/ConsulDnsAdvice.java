package com.baafoo.agent.advice;

import com.baafoo.agent.GlobalRouteState;
import net.bytebuddy.asm.Advice;

import java.net.InetAddress;

public class ConsulDnsAdvice {

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

            GlobalRouteState.HostPort target = GlobalRouteState.lookupService(host);

            if (target != null) {
                InetAddress stubAddr = InetAddress.getByName(target.host);
                result = new InetAddress[]{stubAddr};
            }
        } catch (Throwable t) {
        }
    }
}
