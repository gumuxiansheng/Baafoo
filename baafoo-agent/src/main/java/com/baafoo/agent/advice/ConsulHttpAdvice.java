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

            GlobalRouteState.HostPort target = GlobalRouteState.lookupService(server);

            if (target != null) {
                server = target.host;
                port = target.port;
            }
        } catch (Throwable t) {
        }
    }
}
