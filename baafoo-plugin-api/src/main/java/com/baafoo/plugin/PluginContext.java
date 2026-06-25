package com.baafoo.plugin;

import com.baafoo.plugin.service.PluginServices;

import java.util.Collections;
import java.util.Map;
import java.util.concurrent.Callable;

/**
 * Intercept context passed from Advice to Plugin.
 * Contains all information about the intercepted call,
 * including protocol-specific fields for fine-grained routing.
 */
public class PluginContext {

    // ---- Generic fields ----

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

    /** Plugin-specific configuration (keyed by plugin name in baafoo-agent.yml) */
    private Map<String, Object> pluginConfig;

    /** Injected services (null in Agent-only mode) */
    private PluginServices services;

    // ---- Protocol-specific fields (all optional, null by default) ----

    /** Kafka / Pulsar / JMS / MQTT topic name */
    private String topic;

    /** Kafka partition number */
    private Integer partition;

    /** Kafka message key */
    private String key;

    /** Pulsar tenant */
    private String tenant;

    /** Pulsar namespace */
    private String namespace;

    /** JMS destination name (queue or topic) */
    private String destination;

    /** JMS message type: text, bytes, map, object */
    private String messageType;

    /** HTTP method (GET, POST, PUT, DELETE, etc.) */
    private String method;

    /** HTTP request path */
    private String path;

    /** HTTP query parameters */
    private Map<String, String> queryParams;

    public PluginContext() {
        this.headers = Collections.emptyMap();
        this.requestData = new byte[0];
        this.pluginConfig = Collections.emptyMap();
    }

    // --- Generic Getters / Setters ---

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

    public Map<String, Object> getPluginConfig() { return pluginConfig; }
    public void setPluginConfig(Map<String, Object> pluginConfig) { this.pluginConfig = pluginConfig; }

    // --- Protocol-specific Getters / Setters ---

    public String getTopic() { return topic; }
    public void setTopic(String topic) { this.topic = topic; }

    public Integer getPartition() { return partition; }
    public void setPartition(Integer partition) { this.partition = partition; }

    public String getKey() { return key; }
    public void setKey(String key) { this.key = key; }

    public String getTenant() { return tenant; }
    public void setTenant(String tenant) { this.tenant = tenant; }

    public String getNamespace() { return namespace; }
    public void setNamespace(String namespace) { this.namespace = namespace; }

    public String getDestination() { return destination; }
    public void setDestination(String destination) { this.destination = destination; }

    public String getMessageType() { return messageType; }
    public void setMessageType(String messageType) { this.messageType = messageType; }

    public String getMethod() { return method; }
    public void setMethod(String method) { this.method = method; }

    public String getPath() { return path; }
    public void setPath(String path) { this.path = path; }

    public Map<String, String> getQueryParams() { return queryParams; }
    public void setQueryParams(Map<String, String> queryParams) { this.queryParams = queryParams; }

    /** @return injected services, or null if not available (Agent-only mode) */
    public PluginServices getServices() { return services; }
    public void setServices(PluginServices services) { this.services = services; }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("PluginContext{");
        sb.append("protocol='").append(protocol).append('\'');
        sb.append(", host='").append(host).append('\'');
        sb.append(", port=").append(port);
        if (serviceName != null) sb.append(", serviceName='").append(serviceName).append('\'');
        if (ruleId != null) sb.append(", ruleId='").append(ruleId).append('\'');
        // Protocol-specific: only include non-null fields
        if (topic != null) sb.append(", topic='").append(topic).append('\'');
        if (partition != null) sb.append(", partition=").append(partition);
        if (key != null) sb.append(", key='").append(key).append('\'');
        if (tenant != null) sb.append(", tenant='").append(tenant).append('\'');
        if (namespace != null) sb.append(", namespace='").append(namespace).append('\'');
        if (destination != null) sb.append(", destination='").append(destination).append('\'');
        if (messageType != null) sb.append(", messageType='").append(messageType).append('\'');
        if (method != null) sb.append(", method='").append(method).append('\'');
        if (path != null) sb.append(", path='").append(path).append('\'');
        if (queryParams != null && !queryParams.isEmpty()) sb.append(", queryParams=").append(queryParams);
        sb.append('}');
        return sb.toString();
    }
}
