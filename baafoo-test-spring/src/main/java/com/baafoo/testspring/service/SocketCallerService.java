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
import java.nio.charset.Charset;
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
                ((java.nio.Buffer) readBuf).flip();
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

    public Map<String, Object> testMultiroundSocket(String host, int port) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        Socket socket = new Socket();
        try {
            socket.setSoTimeout(5000);
            socket.connect(new InetSocketAddress(host, port), 5000);
            result.put("connected", true);
            boolean redirected = socket.getPort() != port
                    || !socket.getInetAddress().getHostAddress().equals(host);
            result.put("intercepted", redirected);
            OutputStream os = socket.getOutputStream();
            InputStream is = socket.getInputStream();
            byte[] buf = new byte[4096];

            // Round 1: LOGIN
            os.write("LOGIN:admin:password\r\n".getBytes(StandardCharsets.UTF_8));
            os.flush();
            int len = is.read(buf);
            String round1Response = len > 0 ? new String(buf, 0, len, StandardCharsets.UTF_8).trim() : "";
            result.put("round1_sent", "LOGIN:admin:password");
            result.put("round1_received", round1Response);

            // Round 2: QUERY
            os.write("QUERY:users\r\n".getBytes(StandardCharsets.UTF_8));
            os.flush();
            len = is.read(buf);
            String round2Response = len > 0 ? new String(buf, 0, len, StandardCharsets.UTF_8).trim() : "";
            result.put("round2_sent", "QUERY:users");
            result.put("round2_received", round2Response);

            // Round 3: LOGOUT
            os.write("LOGOUT\r\n".getBytes(StandardCharsets.UTF_8));
            os.flush();
            len = is.read(buf);
            String round3Response = len > 0 ? new String(buf, 0, len, StandardCharsets.UTF_8).trim() : "";
            result.put("round3_sent", "LOGOUT");
            result.put("round3_received", round3Response);

            log.info("Multiround Socket call complete: intercepted={}", redirected);
        } catch (Exception e) {
            result.put("connected", false);
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            log.warn("Multiround Socket call failed: {}", e.getMessage());
        } finally {
            try { socket.close(); } catch (Exception ignored) {}
        }
        return result;
    }

    /**
     * BIO socket test with explicit charset for both request encoding and
     * response decoding. Used by the CH (multi-charset) full-chain test
     * section to verify that the server correctly decodes GBK/GB2312/Big5
     * request bytes (via {@code Rule.requestCharset}) and encodes the stub
     * response body using the configured {@code ResponseEntry.charset}.
     *
     * @param host    target host (will be redirected by agent to baafoo-server)
     * @param port    target port (will be redirected to 9001 TCP stub port)
     * @param message request payload text (will be encoded using {@code charset})
     * @param charset character set name (e.g. "GBK", "GB2312", "Big5", "UTF-8")
     */
    public Map<String, Object> testBioSocketWithCharset(String host, int port,
                                                        String message, String charset) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        Socket socket = new Socket();
        try {
            Charset cs = Charset.forName(charset);
            socket.setSoTimeout(5000);
            socket.connect(new InetSocketAddress(host, port), 5000);
            result.put("connected", true);
            result.put("charset", charset);
            boolean redirected = socket.getPort() != port
                    || !socket.getInetAddress().getHostAddress().equals(host);
            result.put("intercepted", redirected);
            String request = message + "\r\n";
            OutputStream os = socket.getOutputStream();
            os.write(request.getBytes(cs));
            os.flush();
            result.put("sent", message);
            InputStream is = socket.getInputStream();
            byte[] buf = new byte[4096];
            int len = is.read(buf);
            if (len > 0) {
                // Decode the response bytes using the same charset the rule's
                // ResponseEntry.charset was set to, so the test can compare
                // the rendered string directly.
                result.put("received", new String(buf, 0, len, cs).trim());
            }
            log.info("BIO Socket charset={} call complete: intercepted={}", charset, redirected);
        } catch (Exception e) {
            result.put("connected", false);
            result.put("error", e.getClass().getSimpleName() + ": " + e.getMessage());
            log.warn("BIO Socket charset={} call failed: {}", charset, e.getMessage());
        } finally {
            try { socket.close(); } catch (Exception ignored) {}
        }
        return result;
    }
}
