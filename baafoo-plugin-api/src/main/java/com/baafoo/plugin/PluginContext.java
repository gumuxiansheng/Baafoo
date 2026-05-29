package com.baafoo.plugin;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Intercept context passed from Advice to Plugin.
 * Contains all information about the intercepted call.
 */
public class PluginContext {

    /** Target protocol: http, tcp, kafka, pulsar, jms */
    private String protocol;

    /** Target host */
    private String host;

    /** Target port */
    private int port;

    /** Service name (for Consul-based discovery) */
    private String serviceName;

    /** Request headers */
    private Map<String, String> headers;

    /** Request body data (raw bytes) */
    private byte[] requestData;

    /** The original callable to execute the real request */
    private Callable<InterceptResult> originalCall;

    /** Rule ID that matched (if any) */
    private String ruleId;

    /** Rule name that matched (if any) */
    private String ruleName;

    /** Response preset name (for parameterized rules) */
    private String responseName;

    /** Matched condition index (for parameterized rules) */
    private int conditionIndex;

    /** Whether this is a recording session */
    private boolean recording;

    public PluginContext() {
        this.headers = Collections.emptyMap();
        this.requestData = new byte[0];
    }

    // --- Getters / Setters ---

    public String getProtocol() { return protocol; }
    public void setProtocol(String protocol) { this.protocol = protocol; }

    public String getHost() { return host; }
    public void setHost(String host) { this.host = host; }

    public int getPort() { return port; }
    public void setPort(int port) { this.port = port; }

    public String getServiceName() { return serviceName; }
    public void setServiceName(String serviceName) { this.serviceName = serviceName; }

    public Map<String, String> getHeaders() { return headers; }
    public void setHeaders(Map<String, String> headers) { this.headers = headers; }

    public byte[] getRequestData() { return requestData; }
    public void setRequestData(byte[] requestData) { this.requestData = requestData; }

    public Callable<InterceptResult> getOriginalCall() { return originalCall; }
    public void setOriginalCall(Callable<InterceptResult> originalCall) { this.originalCall = originalCall; }

    public String getRuleId() { return ruleId; }
    public void setRuleId(String ruleId) { this.ruleId = ruleId; }

    public String getRuleName() { return ruleName; }
    public void setRuleName(String ruleName) { this.ruleName = ruleName; }

    public String getResponseName() { return responseName; }
    public void setResponseName(String responseName) { this.responseName = responseName; }

    public int getConditionIndex() { return conditionIndex; }
    public void setConditionIndex(int conditionIndex) { this.conditionIndex = conditionIndex; }

    public boolean isRecording() { return recording; }
    public void setRecording(boolean recording) { this.recording = recording; }

    @Override
    public String toString() {
        return "PluginContext{" +
                "protocol='" + protocol + '\'' +
                ", host='" + host + '\'' +
                ", port=" + port +
                ", serviceName='" + serviceName + '\'' +
                ", ruleId='" + ruleId + '\'' +
                '}';
    }
}
