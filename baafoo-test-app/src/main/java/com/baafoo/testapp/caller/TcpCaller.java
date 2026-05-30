package com.baafoo.testapp.caller;

import com.baafoo.testapp.BaafooTestApp;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

public class TcpCaller implements BaafooTestApp.Caller {

    private static final String TARGET_HOST = "127.0.0.1";
    private static final int TARGET_PORT = 9999;

    @Override
    public String name() {
        return "TCP Socket 外调测试 (目标: " + TARGET_HOST + ":" + TARGET_PORT + ")";
    }

    @Override
    public void run() throws Exception {
        testBasicConnect();
        testSendReceive();
        testMultipleMessages();
    }

    private void testBasicConnect() throws Exception {
        System.out.println("  [连接] " + TARGET_HOST + ":" + TARGET_PORT);
        Socket socket = new Socket();
        socket.setSoTimeout(5000);
        try {
            socket.connect(new java.net.InetSocketAddress(TARGET_HOST, TARGET_PORT), 5000);
            System.out.println("    连接成功: " + socket.getRemoteSocketAddress());
            System.out.println("    本地地址: " + socket.getLocalAddress() + ":" + socket.getLocalPort());

            boolean redirected = socket.getPort() != TARGET_PORT
                    || !socket.getInetAddress().getHostAddress().equals(TARGET_HOST);
            System.out.println("    挡板拦截: " + (redirected ? "✓ 是 (连接被重定向)" : "✗ 否 (直连)"));
        } catch (java.io.IOException e) {
            System.out.println("    连接失败: " + e.getMessage());
            System.out.println("    (无 Agent 时连接失败属正常行为)");
        } finally {
            socket.close();
        }
        System.out.println();
    }

    private void testSendReceive() throws Exception {
        System.out.println("  [发送/接收] " + TARGET_HOST + ":" + TARGET_PORT);
        Socket socket = new Socket();
        socket.setSoTimeout(5000);
        try {
            socket.connect(new java.net.InetSocketAddress(TARGET_HOST, TARGET_PORT), 5000);

            String request = "HELLO-BAAFOO-TCP-TEST\r\n";
            OutputStream os = socket.getOutputStream();
            os.write(request.getBytes(StandardCharsets.UTF_8));
            os.flush();
            System.out.println("    发送: " + request.trim());

            InputStream is = socket.getInputStream();
            byte[] buf = new byte[4096];
            int len = is.read(buf);
            if (len > 0) {
                String response = new String(buf, 0, len, StandardCharsets.UTF_8);
                System.out.println("    接收: " + response.trim());
                System.out.println("    挡板拦截: ✓ 是 (收到挡板响应)");
            } else {
                System.out.println("    接收: (空/连接关闭)");
            }
        } catch (java.io.IOException e) {
            System.out.println("    失败: " + e.getMessage());
            System.out.println("    (无 Agent 时连接失败属正常行为)");
        } finally {
            socket.close();
        }
        System.out.println();
    }

    private void testMultipleMessages() throws Exception {
        System.out.println("  [多次发送] " + TARGET_HOST + ":" + TARGET_PORT);
        Socket socket = new Socket();
        socket.setSoTimeout(5000);
        try {
            socket.connect(new java.net.InetSocketAddress(TARGET_HOST, TARGET_PORT), 5000);

            for (int i = 1; i <= 3; i++) {
                String msg = "TCP-MSG-" + i + "\r\n";
                OutputStream os = socket.getOutputStream();
                os.write(msg.getBytes(StandardCharsets.UTF_8));
                os.flush();
                System.out.println("    发送 #" + i + ": " + msg.trim());

                InputStream is = socket.getInputStream();
                byte[] buf = new byte[4096];
                int len = is.read(buf);
                if (len > 0) {
                    System.out.println("    接收 #" + i + ": " + new String(buf, 0, len, StandardCharsets.UTF_8).trim());
                }
            }
        } catch (java.io.IOException e) {
            System.out.println("    失败: " + e.getMessage());
            System.out.println("    (无 Agent 时连接失败属正常行为)");
        } finally {
            socket.close();
        }
        System.out.println();
    }
}
