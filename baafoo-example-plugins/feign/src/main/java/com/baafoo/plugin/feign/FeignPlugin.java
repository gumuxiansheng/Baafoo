package com.baafoo.plugin.feign;

import com.baafoo.plugin.AgentPlugin;
import com.baafoo.plugin.ConnectAdvice;
import com.baafoo.plugin.ConnectContext;
import com.baafoo.plugin.InterceptTarget;
import com.baafoo.plugin.PluginEvent;
import com.baafoo.plugin.RequestAdvice;
import com.baafoo.plugin.RequestContext;
import com.baafoo.plugin.ResponseAdvice;
import com.baafoo.plugin.ResponseContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Example plugin for the {@link InterceptTarget#FEIGN} target.
 *
 * <p>Uses the new phase-specific API hooks:</p>
 * <ul>
 *   <li>{@link #onConnect(ConnectContext)} — passthrough (Feign connection
 *       redirection is handled by the agent's socket advice)</li>
 *   <li>{@link #onRequest(RequestContext)} — stub matching via
 *       {@link RequestAdvice#shortCircuit(byte[], int, Map)}</li>
 *   <li>{@link #onResponse(ResponseContext)} — augment non-stubbed responses
 *       with {@code X-Baafoo-Plugin} header</li>
 *   <li>{@link #onEvent(PluginEvent)} — observation-only event logging</li>
 * </ul>
 *
 * <p><b>Architecture note:</b> A Feign client built on {@code feign-okhttp}
 * performs its requests through a raw {@link java.net.Socket}. The Baafoo
 * agent's socket advice intercepts that socket and redirects the connection
 * to the Baafoo server's HTTP stub port in STUB mode. This plugin does not
 * redirect connections — it provides request-level stub matching via
 * {@link #onRequest} and a built-in stub registry as a fallback.</p>
 */
public class FeignPlugin implements AgentPlugin {

    private static final String PLUGIN_NAME = "feign-plugin";
    private static final Logger log = LoggerFactory.getLogger(FeignPlugin.class);

    private final AtomicLong interceptCount = new AtomicLong(0);
    private final ConcurrentHashMap<String, StubEntry> stubRegistry = new ConcurrentHashMap<String, StubEntry>();

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
        log.info("[FeignPlugin] Initialized with {} default stubs", stubRegistry.size());
    }

    // ---- New API hooks ----

    /**
     * Connection-phase hook: passthrough.
     *
     * <p>Feign connection redirection is handled by the agent's socket advice
     * ({@code SocketConnectAdvice}), not by this plugin. The plugin focuses on
     * request-level stub matching via {@link #onRequest}.</p>
     */
    @Override
    public ConnectAdvice onConnect(ConnectContext ctx) {
        return ConnectAdvice.passthrough();
    }

    /**
     * Request-phase hook: stub matching.
     *
     * <p>Checks the built-in stub registry for a matching method+path. If found,
     * returns {@link RequestAdvice#shortCircuit(byte[], int, Map)} to skip rule
     * matching and return the stub response directly. Otherwise returns
     * {@link RequestAdvice#proceed()} to continue to normal agent rule matching.</p>
     *
     * <p>Note: rule-store consultation is only available in the deprecated
     * {@link #intercept(PluginContext)} method, since {@link RequestContext}
     * does not carry {@link PluginServices}.</p>
     */
    @Override
    public RequestAdvice onRequest(RequestContext ctx) {
        interceptCount.incrementAndGet();

        String method = ctx.getMethod();
        String path = ctx.getPath();

        String stubKey = buildStubKey(method, path);
        StubEntry stub = stubRegistry.get(stubKey);
        if (stub != null) {
            log.debug("[FeignPlugin] Built-in stub HIT: {} -> status={}", stubKey, stub.statusCode);
            Map<String, String> headers = new HashMap<String, String>(stub.headers);
            headers.put("X-Baafoo-Stub", "true");
            headers.put("X-Baafoo-Plugin", PLUGIN_NAME);
            return RequestAdvice.shortCircuit(
                    stub.body.getBytes(StandardCharsets.UTF_8),
                    stub.statusCode,
                    headers
            );
        }

        log.debug("[FeignPlugin] No stub matched: {} -> proceed to rule matching", stubKey);
        return RequestAdvice.proceed();
    }

    /**
     * Response-phase hook: augment non-stubbed responses.
     *
     * <p>For responses that were NOT produced by this plugin's stub (i.e.
     * {@code stubbed == false}), adds the {@code X-Baafoo-Plugin} header to
     * mark that the request passed through the plugin. Stubbed responses
     * already carry the header (set in {@link #onRequest}).</p>
     */
    @Override
    public ResponseAdvice onResponse(ResponseContext ctx) {
        if (!ctx.isStubbed()) {
            Map<String, String> headers = new HashMap<String, String>();
            headers.put("X-Baafoo-Plugin", PLUGIN_NAME);
            return ResponseAdvice.augment(headers);
        }
        return ResponseAdvice.proceed();
    }

    /**
     * Observation-only event hook: logs request lifecycle events.
     */
    @Override
    public void onEvent(PluginEvent event) {
        switch (event.getType()) {
            case REQUEST_RECEIVED:
            case RULE_MATCHED:
            case RULE_NOT_MATCHED:
                log.debug("[FeignPlugin] Event: {}", event);
                break;
            default:
                break;
        }
    }

    @Override
    public void destroy() {
        stubRegistry.clear();
        interceptCount.set(0);
        log.info("[FeignPlugin] Destroyed, all stubs cleared");
    }

    // ---- Public API (for tests / demo) ----

    public void registerStub(String method, String path, int statusCode, String body, Map<String, String> headers) {
        String key = buildStubKey(method, path);
        StubEntry entry = new StubEntry(statusCode, body, headers != null ? headers : new HashMap<String, String>());
        stubRegistry.put(key, entry);
        log.debug("[FeignPlugin] Stub registered: {} -> status={}", key, statusCode);
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

    // ---- Private helpers ----

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
