package com.baafoo.server.handler;

import com.baafoo.core.model.RecordingEntry;
import com.baafoo.core.model.ResponseEntry;
import com.baafoo.core.model.Rule;
import com.baafoo.core.util.MatchEngine;

import java.util.HashMap;
import java.util.Map;

/**
 * Helper for building recording entries from stub and passthrough responses.
 *
 * <p>Extracted from HttpStubHandler to separate recording logic
 * from request handling logic.</p>
 */
public final class RecordingHelper {

    private RecordingHelper() {}

    public static RecordingEntry buildFromStub(MatchEngine.MatchResult result, String protocol,
                                                String host, int port, String method, String path,
                                                Map<String, String> headers, String body) {
        return buildFromStub(result.getRule(), result.getResponse(), protocol, host, port,
                method, path, headers, body);
    }

    public static RecordingEntry buildFromStub(Rule rule, ResponseEntry entry, String protocol,
                                                String host, int port, String method, String path,
                                                Map<String, String> headers, String body) {
        RecordingEntry rec = new RecordingEntry();
        rec.setRuleId(rule != null ? rule.getId() : null);
        rec.setProtocol(protocol);
        rec.setHost(host);
        rec.setPort(port);
        rec.setMethod(method);
        rec.setPath(path);
        rec.setRequestHeaders(headers);
        rec.setRequestBody(body);
        if (entry != null) {
            rec.setResponseStatusCode(entry.getStatusCode());
            rec.setResponseHeaders(entry.getHeaders() != null ? entry.getHeaders() : new HashMap<String, String>());
            rec.setResponseBody(entry.getBody());
        }
        rec.setResponseTimeMs(0);
        rec.setDirection("request");
        return rec;
    }

    public static RecordingEntry buildFromPassthrough(String ruleId, String agentEnvironment,
                                                       String protocol,
                                                       String host, int port, String method, String path,
                                                       Map<String, String> requestHeaders, String requestBody,
                                                       int statusCode, Map<String, String> responseHeaders,
                                                       String responseBody, long responseTimeMs,
                                                       String agentId, String agentIp) {
        RecordingEntry recording = new RecordingEntry();
        recording.setRuleId(ruleId);
        recording.setEnvironmentId(agentEnvironment);
        recording.setProtocol(protocol != null ? protocol : "http");
        recording.setHost(host);
        recording.setPort(port);
        recording.setMethod(method);
        recording.setPath(path);
        recording.setRequestHeaders(requestHeaders);
        recording.setRequestBody(requestBody);
        recording.setResponseStatusCode(statusCode);
        recording.setResponseHeaders(responseHeaders);
        recording.setResponseBody(responseBody);
        recording.setResponseTimeMs(responseTimeMs);
        recording.setAgentId(agentId);
        recording.setAgentIp(agentIp);
        recording.setDirection("request");
        return recording;
    }

    public static RecordingEntry buildError(String ruleId, String agentEnvironment,
                                             String protocol,
                                             String host, int port, String method, String path,
                                             Map<String, String> requestHeaders, String requestBody,
                                             String errorMessage, long elapsedMs,
                                             String agentId, String agentIp) {
        RecordingEntry recording = new RecordingEntry();
        recording.setRuleId(ruleId);
        recording.setEnvironmentId(agentEnvironment);
        recording.setProtocol(protocol != null ? protocol : "http");
        recording.setHost(host);
        recording.setPort(port);
        recording.setMethod(method);
        recording.setPath(path);
        recording.setRequestHeaders(requestHeaders);
        recording.setRequestBody(requestBody);
        recording.setResponseStatusCode(502);
        recording.setResponseBody("Passthrough failed: " + errorMessage);
        recording.setResponseTimeMs(elapsedMs);
        recording.setAgentId(agentId);
        recording.setAgentIp(agentIp);
        recording.setDirection("request");
        return recording;
    }
}
