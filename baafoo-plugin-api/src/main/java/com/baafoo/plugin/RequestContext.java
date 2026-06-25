package com.baafoo.plugin;

import java.util.Collections;
import java.util.Map;

/**
 * Context for the request-phase hook.
 *
 * <p>Carries the fully parsed request (method, path, headers, body, protocol-specific
 * fields) before rule matching. Plugins can inspect, modify, or short-circuit.</p>
 *
 * <p>Only uses JDK types — no domain model dependency.</p>
 */
public class RequestContext {

    /** Protocol */
    private final String protocol;

    /** HTTP method / gRPC method / MQ topic */
    private final String method;

    /** HTTP path / gRPC path (e.g., /helloworld.Greeter/SayHello) */
    private final String path;

    /** Request headers */
    private final Map<String, String> headers;

    /** Query parameters (HTTP) */
    private final Map<String, String> queryParams;

    /** Request body (raw bytes) */
    private final byte[] body;

    /** Target host */
    private final String host;

    /** Target port */
    private final int port;

    /** Agent environment ID */
    private final String environmentId;

    /** Whether this is a recording session */
    private final boolean recording;

    // --- Protocol-specific fields ---

    /** gRPC service name (extracted from path) */
    private final String grpcService;

    /** gRPC method name (extracted from path) */
    private final String grpcMethod;

    /** Kafka/Pulsar/JMS topic */
    private final String topic;

    /** Kafka partition */
    private final Integer partition;

    /** Kafka message key */
    private final String messageKey;

    // --- Modifiable fields (for MODIFY advice) ---

    private Map<String, String> modifiedHeaders;
    private byte[] modifiedBody;

    public RequestContext(String protocol, String method, String path,
                          Map<String, String> headers, Map<String, String> queryParams,
                          byte[] body, String host, int port,
                          String environmentId, boolean recording) {
        this(protocol, method, path, headers, queryParams, body, host, port,
                environmentId, recording, null, null, null, null, null);
    }

    public RequestContext(String protocol, String method, String path,
                          Map<String, String> headers, Map<String, String> queryParams,
                          byte[] body, String host, int port,
                          String environmentId, boolean recording,
                          String grpcService, String grpcMethod,
                          String topic, Integer partition, String messageKey) {
        this.protocol = protocol;
        this.method = method;
        this.path = path;
        this.headers = headers != null ? headers : Collections.emptyMap();
        this.queryParams = queryParams != null ? queryParams : Collections.emptyMap();
        this.body = body != null ? body : new byte[0];
        this.host = host;
        this.port = port;
        this.environmentId = environmentId;
        this.recording = recording;
        this.grpcService = grpcService;
        this.grpcMethod = grpcMethod;
        this.topic = topic;
        this.partition = partition;
        this.messageKey = messageKey;
    }

    // --- Getters ---

    public String getProtocol() { return protocol; }
    public String getMethod() { return method; }
    public String getPath() { return path; }
    public Map<String, String> getHeaders() { return headers; }
    public Map<String, String> getQueryParams() { return queryParams; }
    public byte[] getBody() { return body; }
    public String getHost() { return host; }
    public int getPort() { return port; }
    public String getEnvironmentId() { return environmentId; }
    public boolean isRecording() { return recording; }
    public String getGrpcService() { return grpcService; }
    public String getGrpcMethod() { return grpcMethod; }
    public String getTopic() { return topic; }
    public Integer getPartition() { return partition; }
    public String getMessageKey() { return messageKey; }
    public Map<String, String> getModifiedHeaders() { return modifiedHeaders; }
    public byte[] getModifiedBody() { return modifiedBody; }

    public void setModifiedHeaders(Map<String, String> h) { this.modifiedHeaders = h; }
    public void setModifiedBody(byte[] b) { this.modifiedBody = b; }
}
