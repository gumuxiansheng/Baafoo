package com.baafoo.agent.advice;

import com.baafoo.core.model.RecordingEntry;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * InputStream wrapper that records all bytes read during record mode.
 *
 * <p>Each read operation captures the bytes into a {@link RecordingEntry}
 * and adds it to the {@link RecordingBuffer}. This wrapper is applied to
 * a Socket's InputStream when the agent is in record or record-and-stub mode.</p>
 *
 * <p>Note: We override {@code read(byte[], int, int)} to capture batch reads
 * efficiently, and delegate to the underlying stream directly (not via super)
 * to avoid double-recording through {@code read()}.</p>
 */
public class RecordingInputStream extends FilterInputStream {

    private final String sessionId;
    private final String host;
    private final int port;
    private final RecordingBuffer recordingBuffer;

    public RecordingInputStream(InputStream in, String sessionId, String host, int port,
                                RecordingBuffer recordingBuffer) {
        super(in);
        this.sessionId = sessionId;
        this.host = host;
        this.port = port;
        this.recordingBuffer = recordingBuffer;
    }

    @Override
    public int read() throws IOException {
        int b = in.read();
        if (b != -1 && recordingBuffer != null) {
            recordBytes(new byte[]{(byte) b}, 0, 1);
        }
        return b;
    }

    @Override
    public int read(byte[] b) throws IOException {
        return read(b, 0, b.length);
    }

    @Override
    public int read(byte[] b, int off, int len) throws IOException {
        int n = in.read(b, off, len);
        if (n > 0 && recordingBuffer != null) {
            recordBytes(b, off, n);
        }
        return n;
    }

    private void recordBytes(byte[] data, int offset, int length) {
        RecordingEntry entry = new RecordingEntry();
        entry.setSessionId(sessionId);
        entry.setProtocol(inferProtocol(host, port));
        entry.setDirection("response");
        entry.setHost(host);
        entry.setPort(port);
        entry.setDataHex(bytesToHex(data, offset, length));
        entry.setRecordedAt(System.currentTimeMillis());
        recordingBuffer.add(entry);
    }

    private static String inferProtocol(String host, int port) {
        if (com.baafoo.agent.GlobalRouteState.isInternal(host, port)) {
            if (port == com.baafoo.agent.GlobalRouteState.HTTP_PORT) return "http";
            if (port == com.baafoo.agent.GlobalRouteState.TCP_PORT) return "tcp";
            if (port == com.baafoo.agent.GlobalRouteState.KAFKA_PORT) return "kafka";
            if (port == com.baafoo.agent.GlobalRouteState.PULSAR_PORT) return "pulsar";
            if (port == com.baafoo.agent.GlobalRouteState.JMS_PORT) return "jms";
        }
        String[] route = com.baafoo.agent.GlobalRouteState.lookup(host, port);
        if (route != null) {
            int targetPort = Integer.parseInt(route[1]);
            if (targetPort == com.baafoo.agent.GlobalRouteState.HTTP_PORT) return "http";
            if (targetPort == com.baafoo.agent.GlobalRouteState.TCP_PORT) return "tcp";
            if (targetPort == com.baafoo.agent.GlobalRouteState.KAFKA_PORT) return "kafka";
            if (targetPort == com.baafoo.agent.GlobalRouteState.PULSAR_PORT) return "pulsar";
            if (targetPort == com.baafoo.agent.GlobalRouteState.JMS_PORT) return "jms";
        }
        return "tcp";
    }

    private static String bytesToHex(byte[] bytes, int offset, int length) {
        StringBuilder sb = new StringBuilder(length * 2);
        int end = offset + length;
        for (int i = offset; i < end; i++) {
            sb.append(String.format("%02x", bytes[i] & 0xff));
        }
        return sb.toString();
    }
}
