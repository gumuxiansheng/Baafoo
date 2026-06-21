package com.baafoo.plugin.feign;

import com.baafoo.plugin.AgentPlugin;
import com.baafoo.plugin.InterceptResult;
import com.baafoo.plugin.InterceptTarget;
import com.baafoo.plugin.PluginContext;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

public class FeignPlugin implements AgentPlugin {

    private static final String PLUGIN_NAME = "feign-plugin";

    private final AtomicLong interceptCount = new AtomicLong(0);
    private final ConcurrentHashMap<String, StubEntry> stubRegistry = new ConcurrentHashMap<String, StubEntry>();

    private volatile boolean initialized = false;

    @Override
    public String getName() {
        return PLUGIN_NAME;
    }

    @Override
    public InterceptTarget getTarget() {
        return InterceptTarget.FEIGN;
    }

    @Override
    public void init() {
        registerDefaultStubs();
        initialized = true;
        System.out.println("[FeignPlugin] Initialized with " + stubRegistry.size() + " default stubs");
    }

    @Override
    public InterceptResult intercept(PluginContext ctx) {
        if (!initialized) {
            return InterceptResult.passthrough();
        }

        long count = interceptCount.incrementAndGet();
        String method = ctx.getHeaders() != null ? ctx.getHeaders().get("X-Feign-Method") : null;
        String path = ctx.getHeaders() != null ? ctx.getHeaders().get("X-Feign-Path") : null;
        String stubKey = buildStubKey(method, path);

        System.out.println("[FeignPlugin] Intercept #" + count
                + " | protocol=" + ctx.getProtocol()
                + " | host=" + ctx.getHost()
                + " | method=" + method
                + " | path=" + path
                + " | stubKey=" + stubKey);

        StubEntry stub = stubRegistry.get(stubKey);
        if (stub != null) {
            System.out.println("[FeignPlugin] Stub HIT: " + stubKey + " -> status=" + stub.statusCode);
            Map<String, String> headers = new HashMap<String, String>(stub.headers);
            headers.put("X-Baafoo-Stub", "true");
            headers.put("X-Baafoo-Plugin", PLUGIN_NAME);
            return InterceptResult.stub(
                    stub.body.getBytes(StandardCharsets.UTF_8),
                    headers,
                    stub.statusCode
            );
        }

        if (ctx.getOriginalCall() != null) {
            try {
                InterceptResult realResult = ctx.getOriginalCall().call();
                System.out.println("[FeignPlugin] No stub matched, passthrough to original call");
                return realResult;
            } catch (Exception e) {
                System.out.println("[FeignPlugin] Original call failed: " + e.getMessage());
                return InterceptResult.error("Original call failed: " + e.getMessage());
            }
        }

        System.out.println("[FeignPlugin] No stub matched and no original call available");
        return InterceptResult.passthrough();
    }

    @Override
    public void destroy() {
        stubRegistry.clear();
        interceptCount.set(0);
        initialized = false;
        System.out.println("[FeignPlugin] Destroyed, all stubs cleared");
    }

    public void registerStub(String method, String path, int statusCode, String body, Map<String, String> headers) {
        String key = buildStubKey(method, path);
        StubEntry entry = new StubEntry(statusCode, body, headers != null ? headers : new HashMap<String, String>());
        stubRegistry.put(key, entry);
        System.out.println("[FeignPlugin] Stub registered: " + key + " -> status=" + statusCode);
    }

    public void removeStub(String method, String path) {
        String key = buildStubKey(method, path);
        stubRegistry.remove(key);
    }

    public long getInterceptCount() {
        return interceptCount.get();
    }

    public int getStubCount() {
        return stubRegistry.size();
    }

    private String buildStubKey(String method, String path) {
        String m = method != null ? method.toUpperCase() : "GET";
        String p = path != null ? path : "/";
        return m + " " + p;
    }

    private void registerDefaultStubs() {
        registerStub("GET", "/get", 200,
                "{\"url\":\"http://stub.baafoo.local/get\",\"headers\":{\"X-Baafoo-Stub\":[\"true\"]},\"origin\":\"127.0.0.1\"}",
                null);
        registerStub("POST", "/post", 201,
                "{\"url\":\"http://stub.baafoo.local/post\",\"data\":\"stubbed-by-baafoo\"}",
                null);
        registerStub("PUT", "/put", 200,
                "{\"url\":\"http://stub.baafoo.local/put\",\"data\":\"stubbed-by-baafoo\"}",
                null);
        registerStub("DELETE", "/delete", 204, "", null);
    }

    private static class StubEntry {
        final int statusCode;
        final String body;
        final Map<String, String> headers;

        StubEntry(int statusCode, String body, Map<String, String> headers) {
            this.statusCode = statusCode;
            this.body = body;
            this.headers = headers;
        }
    }
}
