package com.baafoo.plugin.feign.demo;

import com.baafoo.plugin.InterceptResult;
import com.baafoo.plugin.InterceptTarget;
import com.baafoo.plugin.PluginContext;
import com.baafoo.plugin.feign.FeignPlugin;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;

public class FeignPluginDemo {

    public static void main(String[] args) {
        System.out.println("==============================================");
        System.out.println("  Baafoo Feign Plugin - Interception Demo");
        System.out.println("==============================================\n");

        FeignPlugin plugin = new FeignPlugin();

        System.out.println("--- Step 1: Plugin Lifecycle ---");
        System.out.println("Name:   " + plugin.getName());
        System.out.println("Target: " + plugin.getTarget());
        System.out.println();

        plugin.init();
        System.out.println("Stubs loaded: " + plugin.getStubCount());
        System.out.println();

        System.out.println("--- Step 2: Simulate Feign GET /get Interception ---");
        PluginContext ctx1 = buildContext("http", "httpbin.org", 80, "GET", "/get", null);
        InterceptResult result1 = plugin.intercept(ctx1);
        printResult("GET /get", result1);
        System.out.println();

        System.out.println("--- Step 3: Simulate Feign POST /post Interception ---");
        String postBody = "{\"test\":\"baafoo-feign\",\"protocol\":\"feign\"}";
        PluginContext ctx2 = buildContext("http", "httpbin.org", 80, "POST", "/post", postBody);
        InterceptResult result2 = plugin.intercept(ctx2);
        printResult("POST /post", result2);
        System.out.println();

        System.out.println("--- Step 4: Simulate Unmatched Path (passthrough) ---");
        PluginContext ctx3 = buildContext("http", "httpbin.org", 80, "GET", "/status/404", null);
        ctx3.setOriginalCall(new Callable<InterceptResult>() {
            @Override
            public InterceptResult call() {
                System.out.println("  [OriginalCall] Would make real HTTP request to httpbin.org/status/404");
                return InterceptResult.passthrough();
            }
        });
        InterceptResult result3 = plugin.intercept(ctx3);
        printResult("GET /status/404 (unmatched)", result3);
        System.out.println();

        System.out.println("--- Step 5: Register Custom Stub at Runtime ---");
        Map<String, String> customHeaders = new HashMap<String, String>();
        customHeaders.put("Content-Type", "application/json");
        customHeaders.put("X-Custom-Header", "baafoo-demo");
        plugin.registerStub("GET", "/custom/endpoint", 200,
                "{\"message\":\"custom stub response\",\"source\":\"baafoo-plugin\"}",
                customHeaders);
        System.out.println("Stubs after registration: " + plugin.getStubCount());

        PluginContext ctx4 = buildContext("http", "api.example.com", 443, "GET", "/custom/endpoint", null);
        InterceptResult result4 = plugin.intercept(ctx4);
        printResult("GET /custom/endpoint (custom stub)", result4);
        System.out.println();

        System.out.println("--- Step 6: Simulate Record Mode ---");
        PluginContext ctx5 = buildContext("http", "httpbin.org", 80, "GET", "/get", null);
        ctx5.setRecording(true);
        ctx5.setOriginalCall(new Callable<InterceptResult>() {
            @Override
            public InterceptResult call() {
                System.out.println("  [OriginalCall] Recording mode: executing real call and storing result");
                Map<String, String> respHeaders = new HashMap<String, String>();
                respHeaders.put("Content-Type", "application/json");
                return InterceptResult.stub(
                        "{\"recorded\":true,\"data\":\"real response\"}".getBytes(),
                        respHeaders, 200
                );
            }
        });
        InterceptResult result5 = plugin.intercept(ctx5);
        printResult("GET /get (record mode)", result5);
        System.out.println();

        System.out.println("--- Step 7: Statistics ---");
        System.out.println("Total interceptions: " + plugin.getInterceptCount());
        System.out.println("Registered stubs:    " + plugin.getStubCount());
        System.out.println();

        System.out.println("--- Step 8: Destroy Plugin ---");
        plugin.destroy();
        System.out.println("Plugin destroyed. Stubs: " + plugin.getStubCount());
        System.out.println();

        System.out.println("==============================================");
        System.out.println("  Demo Complete!");
        System.out.println("==============================================");
    }

    private static PluginContext buildContext(String protocol, String host, int port,
                                              String method, String path, String body) {
        PluginContext ctx = new PluginContext();
        ctx.setProtocol(protocol);
        ctx.setHost(host);
        ctx.setPort(port);

        Map<String, String> headers = new HashMap<String, String>();
        headers.put("X-Feign-Method", method);
        headers.put("X-Feign-Path", path);
        ctx.setHeaders(headers);

        if (body != null) {
            ctx.setRequestData(body.getBytes());
        }

        return ctx;
    }

    private static void printResult(String label, InterceptResult result) {
        System.out.println("  [" + label + "]");
        System.out.println("    Stubbed:    " + result.isStubbed());
        System.out.println("    StatusCode: " + result.getStatusCode());
        if (result.getResponseHeaders() != null && !result.getResponseHeaders().isEmpty()) {
            System.out.println("    Headers:    " + result.getResponseHeaders());
        }
        if (result.getResponseData() != null && result.getResponseData().length > 0) {
            String body = new String(result.getResponseData());
            if (body.length() > 200) {
                body = body.substring(0, 200) + "...";
            }
            System.out.println("    Body:       " + body);
        }
        if (result.getErrorMessage() != null) {
            System.out.println("    Error:      " + result.getErrorMessage());
        }
    }
}
