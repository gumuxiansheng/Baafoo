package com.baafoo.plugin.tdmq;

import com.baafoo.plugin.AgentPlugin;
import com.baafoo.plugin.InterceptResult;
import com.baafoo.plugin.InterceptTarget;
import com.baafoo.plugin.PluginContext;

import java.util.HashMap;
import java.util.Map;

public class TdmqPlugin implements AgentPlugin {

    private static final String PLUGIN_NAME = "tdmq";
    private static final int TDMQ_BROKER_PORT = 9005;
    
    @Override
    public String getName() {
        return PLUGIN_NAME;
    }

    @Override
    public InterceptTarget getTarget() {
        return InterceptTarget.PULSAR;
    }

    @Override
    public void init() {
        System.out.println("[TDMQ Plugin] Initialized for Pulsar/TDMQ 2.7.4");
    }

    @Override
    public InterceptResult intercept(PluginContext ctx) {
        String protocol = ctx.getProtocol();
        String host = ctx.getHost();
        int port = ctx.getPort();
        
        if ("pulsar".equalsIgnoreCase(protocol) && host != null && !host.equals("localhost")) {
            Map<String, String> headers = new HashMap<>();
            headers.put("X-Tdmq-Plugin", "true");
            headers.put("X-Tdmq-Target", "localhost:" + TDMQ_BROKER_PORT);
            
            String redirectInfo = "{\"redirected\":true,\"originalHost\":\"" + host + "\",\"originalPort\":" + port + ",\"targetHost\":\"localhost\",\"targetPort\":" + TDMQ_BROKER_PORT + "}";
            return InterceptResult.stub(redirectInfo.getBytes(), headers, 200);
        }
        
        return InterceptResult.passthrough();
    }

    @Override
    public void destroy() {
        System.out.println("[TDMQ Plugin] Destroyed");
    }
}
