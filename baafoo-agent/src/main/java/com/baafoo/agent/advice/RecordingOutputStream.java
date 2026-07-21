package com.baafoo.agent.advice;

import com.baafoo.agent.GlobalRouteState;
import com.baafoo.core.model.RecordingEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;

/**
 * OutputStream wrapper that records all bytes written during record mode.
 *
 * <p>Each write operation captures the bytes into a {@link RecordingEntry}
 * and adds it to the {@link RecordingBuffer}. This wrapper is applied to
 * a Socket's OutputStream when the agent is in record or record-and-stub mode.</p>
 *
 * <p>Note: We override {@code write(byte[], int, int)} to capture batch writes
 * efficiently, and delegate to the underlying stream directly (not via super)
 * to avoid double-recording through {@code write(int)}.</p>
 */
public class RecordingOutputStream extends FilterOutputStream {

    private static final Logger log = LoggerFactory.getLogger(RecordingOutputStream.class);

    private final String sessionId;
    private final String host;
    private final int port;
    private final RecordingBuffer recordingBuffer;

    public RecordingOutputStream(OutputStream out, String sessionId, String host, int port,
                                 RecordingBuffer recordingBuffer) {
        super(out);
        this.sessionId = sessionId;
        this.host = host;
        this.port = port;
        this.recordingBuffer = recordingBuffer;
    }

    @Override
    public void write(int b) throws IOException {
        out.write(b);
        if (recordingBuffer != null) {
            recordBytes(new byte[]{(byte) b}, 0, 1);
        }
    }

    @Override
    public void write(byte[] b) throws IOException {
        write(b, 0, b.length);
    }

    @Override
    public void write(byte[] b, int off, int len) throws IOException {
        out.write(b, off, len);
        if (recordingBuffer != null) {
            recordBytes(b, off, len);
        }
    }

    private void recordBytes(byte[] data, int offset, int length) {
        // H5: recording must never propagate exceptions to the application's
        // write() call — a failure inside the recording pipeline (e.g. OOM,
        // NPE in inferProtocol, RecordingBuffer rejection) would otherwise
        // break the application's IO. Swallow everything and log at debug.
        try {
            RecordingEntry entry = new RecordingEntry();
            entry.setSessionId(sessionId);
            String inferredProtocol = GlobalRouteState.inferProtocol(host, port);
            entry.setProtocol(inferredProtocol);
            entry.setDirection("request");
            entry.setHost(host);
            entry.setPort(port);
            // Borrow path for "host:port" on TCP/UDP streams — see BaafooAgent.NIO_RECORDING_HANDLER.
            if (inferredProtocol == null || inferredProtocol.isEmpty()
                    || "tcp".equalsIgnoreCase(inferredProtocol)
                    || "udp".equalsIgnoreCase(inferredProtocol)) {
                entry.setPath(host + ":" + port);
            }
            entry.setDataHex(GlobalRouteState.bytesToHex(data, offset, length));
            entry.setRecordedAt(System.currentTimeMillis());
            recordingBuffer.add(entry);
        } catch (Throwable t) {
            if (log.isDebugEnabled()) {
                log.debug("RecordingOutputStream recording failed, swallowed", t);
            }
        }
    }
}
