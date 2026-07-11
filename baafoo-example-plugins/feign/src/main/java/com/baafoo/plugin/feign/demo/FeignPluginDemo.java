package com.baafoo.plugin.feign.demo;

import com.baafoo.plugin.ConnectAdvice;
import com.baafoo.plugin.ConnectContext;
import com.baafoo.plugin.PluginEvent;
import com.baafoo.plugin.RequestAdvice;
import com.baafoo.plugin.RequestContext;
import com.baafoo.plugin.ResponseAdvice;
import com.baafoo.plugin.ResponseContext;
import com.baafoo.plugin.feign.FeignPlugin;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

public class FeignPluginDemo {

    public static void main(String[] args) {
        System.out.println("==============================================");
        System.out.println("  Baafoo Feign Plugin - New API Demo");
        System.out.println("==============================================\n");

        FeignPlugin plugin = new FeignPlugin();

        System.out.println("--- Step 1: Plugin Lifecycle ---");
        System.out.println("Name:   " + plugin.getName());
        System.out.println("Target: " + plugin.getTarget());
        System.out.println();

        plugin.init();
        System.out.println("Stubs loaded: " + plugin.getStubCount());
        System.out.println();

        System.out.println("--- Step 2: onConnect (passthrough) ---");
        ConnectContext connectCtx = new ConnectContext("http", "httpbin.org", 80, null, null, null, null);
        ConnectAdvice connectAdvice = plugin.onConnect(connectCtx);
        System.out.println("  Action: " + connectAdvice.getAction());
        System.out.println();

        System.out.println("--- Step 3: onRequest GET /get (stub HIT) ---");
        RequestContext reqCtx1 = newRequestContext("GET", "/get");
        RequestAdvice reqAdvice1 = plugin.onRequest(reqCtx1);
        printRequestAdvice("GET /get", reqAdvice1);
        System.out.println();

        System.out.println("--- Step 4: onRequest POST /post (stub HIT) ---");
        RequestContext reqCtx2 = newRequestContext("POST", "/post");
        RequestAdvice reqAdvice2 = plugin.onRequest(reqCtx2);
        printRequestAdvice("POST /post", reqAdvice2);
        System.out.println();

        System.out.println("--- Step 5: onRequest GET /status/404 (no stub, proceed) ---");
        RequestContext reqCtx3 = newRequestContext("GET", "/status/404");
        RequestAdvice reqAdvice3 = plugin.onRequest(reqCtx3);
        printRequestAdvice("GET /status/404 (unmatched)", reqAdvice3);
        System.out.println();

        System.out.println("--- Step 6: onResponse (stubbed → proceed) ---");
        ResponseContext respCtx1 = new ResponseContext("http", null, null,
                reqAdvice1.getShortcutStatusCode(), reqAdvice1.getShortcutHeaders(),
                reqAdvice1.getShortcutBody(), reqCtx1, true);
        ResponseAdvice respAdvice1 = plugin.onResponse(respCtx1);
        System.out.println("  Action: " + respAdvice1.getAction());
        System.out.println();

        System.out.println("--- Step 7: onResponse (non-stubbed → augment) ---");
        ResponseContext respCtx2 = new ResponseContext("http", null, null, 404,
                Collections.<String, String>emptyMap(), new byte[0], reqCtx3, false);
        ResponseAdvice respAdvice2 = plugin.onResponse(respCtx2);
        System.out.println("  Action: " + respAdvice2.getAction());
        System.out.println("  Additional headers: " + respAdvice2.getAdditionalHeaders());
        System.out.println();

        System.out.println("--- Step 8: onEvent (observation-only) ---");
        PluginEvent event = PluginEvent.requestReceived("http", "GET", "/get");
        plugin.onEvent(event);
        System.out.println("  (event processed without throwing)");
        System.out.println();

        System.out.println("--- Step 9: Register Custom Stub ---");
        Map<String, String> customHeaders = new HashMap<String, String>();
        customHeaders.put("Content-Type", "application/json");
        customHeaders.put("X-Custom-Header", "baafoo-demo");
        plugin.registerStub("GET", "/custom/endpoint", 200,
                "{\"message\":\"custom stub response\",\"source\":\"baafoo-plugin\"}",
                customHeaders);
        System.out.println("Stubs after registration: " + plugin.getStubCount());

        RequestContext reqCtx4 = newRequestContext("GET", "/custom/endpoint");
        RequestAdvice reqAdvice4 = plugin.onRequest(reqCtx4);
        printRequestAdvice("GET /custom/endpoint (custom stub)", reqAdvice4);
        System.out.println();

        System.out.println("--- Step 10: Statistics ---");
        System.out.println("Total requests (onRequest calls): " + plugin.getInterceptCount());
        System.out.println("Registered stubs: " + plugin.getStubCount());
        System.out.println();

        System.out.println("--- Step 11: Destroy Plugin ---");
        plugin.destroy();
        System.out.println("Plugin destroyed. Stubs: " + plugin.getStubCount());
        System.out.println();

        System.out.println("==============================================");
        System.out.println("  Demo Complete!");
        System.out.println("==============================================");
    }

    private static RequestContext newRequestContext(String method, String path) {
        return new RequestContext("http", method, path,
                Collections.<String, String>emptyMap(),
                Collections.<String, String>emptyMap(),
                new byte[0], "httpbin.org", 80, null, false);
    }

    private static void printRequestAdvice(String label, RequestAdvice advice) {
        System.out.println("  [" + label + "]");
        System.out.println("    Action:     " + advice.getAction());
        if (advice.getShortcutBody() != null && advice.getShortcutBody().length > 0) {
            System.out.println("    StatusCode: " + advice.getShortcutStatusCode());
            String body = new String(advice.getShortcutBody(), StandardCharsets.UTF_8);
            if (body.length() > 200) {
                body = body.substring(0, 200) + "...";
            }
            System.out.println("    Body:       " + body);
        }
        if (advice.getShortcutHeaders() != null && !advice.getShortcutHeaders().isEmpty()) {
            System.out.println("    Headers:    " + advice.getShortcutHeaders());
        }
    }
}
