package com.baafoo.testspring.grpc;

import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.ServerServiceDefinition;
import io.grpc.MethodDescriptor;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.stub.ServerCalls;
import io.grpc.stub.StreamObserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import javax.annotation.PreDestroy;

/**
 * Real gRPC echo backend for the Baafoo integration-test suite.
 *
 * <p>test-spring normally only acts as a gRPC <b>caller</b> (via
 * {@code GrpcCallerController} / {@code GrpcCallerService}). This server turns
 * test-spring into a gRPC <b>backend</b> as well, so that gRPC
 * PASSTHROUGH / RECORD modes can be exercised end-to-end:</p>
 *
 * <ul>
 *   <li>In STUB mode the Baafoo agent redirects the channel to the stub server
 *       (9005), so this server is never hit — existing G01–G06 still pass.</li>
 *   <li>In PASSTHROUGH mode the agent does NOT intercept, so the channel
 *       connects to {@code greeter.example.com:50051} etc., which (via Docker
 *       network aliases on app-env-a/app-env-b) resolves to this server.</li>
 *   <li>In RECORD mode the agent redirects to 9005, the server forwards to this
 *       real backend and records the exchange.</li>
 * </ul>
 *
 * <p>Responses are tagged with {@link #MARKER} so the full-chain script can
 * distinguish a real-backend response from the Baafoo stub response
 * ("Baafoo gRPC").</p>
 *
 * <p>No .proto codegen — we build the {@link MethodDescriptor}s dynamically
 * (same technique as the client), keeping the test app free of generated
 * sources.</p>
 */
@Component
public class GrpcEchoServer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(GrpcEchoServer.class);

    /** Port the caller targets (must match GrpcCallerController's 50051). */
    public static final int PORT = 50051;

    /** Marker proving a response came from the REAL backend, not the stub. */
    public static final String MARKER = "REAL-GRPC-BACKEND";

    private Server server;

    private static final MethodDescriptor.Marshaller<String> MARSHALLER =
            new MethodDescriptor.Marshaller<String>() {
                @Override
                public InputStream stream(String value) {
                    return new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8));
                }

                @Override
                public String parse(InputStream stream) {
                    try {
                        ByteArrayOutputStream bos = new ByteArrayOutputStream();
                        byte[] buf = new byte[4096];
                        int n;
                        while ((n = stream.read(buf)) != -1) {
                            bos.write(buf, 0, n);
                        }
                        return new String(bos.toByteArray(), StandardCharsets.UTF_8);
                    } catch (Exception e) {
                        throw new RuntimeException("gRPC marshaller parse failed", e);
                    }
                }
            };

    private static MethodDescriptor<String, String> method(
            String service, String methodName, MethodType type) {
        return MethodDescriptor.<String, String>newBuilder()
                .setType(type)
                .setFullMethodName(MethodDescriptor.generateFullMethodName(service, methodName))
                .setRequestMarshaller(MARSHALLER)
                .setResponseMarshaller(MARSHALLER)
                .build();
    }

    private static String echo(String req) {
        return "{\"message\":\"" + MARKER + ":" + req + "\"}";
    }

    private static void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void run(ApplicationArguments args) {
        try {
            ServerBuilder<?> builder = ServerBuilder.forPort(PORT);

            // helloworld.Greeter — all unary methods echo with the real-backend marker
            // Note: gRPC 1.59.x ServerCalls uses async*(handler) — the *ServerMethodDefinition
            // helpers were introduced in a later release.
            builder.addService(ServerServiceDefinition.builder("helloworld.Greeter")
                    .addMethod(method("helloworld.Greeter", "SayHello", MethodType.UNARY),
                            ServerCalls.asyncUnaryCall((req, obs) -> { obs.onNext(echo(req)); obs.onCompleted(); }))
                    .addMethod(method("helloworld.Greeter", "SlowMethod", MethodType.UNARY),
                            ServerCalls.asyncUnaryCall((req, obs) -> { sleepQuietly(200); obs.onNext(echo(req)); obs.onCompleted(); }))
                    .addMethod(method("helloworld.Greeter", "GetUser", MethodType.UNARY),
                            ServerCalls.asyncUnaryCall((req, obs) -> { obs.onNext(echo(req)); obs.onCompleted(); }))
                    .addMethod(method("helloworld.Greeter", "StatusTest", MethodType.UNARY),
                            ServerCalls.asyncUnaryCall((req, obs) -> { obs.onNext(echo(req)); obs.onCompleted(); }))
                    .addMethod(method("helloworld.Greeter", "DelayTest", MethodType.UNARY),
                            ServerCalls.asyncUnaryCall((req, obs) -> { obs.onNext(echo(req)); obs.onCompleted(); }))
                    .build());

            // events.StreamService — server streaming returns 3 tagged messages
            builder.addService(ServerServiceDefinition.builder("events.StreamService")
                    .addMethod(method("events.StreamService", "StreamEvents", MethodType.SERVER_STREAMING),
                            ServerCalls.asyncServerStreamingCall((req, obs) -> {
                                for (int i = 1; i <= 3; i++) {
                                    obs.onNext(MARKER + ":event-" + i);
                                }
                                obs.onCompleted();
                            }))
                    .build());

            // metrics.MetricsCollector — client streaming accumulates then replies
            builder.addService(ServerServiceDefinition.builder("metrics.MetricsCollector")
                    .addMethod(method("metrics.MetricsCollector", "CollectMetrics", MethodType.CLIENT_STREAMING),
                            ServerCalls.asyncClientStreamingCall((obs) -> new StreamObserver<String>() {
                                private final List<String> reqs = new ArrayList<String>();
                                @Override
                                public void onNext(String value) { reqs.add(value); }
                                @Override
                                public void onError(Throwable t) { obs.onError(t); }
                                @Override
                                public void onCompleted() {
                                    obs.onNext(MARKER + ":collected=" + reqs.size());
                                    obs.onCompleted();
                                }
                            }))
                    .build());

            // chat.ChatService — bidirectional streaming echoes each message
            builder.addService(ServerServiceDefinition.builder("chat.ChatService")
                    .addMethod(method("chat.ChatService", "Chat", MethodType.BIDI_STREAMING),
                            ServerCalls.asyncBidiStreamingCall((obs) -> new StreamObserver<String>() {
                                @Override
                                public void onNext(String value) { obs.onNext(MARKER + ":echo=" + value); }
                                @Override
                                public void onError(Throwable t) { obs.onError(t); }
                                @Override
                                public void onCompleted() { obs.onCompleted(); }
                            }))
                    .build());

            server = builder.build();
            server.start();
            log.info("[Baafoo] GrpcEchoServer listening on :{}", PORT);
        } catch (Throwable t) {
            // Non-fatal: if the port is busy (e.g. another instance) the app
            // still boots; only gRPC PASSTHROUGH/RECORD tests would be affected.
            log.warn("[Baafoo] GrpcEchoServer failed to start on :{} — {}", PORT, t.getMessage());
        }
    }

    @PreDestroy
    public void stop() {
        if (server != null) {
            server.shutdownNow();
        }
    }
}
