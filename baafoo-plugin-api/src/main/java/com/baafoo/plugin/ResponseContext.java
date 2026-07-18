package com.baafoo.plugin;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Context for the response-phase hook.
 *
 * <p>Carries the response after stub generation or real upstream response,
 * before it is sent to the client. Plugins can inspect, replace, or augment.</p>
 *
 * <p><b>Thread-safety:</b> Not thread-safe. The {@code body} field is mutable via
 * its setter. The {@code headers} field is defensively copied on construction to
 * isolate the context from subsequent mutations of the source map.</p>
 */
public class ResponseContext {

    private final String protocol;
    private final String ruleId;
    private final String ruleName;
    private final int statusCode;
    private final Map<String, String> headers;
    private byte[] body;
    private final RequestContext request;
    private final boolean stubbed;

    public ResponseContext(String protocol, String ruleId, String ruleName,
                           int statusCode, Map<String, String> headers, byte[] body,
                           RequestContext request, boolean stubbed) {
        this.protocol = protocol;
        this.ruleId = ruleId;
        this.ruleName = ruleName;
        this.statusCode = statusCode;
        // M-2: Defensive copy to isolate the context from later mutations of the source map
        this.headers = headers != null ? new LinkedHashMap<>(headers) : Collections.emptyMap();
        this.body = body;
        this.request = request;
        this.stubbed = stubbed;
    }

    // --- Getters / Setters ---

    public String getProtocol() { return protocol; }
    public String getRuleId() { return ruleId; }
    public String getRuleName() { return ruleName; }
    public int getStatusCode() { return statusCode; }
    public Map<String, String> getHeaders() { return headers; }
    public byte[] getBody() { return body; }
    public void setBody(byte[] body) { this.body = body; }
    public RequestContext getRequest() { return request; }
    public boolean isStubbed() { return stubbed; }
}
