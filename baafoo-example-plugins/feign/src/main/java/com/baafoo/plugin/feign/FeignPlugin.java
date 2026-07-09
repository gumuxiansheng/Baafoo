package com.baafoo.plugin.feign;

import com.baafoo.plugin.AgentPlugin;
import com.baafoo.plugin.InterceptResult;
import com.baafoo.plugin.InterceptTarget;
import com.baafoo.plugin.PluginContext;
import com.baafoo.plugin.service.PluginServices;
import com.baafoo.plugin.service.RuleStore;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Example plugin for the {@link InterceptTarget#FEIGN} target.
 *
 * <p><b>Architecture note (important):</b> A Feign client built on {@code
 * feign-okhttp} (OkHttp) performs its requests through a raw {@link
 * java.net.Socket}. The Baafoo agent's socket advice ({@code SocketConnectAdvice})
 * intercepts that socket and — in STUB mode — redirects the connection to the
 * Baafoo server's HTTP stub port (9000) using the runtime route table. That is
 * the mechanism that actually stubs a Feign/OkHttp call in production.</p>
 *
 * <p>Intercepting Feign at the {@code feign.Client} layer (see the bundled
 * {@code FeignClientAdvice}) is <b>not</b> viable for OkHttp-based Feign:
 * that advice would have to be inlined into Bootstrap-CL classes and reference
 * {@code feign.Request}/{@code feign.Response}, which violates the Bootstrap
 * ClassLoader constraint and fails to load. This plugin therefore lives as the
 * designated {@code FEIGN} handler and consults the rule engine so it is no
 * longer a hard-coded demo; it is the correct design for any future Feign path
 * that routes through a woven advice, and as a fallback it still serves its
 * built-in stubs.</p>
 */
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
        String host = ctx.getHost();
        String method = ctx.getMethod();
        String path = ctx.getPath();

        System.out.println("[FeignPlugin] Intercept #" + count
                + " | protocol=" + ctx.getProtocol()
                + " | host=" + host
                + " | method=" + method
                + " | path=" + path);

        // 1) Consult the rule engine first (honors server-side stub definitions).
        InterceptResult ruleResult = consultRuleStore(ctx);
        if (ruleResult != null) {
            System.out.println("[FeignPlugin] Matched a server rule for " + host + method + path
                    + " -> status=" + ruleResult.getStatusCode());
            return ruleResult;
        }

        // 2) Fall back to the plugin's built-in stubs (demo behavior).
        String stubKey = buildStubKey(method, path);
        StubEntry stub = stubRegistry.get(stubKey);
        if (stub != null) {
            System.out.println("[FeignPlugin] Built-in stub HIT: " + stubKey + " -> status=" + stub.statusCode);
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

    /**
     * Query the rule engine for a stub matching this request.
     *
     * @return a stubbed {@link InterceptResult} if a matching enabled rule with a
     *         response body exists, otherwise {@code null}.
     */
    private InterceptResult consultRuleStore(PluginContext ctx) {
        PluginServices services = ctx.getServices();
        if (services == null) {
            return null;
        }
        RuleStore ruleStore = services.getRuleStore();
        if (ruleStore == null) {
            return null;
        }

        List<Map<String, Object>> rules = ruleStore.listRules(null);
        if (rules == null || rules.isEmpty()) {
            return null;
        }

        String host = ctx.getHost();
        String method = ctx.getMethod() != null ? ctx.getMethod().toUpperCase() : null;
        String path = ctx.getPath();

        for (Map<String, Object> rule : rules) {
            if (!Boolean.TRUE.equals(rule.get("enabled"))) {
                continue;
            }
            if (!matchesTarget(rule, host, method, path)) {
                continue;
            }
            Object responsesObj = rule.get("responses");
            if (!(responsesObj instanceof List)) {
                continue;
            }
            List<?> responses = (List<?>) responsesObj;
            if (responses.isEmpty()) {
                continue;
            }
            Object first = responses.get(0);
            if (!(first instanceof Map)) {
                continue;
            }
            @SuppressWarnings("unchecked")
            Map<String, Object> response = (Map<String, Object>) first;

            String body = response.get("body") != null ? String.valueOf(response.get("body")) : "";
            int statusCode = 200;
            Object statusObj = response.get("statusCode");
            if (statusObj instanceof Number) {
                statusCode = ((Number) statusObj).intValue();
            }
            Map<String, String> headers = new HashMap<String, String>();
            Object headersObj = response.get("headers");
            if (headersObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> rawHeaders = (Map<String, Object>) headersObj;
                for (Map.Entry<String, Object> e : rawHeaders.entrySet()) {
                    headers.put(e.getKey(), String.valueOf(e.getValue()));
                }
            }
            headers.put("X-Baafoo-Stub", "true");
            headers.put("X-Baafoo-Plugin", PLUGIN_NAME);
            headers.put("X-Baafoo-Rule-Id", String.valueOf(rule.get("id")));
            return InterceptResult.stub(body.getBytes(StandardCharsets.UTF_8), headers, statusCode);
        }
        return null;
    }

    /** @return true if the rule's host/method/path conditions match the request. */
    private boolean matchesTarget(Map<String, Object> rule, String host, String method, String path) {
        Object ruleHostObj = rule.get("host");
        String ruleHost = ruleHostObj != null ? String.valueOf(ruleHostObj) : null;
        if (ruleHost != null && !ruleHost.isEmpty() && host != null) {
            if (!ruleHost.equalsIgnoreCase(host)) {
                return false;
            }
        }

        Object conditionsObj = rule.get("conditions");
        if (conditionsObj instanceof List) {
            for (Object condObj : (List<?>) conditionsObj) {
                if (!(condObj instanceof Map)) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                Map<String, Object> cond = (Map<String, Object>) condObj;
                String type = cond.get("type") != null ? String.valueOf(cond.get("type")) : null;
                String value = cond.get("value") != null ? String.valueOf(cond.get("value")) : null;
                if (value == null || value.isEmpty()) {
                    continue;
                }
                if ("method".equalsIgnoreCase(type)) {
                    if (method == null || !value.equalsIgnoreCase(method)) {
                        return false;
                    }
                } else if ("path".equalsIgnoreCase(type)) {
                    String p = path != null ? path : "/";
                    String op = cond.get("operator") != null ? String.valueOf(cond.get("operator")) : "startsWith";
                    if ("equals".equalsIgnoreCase(op)) {
                        if (!value.equals(p)) {
                            return false;
                        }
                    } else { // startsWith (default)
                        if (!p.startsWith(value)) {
                            return false;
                        }
                    }
                }
            }
        }
        return true;
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
