package com.baafoo.agent.advice;

import com.baafoo.core.model.RecordingEntry;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.Assert.*;

public class RecordingStreamTest {

    /**
     * Simple recording buffer implementation for testing that captures entries in a list.
     * Uses a delegate RecordingBuffer but intercepts add() calls for verification.
     */
    private static class TestRecordingBuffer extends RecordingBuffer {
        final List<RecordingEntry> entries = new CopyOnWriteArrayList<RecordingEntry>();

        public TestRecordingBuffer() {
            super(1000, 3600);
        }

        @Override
        public void add(RecordingEntry entry) {
            entries.add(entry);
            // Don't delegate to super to avoid needing ControlChannel
        }
    }

    @Test
    public void testRecordingInputStreamSingleByte() throws Exception {
        TestRecordingBuffer buffer = new TestRecordingBuffer();
        byte[] data = "HELLO".getBytes("UTF-8");
        InputStream in = new ByteArrayInputStream(data);
        InputStream recordingIn = new RecordingInputStream(in, "session-1", "example.com", 80, buffer);

        // Read single bytes
        assertEquals('H', recordingIn.read());
        assertEquals('E', recordingIn.read());

        // Should have 2 recorded entries
        assertEquals(2, buffer.entries.size());
        assertEquals("response", buffer.entries.get(0).getDirection());
        assertEquals("session-1", buffer.entries.get(0).getSessionId());
        assertEquals("example.com", buffer.entries.get(0).getHost());
        assertEquals(80, buffer.entries.get(0).getPort());
        assertEquals("tcp", buffer.entries.get(0).getProtocol());
        assertEquals("48", buffer.entries.get(0).getDataHex()); // 'H' = 0x48
        assertEquals("45", buffer.entries.get(1).getDataHex()); // 'E' = 0x45
    }

    @Test
    public void testRecordingInputStreamByteArray() throws Exception {
        TestRecordingBuffer buffer = new TestRecordingBuffer();
        byte[] data = "HELLO".getBytes("UTF-8");
        InputStream in = new ByteArrayInputStream(data);
        InputStream recordingIn = new RecordingInputStream(in, "session-2", "test.com", 443, buffer);

        byte[] buf = new byte[5];
        int n = recordingIn.read(buf);

        assertEquals(5, n);
        assertEquals(1, buffer.entries.size());
        assertEquals("response", buffer.entries.get(0).getDirection());
        assertEquals("session-2", buffer.entries.get(0).getSessionId());
        assertEquals("48454c4c4f", buffer.entries.get(0).getDataHex());
    }

    @Test
    public void testRecordingInputStreamPartialRead() throws Exception {
        TestRecordingBuffer buffer = new TestRecordingBuffer();
        byte[] data = "HELLO WORLD".getBytes("UTF-8");
        InputStream in = new ByteArrayInputStream(data);
        InputStream recordingIn = new RecordingInputStream(in, "session-3", "test.com", 80, buffer);

        byte[] buf = new byte[5];
        int n = recordingIn.read(buf, 0, 5);

        assertEquals(5, n);
        assertEquals(1, buffer.entries.size());
        assertEquals("48454c4c4f", buffer.entries.get(0).getDataHex()); // "HELLO"
    }

    @Test
    public void testRecordingInputStreamEndOfStream() throws Exception {
        TestRecordingBuffer buffer = new TestRecordingBuffer();
        byte[] data = new byte[0];
        InputStream in = new ByteArrayInputStream(data);
        InputStream recordingIn = new RecordingInputStream(in, "session-4", "test.com", 80, buffer);

        int b = recordingIn.read();
        assertEquals(-1, b);
        assertEquals(0, buffer.entries.size()); // -1 should not be recorded
    }

    @Test
    public void testRecordingOutputStreamSingleByte() throws Exception {
        TestRecordingBuffer buffer = new TestRecordingBuffer();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        OutputStream recordingOut = new RecordingOutputStream(baos, "session-5", "example.com", 80, buffer);

        recordingOut.write('A');
        recordingOut.write('B');

        assertEquals(2, buffer.entries.size());
        assertEquals("request", buffer.entries.get(0).getDirection());
        assertEquals("session-5", buffer.entries.get(0).getSessionId());
        assertEquals("41", buffer.entries.get(0).getDataHex()); // 'A' = 0x41
        assertEquals("42", buffer.entries.get(1).getDataHex()); // 'B' = 0x42
    }

    @Test
    public void testRecordingOutputStreamByteArray() throws Exception {
        TestRecordingBuffer buffer = new TestRecordingBuffer();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        OutputStream recordingOut = new RecordingOutputStream(baos, "session-6", "test.com", 443, buffer);

        recordingOut.write("HELLO".getBytes("UTF-8"));

        assertEquals(1, buffer.entries.size());
        assertEquals("request", buffer.entries.get(0).getDirection());
        assertEquals("session-6", buffer.entries.get(0).getSessionId());
        assertEquals("48454c4c4f", buffer.entries.get(0).getDataHex());
    }

    @Test
    public void testRecordingOutputStreamPartialWrite() throws Exception {
        TestRecordingBuffer buffer = new TestRecordingBuffer();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        OutputStream recordingOut = new RecordingOutputStream(baos, "session-7", "test.com", 80, buffer);

        byte[] data = "HELLO WORLD".getBytes("UTF-8");
        recordingOut.write(data, 0, 5);

        assertEquals(1, buffer.entries.size());
        assertEquals("48454c4c4f", buffer.entries.get(0).getDataHex()); // "HELLO"
    }

    @Test
    public void testRecordingNullBuffer() throws Exception {
        // When buffer is null, streams should work without recording
        InputStream in = new ByteArrayInputStream("HELLO".getBytes("UTF-8"));
        InputStream recordingIn = new RecordingInputStream(in, "session-8", "test.com", 80, null);

        byte[] buf = new byte[5];
        int n = recordingIn.read(buf);
        assertEquals(5, n);
        assertEquals("HELLO", new String(buf, "UTF-8"));

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        OutputStream recordingOut = new RecordingOutputStream(baos, "session-8", "test.com", 80, null);
        recordingOut.write("WORLD".getBytes("UTF-8"));
        assertEquals("WORLD", baos.toString("UTF-8"));
    }

    @Test
    public void testHexEncoding() throws Exception {
        TestRecordingBuffer buffer = new TestRecordingBuffer();
        byte[] data = new byte[]{0x00, 0x01, (byte) 0xFF, (byte) 0xAB};
        InputStream in = new ByteArrayInputStream(data);
        InputStream recordingIn = new RecordingInputStream(in, "session-9", "test.com", 80, buffer);

        byte[] buf = new byte[4];
        recordingIn.read(buf);

        assertEquals(1, buffer.entries.size());
        assertEquals("0001ffab", buffer.entries.get(0).getDataHex());
    }
}
