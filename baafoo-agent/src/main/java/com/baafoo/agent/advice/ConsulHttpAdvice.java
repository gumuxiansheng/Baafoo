package com.baafoo.agent.advice;

import com.baafoo.agent.GlobalRouteState;
import net.bytebuddy.asm.Advice;

public class ConsulHttpAdvice {

    @Advice.OnMethodEnter
    public static void onOpenServer(
            @Advice.Argument(value = 0, readOnly = false) String server,
            @Advice.Argument(value = 1, readOnly = false) int port) {

        if (GlobalRouteState.isPassthrough()) {
            return;
        }

        try {
            if (server == null || server.isEmpty()) {
                return;
            }

            String routeValue = GlobalRouteState.lookupService(server);

            if (routeValue != null) {
                String stubHost = GlobalRouteState.parseHost(routeValue);
                int stubPort = GlobalRouteState.parsePort(routeValue);
                server = stubHost;
                port = stubPort;
            }
        } catch (Throwable t) {
        }
    }
}
