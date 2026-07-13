package com.baafoo.core.util;

import org.junit.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.Assert.*;

public class MatchRequestTest {

    @Test
    public void defaultConstructor() {
        MatchRequest req = new MatchRequest();
        assertNull(req.getProtocol());
        assertNull(req.getHost());
        assertEquals(0, req.getPort());
    }

    @Test
    public void threeArgConstructor() {
        MatchRequest req = new MatchRequest("http", "example.com", 8080);
        assertEquals("http", req.getProtocol());
        assertEquals("example.com", req.getHost());
        assertEquals(8080, req.getPort());
    }

    @Test
    public void fluentSetters() {
        MatchRequest req = new MatchRequest();
        MatchRequest result = req.setProtocol("tcp")
                .setHost("localhost")
                .setPort(9090)
                .setServiceName("svc")
                .setMethod("POST")
                .setPath("/api")
                .setTopic("topic")
                .setBody("body")
                .setGrpcService("GrpcService")
                .setGrpcMethod("GrpcMethod");

        assertSame(req, result);
        assertEquals("tcp", req.getProtocol());
        assertEquals("localhost", req.getHost());
        assertEquals(9090, req.getPort());
        assertEquals("svc", req.getServiceName());
        assertEquals("POST", req.getMethod());
        assertEquals("/api", req.getPath());
        assertEquals("topic", req.getTopic());
        assertEquals("body", req.getBody());
        assertEquals("GrpcService", req.getGrpcService());
        assertEquals("GrpcMethod", req.getGrpcMethod());
    }

    @Test
    public void headersAndQueryParams() {
        Map<String, String> headers = new HashMap<>();
        headers.put("Content-Type", "application/json");
        Map<String, String> query = new HashMap<>();
        query.put("page", "1");

        MatchRequest req = new MatchRequest();
        req.setHeaders(headers).setQueryParams(query);

        assertEquals("application/json", req.getHeaders().get("Content-Type"));
        assertEquals("1", req.getQueryParams().get("page"));
    }

    @Test
    public void httpFactory() {
        Map<String, String> h = new HashMap<>();
        Map<String, String> q = new HashMap<>();
        MatchRequest req = MatchRequest.http("http", "host.com", 80, "GET", "/path", h, q, "body");

        assertEquals("http", req.getProtocol());
        assertEquals("host.com", req.getHost());
        assertEquals(80, req.getPort());
        assertEquals("GET", req.getMethod());
        assertEquals("/path", req.getPath());
        assertEquals("body", req.getBody());
    }

    @Test
    public void mqFactory() {
        Map<String, String> h = new HashMap<>();
        MatchRequest req = MatchRequest.mq("kafka", "broker:9092", 9092, "my-topic", h, "payload");

        assertEquals("kafka", req.getProtocol());
        assertEquals("broker:9092", req.getHost());
        assertEquals("my-topic", req.getTopic());
        assertEquals("payload", req.getBody());
    }

    @Test
    public void grpcFactory() {
        Map<String, String> h = new HashMap<>();
        MatchRequest req = MatchRequest.grpc("grpc-host", 50051, "Greeter", "SayHello", h, "bytes");

        assertEquals("grpc", req.getProtocol());
        assertEquals("grpc-host", req.getHost());
        assertEquals(50051, req.getPort());
        assertEquals("Greeter", req.getGrpcService());
        assertEquals("SayHello", req.getGrpcMethod());
    }

    @Test
    public void tcpFactory() {
        MatchRequest req = MatchRequest.tcp("tcp-host", 9091, "deadbeef");

        assertEquals("tcp", req.getProtocol());
        assertEquals("tcp-host", req.getHost());
        assertEquals(9091, req.getPort());
        assertEquals("deadbeef", req.getBody());
    }
}
