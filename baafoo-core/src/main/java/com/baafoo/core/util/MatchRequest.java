package com.baafoo.core.util;

import java.util.Map;

/**
 * Encapsulates the inputs to {@link MatchEngine#match(java.util.List, MatchRequest)}
 * and {@link MatchEngine#matchWithFallback(java.util.List, MatchRequest)}.
 *
 * <p>P2-1: replaces the 11-14 parameter method signatures that were previously
 * duplicated across {@code HttpStubHandler}, {@code TcpStubHandler},
 * {@code GrpcUnifiedHandler}, {@code GrpcStubHandler}, {@code GrpcStreamingHandler},
 * {@code MqMatchHelper}, and the agent's {@code RouteManager}. Carrying these
 * fields in a single object:</p>
 *
 * <ul>
 *   <li>makes call sites readable (named parameters via builder),</li>
 *   <li>prevents argument-order bugs (e.g., swapping {@code path} and {@code topic}),</li>
 *   <li>allows adding new match dimensions without changing every call site.</li>
 * </ul>
 *
 * <p>This is a mutable POJO (not a record) so it can be constructed incrementally
 * by handlers that build up match context over multiple stages (e.g., HTTP handler
 * parses path before headers). Use the fluent setters or the builder.</p>
 *
 * <p>Nullability: {@code protocol}, {@code host}, {@code port} should always be
 * set. {@code method}/{@code path} are HTTP-specific. {@code topic} is
 * MQ-specific. {@code headers}/{@code queryParams} default to empty maps if
 * null. {@code body} defaults to empty string if null.</p>
 */
public class MatchRequest {

    private String protocol;
    private String host;
    private int port;
    private String serviceName;
    private String method;
    private String path;
    private String topic;
    private Map<String, String> headers;
    private Map<String, String> queryParams;
    private String body;
    private String grpcService;
    private String grpcMethod;

    public MatchRequest() {
        // Defaults: all fields null/0. Use setters or builder.
    }

    public MatchRequest(String protocol, String host, int port) {
        this.protocol = protocol;
        this.host = host;
        this.port = port;
    }

    // ---- Getters / fluent setters ----

    public String getProtocol() { return protocol; }
    public MatchRequest setProtocol(String protocol) { this.protocol = protocol; return this; }

    public String getHost() { return host; }
    public MatchRequest setHost(String host) { this.host = host; return this; }

    public int getPort() { return port; }
    public MatchRequest setPort(int port) { this.port = port; return this; }

    public String getServiceName() { return serviceName; }
    public MatchRequest setServiceName(String serviceName) { this.serviceName = serviceName; return this; }

    public String getMethod() { return method; }
    public MatchRequest setMethod(String method) { this.method = method; return this; }

    public String getPath() { return path; }
    public MatchRequest setPath(String path) { this.path = path; return this; }

    public String getTopic() { return topic; }
    public MatchRequest setTopic(String topic) { this.topic = topic; return this; }

    public Map<String, String> getHeaders() { return headers; }
    public MatchRequest setHeaders(Map<String, String> headers) { this.headers = headers; return this; }

    public Map<String, String> getQueryParams() { return queryParams; }
    public MatchRequest setQueryParams(Map<String, String> queryParams) { this.queryParams = queryParams; return this; }

    public String getBody() { return body; }
    public MatchRequest setBody(String body) { this.body = body; return this; }

    public String getGrpcService() { return grpcService; }
    public MatchRequest setGrpcService(String grpcService) { this.grpcService = grpcService; return this; }

    public String getGrpcMethod() { return grpcMethod; }
    public MatchRequest setGrpcMethod(String grpcMethod) { this.grpcMethod = grpcMethod; return this; }

    /**
     * Build a {@link MatchRequest} for an HTTP-style protocol (HTTP, gRPC-over-HTTP/1.1).
     */
    public static MatchRequest http(String protocol, String host, int port,
                                     String method, String path,
                                     Map<String, String> headers,
                                     Map<String, String> queryParams, String body) {
        return new MatchRequest(protocol, host, port)
                .setMethod(method)
                .setPath(path)
                .setHeaders(headers)
                .setQueryParams(queryParams)
                .setBody(body);
    }

    /**
     * Build a {@link MatchRequest} for an MQ protocol (Kafka, Pulsar, JMS).
     */
    public static MatchRequest mq(String protocol, String host, int port, String topic,
                                   Map<String, String> headers, String body) {
        return new MatchRequest(protocol, host, port)
                .setTopic(topic)
                .setHeaders(headers)
                .setBody(body);
    }

    /**
     * Build a {@link MatchRequest} for a gRPC-over-HTTP/2 call.
     */
    public static MatchRequest grpc(String host, int port, String grpcService, String grpcMethod,
                                     Map<String, String> headers, String body) {
        return new MatchRequest("grpc", host, port)
                .setGrpcService(grpcService)
                .setGrpcMethod(grpcMethod)
                .setHeaders(headers)
                .setBody(body);
    }

    /**
     * Build a {@link MatchRequest} for a raw TCP request.
     */
    public static MatchRequest tcp(String host, int port, String bodyHex) {
        return new MatchRequest("tcp", host, port).setBody(bodyHex);
    }
}
