package com.baafoo.testspring.controller;

import com.baafoo.testspring.service.GrpcCallerService;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * gRPC caller endpoints for the Baafoo integration-test suite.
 *
 * <p>Each endpoint drives one of the {@code grpc-*.json} rules through the
 * Baafoo agent (which redirects the channel to the stub gRPC server). The
 * returned JSON is intentionally flat so the PowerShell full-chain script can
 * assert on it without parsing nested objects:</p>
 *
 * <pre>
 * { "completed": true, "grpcStatus": "0", "grpcMessage": null,
 *   "messages": ["..."], "error": null, "latencyMs": 12 }
 * </pre>
 *
 * <ul>
 *   <li>/api/grpc/greeter       → helloworld.Greeter/SayHello        (unary)</li>
 *   <li>/api/grpc/slow          → helloworld.Greeter/SlowMethod      (unary + delay)</li>
 *   <li>/api/grpc/error         → helloworld.Greeter/GetUser         (unary, grpc-status 5)</li>
 *   <li>/api/grpc/server-stream → events.StreamService/StreamEvents  (server streaming)</li>
 *   <li>/api/grpc/client-stream → metrics.MetricsCollector/CollectMetrics (client streaming)</li>
 *   <li>/api/grpc/bidi          → chat.ChatService/Chat              (bidirectional streaming)</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/grpc")
public class GrpcCallerController {

    private final GrpcCallerService grpcCallerService;

    public GrpcCallerController(GrpcCallerService grpcCallerService) {
        this.grpcCallerService = grpcCallerService;
    }

    @GetMapping("/greeter")
    public Map<String, Object> greeter() {
        return grpcCallerService.callUnary(
                "helloworld.Greeter", "SayHello", "greeter.example.com", 50051, "hello");
    }

    @GetMapping("/slow")
    public Map<String, Object> slow() {
        return grpcCallerService.callUnary(
                "helloworld.Greeter", "SlowMethod", "greeter.example.com", 50051, "hello");
    }

    @GetMapping("/error")
    public Map<String, Object> error() {
        // Expects grpc-status 5 (NOT_FOUND) from the grpc-error rule.
        return grpcCallerService.callUnary(
                "helloworld.Greeter", "GetUser", "greeter.example.com", 50051, "1");
    }

    @GetMapping("/server-stream")
    public Map<String, Object> serverStream() {
        return grpcCallerService.callServerStreaming(
                "events.StreamService", "StreamEvents", "stream.example.com", 50051, "start");
    }

    @GetMapping("/client-stream")
    public Map<String, Object> clientStream() {
        List<String> requests = Arrays.asList("metric-a", "metric-b", "metric-c");
        return grpcCallerService.callClientStreaming(
                "metrics.MetricsCollector", "CollectMetrics", "metrics.example.com", 50051, requests);
    }

    @GetMapping("/bidi")
    public Map<String, Object> bidi() {
        List<String> requests = Arrays.asList("hello", "world");
        return grpcCallerService.callBidi(
                "chat.ChatService", "Chat", "chat.example.com", 50051, requests);
    }
}
