package com.baafoo.testapp.caller;

import com.baafoo.testapp.BaafooTestApp;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;

public class NioTcpCaller implements BaafooTestApp.Caller {

    private static final String TARGET_HOST = "127.0.0.1";
    private static final int TARGET_PORT = 9999;

    @Override
    public String name() {
        return "NIO Socket 外调测试 (目标: " + TARGET_HOST + ":" + TARGET_PORT + ")";
    }

    @Override
    public void run() throws Exception {
        testBasicConnect();
        testSendReceive();
    }

    private void testBasicConnect() throws Exception {
        System.out.println("  [NIO 连接] " + TARGET_HOST + ":" + TARGET_PORT);
        SocketChannel channel = SocketChannel.open();
        try {
            channel.configureBlocking(true);
            channel.socket().setSoTimeout(5000);
            SocketAddress remote = new InetSocketAddress(TARGET_HOST, TARGET_PORT);
            channel.connect(remote);
            System.out.println("    连接成功: " + channel.getRemoteAddress());

            boolean redirected = ((InetSocketAddress) channel.getRemoteAddress()).getPort() != TARGET_PORT;
            System.out.println("    挡板拦截: " + (redirected ? "✓ 是 (连接被重定向)" : "✗ 否 (直连)"));
        } catch (java.io.IOException e) {
            System.out.println("    连接失败: " + e.getMessage());
            System.out.println("    (无 Agent 时连接失败属正常行为)");
        } finally {
            channel.close();
        }
        System.out.println();
    }

    private void testSendReceive() throws Exception {
        System.out.println("  [NIO 发送/接收] " + TARGET_HOST + ":" + TARGET_PORT);
        SocketChannel channel = SocketChannel.open();
        try {
            channel.configureBlocking(true);
            channel.socket().setSoTimeout(5000);
            channel.connect(new InetSocketAddress(TARGET_HOST, TARGET_PORT));

            String request = "HELLO-BAAFOO-NIO-TEST\r\n";
            ByteBuffer writeBuf = ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8));
            channel.write(writeBuf);
            System.out.println("    发送: " + request.trim());

            ByteBuffer readBuf = ByteBuffer.allocate(4096);
            int len = channel.read(readBuf);
            if (len > 0) {
                // L-7: Cast to java.nio.Buffer is intentional — ByteBuffer.flip() was inherited
                // from Buffer in Java 8, but Java 9+ made flip() final on ByteBuffer and only
                // Buffer.flip() remains overridable. The cast forces the call to use Buffer.flip()
                // so the code compiles cleanly on both Java 8 and 9+.
                ((java.nio.Buffer) readBuf).flip();
                byte[] data = new byte[readBuf.remaining()];
                readBuf.get(data);
                String response = new String(data, StandardCharsets.UTF_8);
                System.out.println("    接收: " + response.trim());
                System.out.println("    挡板拦截: ✓ 是 (收到挡板响应)");
            } else {
                System.out.println("    接收: (空/连接关闭)");
            }
        } catch (java.io.IOException e) {
            System.out.println("    失败: " + e.getMessage());
            System.out.println("    (无 Agent 时连接失败属正常行为)");
        } finally {
            channel.close();
        }
        System.out.println();
    }
}
