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

            String routeValue = GlobalRouteState.lookupService(host);

            if (routeValue != null) {
                String stubHost = GlobalRouteState.parseHost(routeValue);
                result = InetAddress.getByName(stubHost);
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

            String routeValue = GlobalRouteState.lookupService(host);

            if (routeValue != null) {
                String stubHost = GlobalRouteState.parseHost(routeValue);
                InetAddress stubAddr = InetAddress.getByName(stubHost);
                result = new InetAddress[]{stubAddr};
            }
        } catch (Throwable t) {
        }
    }
}
