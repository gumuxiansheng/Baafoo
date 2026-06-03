package com.baafoo.agent.advice;

import net.bytebuddy.asm.Advice;

import java.net.InetSocketAddress;
import java.net.SocketAddress;

public class SocketConnectAdvice {

    @Advice.OnMethodEnter
    public static void onConnect(@Advice.Argument(value = 0, readOnly = false) SocketAddress endpoint) {
        try {
            InetSocketAddress rerouted = ConnectAdviceHelper.resolveRoute(endpoint);
            if (rerouted != null) {
                endpoint = rerouted;
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Throwable t) {
        }
    }
}
