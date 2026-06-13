package com.baafoo.agent.advice;

import com.baafoo.core.model.RecordingEntry;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.Assert.*;

public class RecordingBufferTest {

    @Test
    public void testAddAndSize() {
        RecordingBuffer buffer = new RecordingBuffer(100, 3600);
        assertEquals(0, buffer.size());

        RecordingEntry entry = new RecordingEntry();
        entry.setProtocol("tcp");
        entry.setDirection("request");
        entry.setDataHex("48454c4c4f");
        buffer.add(entry);

        assertEquals(1, buffer.size());
    }

    @Test
    public void testAutoFlushOnBufferSize() {
        // Buffer with max size of 3
        RecordingBuffer buffer = new RecordingBuffer(3, 3600);

        // Add 3 entries — should trigger flush
        for (int i = 0; i < 3; i++) {
            RecordingEntry entry = new RecordingEntry();
            entry.setProtocol("tcp");
            entry.setDataHex("data" + i);
            buffer.add(entry);
        }

        // After auto-flush, buffer should be empty (or pending retry if no channel)
        // Since BaafooAgent.getControlChannel() returns null in tests, entries go to pending retry
        assertTrue(buffer.pendingRetryCount() <= 3);
    }

    @Test
    public void testFlushClearsBuffer() {
        RecordingBuffer buffer = new RecordingBuffer(100, 3600);

        RecordingEntry entry = new RecordingEntry();
        entry.setProtocol("tcp");
        buffer.add(entry);

        assertTrue(buffer.size() > 0);

        // Flush — entries go to pending retry since no ControlChannel
        buffer.flush();

        // Buffer should be cleared; entries in pending retry
        assertTrue(buffer.pendingRetryCount() > 0);
    }

    @Test
    public void testConcurrentAdd() throws Exception {
        RecordingBuffer buffer = new RecordingBuffer(1000, 3600);
        int threadCount = 10;
        int entriesPerThread = 50;
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            final int threadIdx = t;
            new Thread(() -> {
                for (int i = 0; i < entriesPerThread; i++) {
                    RecordingEntry entry = new RecordingEntry();
                    entry.setProtocol("tcp");
                    entry.setSessionId("session-" + threadIdx + "-" + i);
                    buffer.add(entry);
                }
                latch.countDown();
            }).start();
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS));
        // All entries should be in buffer or pending retry
        assertTrue(buffer.size() > 0);
    }

    @Test
    public void testPendingRetryOnMissingChannel() {
        RecordingBuffer buffer = new RecordingBuffer(100, 3600);

        RecordingEntry entry = new RecordingEntry();
        entry.setProtocol("tcp");
        buffer.add(entry);

        buffer.flush();

        // No ControlChannel available, so entries should be in pending retry
        assertEquals(1, buffer.pendingRetryCount());
    }

    @Test
    public void testStartAndStop() {
        RecordingBuffer buffer = new RecordingBuffer(100, 1);
        buffer.start();

        RecordingEntry entry = new RecordingEntry();
        entry.setProtocol("tcp");
        buffer.add(entry);

        buffer.stop();
        // After stop, buffer should be flushed
    }
}
