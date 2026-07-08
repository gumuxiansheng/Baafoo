package com.baafoo.testspring.service;

import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.MethodDescriptor;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.StreamObserver;

import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Dynamic gRPC client for the Baafoo integration-test suite.
 *
 * <p>Unlike a normal gRPC client we do <b>not</b> generate stubs from .proto
 * files. Instead we build a {@link MethodDescriptor} on the fly and call it
 * through {@link ClientCalls}. This keeps the test app free of generated code
 * while still exercising every gRPC call type (unary / server-streaming /
 * client-streaming / bidirectional-streaming).</p>
 *
 * <p><b>Why {@code ManagedChannelBuilder.forTarget(...)}?</b> The Baafoo agent
 * installs {@code GrpcChannelAdvice} on exactly that method
 * ({@code io.grpc.ManagedChannelBuilder.forTarget(String)}). Building the
 * channel this way lets the advice transparently redirect the target to the
 * Baafoo stub gRPC server — so these calls get stubbed like any other rule,
 * with zero changes to the caller.</p>
 */
@Service
public class GrpcCallerService {

    /** String <-> InputStream marshaller (UTF-8). No protobuf involved. */
    private static final MethodDescriptor.Marshaller<String> STRING_MARSHALLER =
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

    private MethodDescriptor<String, String> method(String service, String methodName, MethodType type) {
        return MethodDescriptor.<String, String>newBuilder()
                .setType(type)
                .setFullMethodName(MethodDescriptor.generateFullMethodName(service, methodName))
                .setRequestMarshaller(STRING_MARSHALLER)
                .setResponseMarshaller(STRING_MARSHALLER)
                .build();
    }

    private ManagedChannel channel(String host, int port) {
        // Must use forTarget(...) so GrpcChannelAdvice can redirect to the stub server.
        ManagedChannelBuilder<?> builder = ManagedChannelBuilder.forTarget(host + ":" + port);
        return builder.usePlaintext().build();
    }

    // ==================== Result builders ====================

    private Map<String, Object> result(boolean completed, String grpcStatus, String grpcMessage, String error) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        m.put("completed", completed);
        m.put("grpcStatus", grpcStatus);
        m.put("grpcMessage", grpcMessage);
        m.put("messages", new ArrayList<String>());
        m.put("error", error);
        return m;
    }

    // ==================== Unary ====================

    public Map<String, Object> callUnary(String service, String methodName, String host, int port, String request) {
        ManagedChannel ch = channel(host, port);
        long start = System.currentTimeMillis();
        try {
            MethodDescriptor<String, String> m = method(service, methodName, MethodType.UNARY);
            String resp = ClientCalls.blockingUnaryCall(ch, m, CallOptions.DEFAULT, request);
            Map<String, Object> r = result(true, "0", null, null);
            List<String> msgs = new ArrayList<String>();
            msgs.add(resp);
            r.put("messages", msgs);
            r.put("latencyMs", System.currentTimeMillis() - start);
            return r;
        } catch (StatusRuntimeException e) {
            return statusResult(e.getStatus());
        } catch (Exception e) {
            return result(false, null, null, e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            ch.shutdownNow();
        }
    }

    // ==================== Server streaming ====================

    public Map<String, Object> callServerStreaming(String service, String methodName, String host, int port, String request) {
        ManagedChannel ch = channel(host, port);
        try {
            MethodDescriptor<String, String> m = method(service, methodName, MethodType.SERVER_STREAMING);
            Iterator<String> it = ClientCalls.blockingServerStreamingCall(ch, m, CallOptions.DEFAULT, request);
            List<String> msgs = new ArrayList<String>();
            while (it.hasNext()) {
                msgs.add(it.next());
            }
            Map<String, Object> r = result(true, "0", null, null);
            r.put("messages", msgs);
            return r;
        } catch (StatusRuntimeException e) {
            return statusResult(e.getStatus());
        } catch (Exception e) {
            return result(false, null, null, e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            ch.shutdownNow();
        }
    }

    // ==================== Client streaming ====================

    public Map<String, Object> callClientStreaming(String service, String methodName, String host, int port, List<String> requests) {
        ManagedChannel ch = channel(host, port);
        try {
            MethodDescriptor<String, String> m = method(service, methodName, MethodType.CLIENT_STREAMING);
            final List<String> responses = new ArrayList<String>();
            final AtomicReference<String> statusRef = new AtomicReference<String>("0");
            final AtomicReference<String> msgRef = new AtomicReference<String>(null);
            final CountDownLatch latch = new CountDownLatch(1);

            StreamObserver<String> requestObserver = ClientCalls.asyncClientStreamingCall(ch.newCall(m, CallOptions.DEFAULT),
                    new StreamObserver<String>() {
                        @Override
                        public void onNext(String value) {
                            responses.add(value);
                        }

                        @Override
                        public void onError(Throwable t) {
                            applyStatus(t, statusRef, msgRef);
                            latch.countDown();
                        }

                        @Override
                        public void onCompleted() {
                            latch.countDown();
                        }
                    });

            for (String req : requests) {
                requestObserver.onNext(req);
            }
            requestObserver.onCompleted();

            latch.await(15, TimeUnit.SECONDS);
            Map<String, Object> r = result(true, statusRef.get(), msgRef.get(), null);
            r.put("messages", responses);
            return r;
        } catch (Exception e) {
            return result(false, null, null, e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            ch.shutdownNow();
        }
    }

    // ==================== Bidirectional streaming ====================

    public Map<String, Object> callBidi(String service, String methodName, String host, int port, List<String> requests) {
        ManagedChannel ch = channel(host, port);
        try {
            MethodDescriptor<String, String> m = method(service, methodName, MethodType.BIDI_STREAMING);
            final List<String> responses = new ArrayList<String>();
            final AtomicReference<String> statusRef = new AtomicReference<String>("0");
            final AtomicReference<String> msgRef = new AtomicReference<String>(null);
            final CountDownLatch latch = new CountDownLatch(1);

            StreamObserver<String> requestObserver = ClientCalls.asyncBidiStreamingCall(ch.newCall(m, CallOptions.DEFAULT),
                    new StreamObserver<String>() {
                        @Override
                        public void onNext(String value) {
                            responses.add(value);
                        }

                        @Override
                        public void onError(Throwable t) {
                            applyStatus(t, statusRef, msgRef);
                            latch.countDown();
                        }

                        @Override
                        public void onCompleted() {
                            latch.countDown();
                        }
                    });

            for (String req : requests) {
                requestObserver.onNext(req);
            }
            requestObserver.onCompleted();

            latch.await(15, TimeUnit.SECONDS);
            Map<String, Object> r = result(true, statusRef.get(), msgRef.get(), null);
            r.put("messages", responses);
            return r;
        } catch (Exception e) {
            return result(false, null, null, e.getClass().getSimpleName() + ": " + e.getMessage());
        } finally {
            ch.shutdownNow();
        }
    }

    // ==================== Helpers ====================

    private Map<String, Object> statusResult(Status status) {
        return result(true, String.valueOf(status.getCode().value()), status.getDescription(), null);
    }

    private void applyStatus(Throwable t, AtomicReference<String> statusRef, AtomicReference<String> msgRef) {
        if (t instanceof StatusRuntimeException) {
            Status s = ((StatusRuntimeException) t).getStatus();
            statusRef.set(String.valueOf(s.getCode().value()));
            msgRef.set(s.getDescription());
        } else {
            statusRef.set("-1");
            msgRef.set(t.getMessage());
        }
    }
}
