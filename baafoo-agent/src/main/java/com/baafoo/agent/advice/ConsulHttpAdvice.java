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
                GlobalRouteState.logDebug("[Baafoo] ConsulHttpAdvice: would redirect to " + target.host + ":" + target.port + " (note: String arg mutation may not propagate to caller)");
            }
        } catch (Throwable t) {
            GlobalRouteState.logDebug("[Baafoo] ConsulHttpAdvice error: " + t.getMessage());
        }
    }
}
