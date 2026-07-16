package com.baafoo.testapp.caller;

import com.baafoo.testapp.BaafooTestApp;

import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.MethodDescriptor;
import io.grpc.MethodDescriptor.MethodType;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.ClientCalls;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;

/**
 * gRPC 外调测试 caller（无 .proto codegen，动态构建 {@link MethodDescriptor}）。
 *
 * <p>与 test-spring 的 {@code GrpcCallerService} 同样的技巧：通过
 * {@code ManagedChannelBuilder.forTarget(...)} 建连，使 Baafoo agent 的
 * {@code GrpcChannelAdvice} 能把通道透明重定向到挡板 gRPC 服务（端口 9005），
 * 从而像其他协议一样被 stub / record。无 Agent 或后端不可达时连接失败属正常。</p>
 */
public class GrpcCaller implements BaafooTestApp.Caller {

    private static final String TARGET_HOST = "greeter.example.com";
    private static final int TARGET_PORT = 50051;

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

    private static MethodDescriptor<String, String> method(String service, String name, MethodType type) {
        return MethodDescriptor.<String, String>newBuilder()
                .setType(type)
                .setFullMethodName(MethodDescriptor.generateFullMethodName(service, name))
                .setRequestMarshaller(MARSHALLER)
                .setResponseMarshaller(MARSHALLER)
                .build();
    }

    @Override
    public String name() {
        return "gRPC 外调测试 (目标: " + TARGET_HOST + ":" + TARGET_PORT + ")";
    }

    @Override
    public void run() throws Exception {
        testUnary();
        testServerStreaming();
    }

    private ManagedChannel channel() {
        // forTarget(...) 才能让 GrpcChannelAdvice 重定向到挡板 gRPC 服务
        return ManagedChannelBuilder.forTarget(TARGET_HOST + ":" + TARGET_PORT).usePlaintext().build();
    }

    private void testUnary() {
        System.out.println("  [gRPC Unary] " + TARGET_HOST + ":" + TARGET_PORT + " helloworld.Greeter/SayHello");
        ManagedChannel ch = channel();
        try {
            String resp = ClientCalls.blockingUnaryCall(ch,
                    method("helloworld.Greeter", "SayHello", MethodType.UNARY),
                    CallOptions.DEFAULT, "hello-from-test-app");
            System.out.println("    响应: " + resp);
            System.out.println("    挡板拦截: ✓ 已通过 Baafoo gRPC 通道 (收到响应即说明被拦截/转发)");
        } catch (StatusRuntimeException e) {
            System.out.println("    gRPC 状态: code=" + e.getStatus().getCode() + " desc=" + e.getStatus().getDescription());
        } catch (Exception e) {
            System.out.println("    失败: " + e.getMessage());
            System.out.println("    (无 Agent / 后端不可达时失败属正常行为)");
        } finally {
            ch.shutdownNow();
        }
        System.out.println();
    }

    private void testServerStreaming() {
        System.out.println("  [gRPC ServerStream] " + TARGET_HOST + ":" + TARGET_PORT + " events.StreamService/StreamEvents");
        ManagedChannel ch = channel();
        try {
            Iterator<String> it = ClientCalls.blockingServerStreamingCall(ch,
                    method("events.StreamService", "StreamEvents", MethodType.SERVER_STREAMING),
                    CallOptions.DEFAULT, "start");
            int count = 0;
            while (it.hasNext()) {
                System.out.println("    流消息[" + (++count) + "]: " + it.next());
            }
            System.out.println("    共收到 " + count + " 条流消息");
        } catch (StatusRuntimeException e) {
            System.out.println("    gRPC 状态: code=" + e.getStatus().getCode() + " desc=" + e.getStatus().getDescription());
        } catch (Exception e) {
            System.out.println("    失败: " + e.getMessage());
        } finally {
            ch.shutdownNow();
        }
        System.out.println();
    }
}
