package com.baafoo.testspring.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

@Service
public class SocketCallerService {

    private static final Logger log = LoggerFactory.getLogger(SocketCallerService.class);

    public Map<String, Object> testBiologicalSocket(String host, int port) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        Socket socket = new Socket();
        try {
            socket.setSoTimeout(5000);
            socket.connect(new InetSocketAddress(host, port), 5000);
            result.put("connected", true);
            result.put("remoteAddress", socket.getRemoteSocketAddress().toString());
            boolean redirected = socket.getPort() != port
                    || !socket.getInetAddress().getHostAddress().equals(host);
            result.put("intercepted", redirected);
            String request = "HELLO-BAAFOO-TCP\r\n";
            OutputStream os = socket.getOutputStream();
            os.write(request.getBytes(StandardCharsets.UTF_8));
            os.flush();
            result.put("sent", request.trim());
            InputStream is = socket.getInputStream();
            byte[] buf = new byte[4096];
            int len = is.read(buf);
            if (len > 0) {
                result.put("received", new String(buf, 0, len, StandardCharsets.UTF_8).trim());
            }
            log.info("BIO Socket call complete: intercepted={}", redirected);
        } catch (Exception e) {
            result.put("connected", false);
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            log.warn("BIO Socket call failed: {}", e.getMessage());
        } finally {
            try { socket.close(); } catch (Exception ignored) {}
        }
        return result;
    }

    public Map<String, Object> testNioSocket(String host, int port) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        SocketChannel channel = null;
        try {
            channel = SocketChannel.open();
            channel.configureBlocking(true);
            channel.socket().setSoTimeout(5000);
            channel.connect(new InetSocketAddress(host, port));
            result.put("connected", true);
            result.put("remoteAddress", channel.getRemoteAddress().toString());
            boolean redirected = ((InetSocketAddress) channel.getRemoteAddress()).getPort() != port;
            result.put("intercepted", redirected);
            String request = "HELLO-BAAFOO-NIO\r\n";
            ByteBuffer writeBuf = ByteBuffer.wrap(request.getBytes(StandardCharsets.UTF_8));
            channel.write(writeBuf);
            result.put("sent", request.trim());
            ByteBuffer readBuf = ByteBuffer.allocate(4096);
            int len = channel.read(readBuf);
            if (len > 0) {
                readBuf.flip();
                byte[] data = new byte[readBuf.remaining()];
                readBuf.get(data);
                result.put("received", new String(data, StandardCharsets.UTF_8).trim());
            }
            log.info("NIO Socket call complete: intercepted={}", redirected);
        } catch (Exception e) {
            result.put("connected", false);
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            log.warn("NIO Socket call failed: {}", e.getMessage());
        } finally {
            if (channel != null) try { channel.close(); } catch (Exception ignored) {}
        }
        return result;
    }
}
