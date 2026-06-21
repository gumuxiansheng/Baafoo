package com.baafoo.agent.advice;

import com.baafoo.agent.BaafooAgent;
import com.baafoo.agent.channel.ControlChannel;
import com.baafoo.core.model.RecordingEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Thread-safe buffer for recording entries captured during record mode.
 *
 * <p>Entries accumulate in memory and are flushed to the Baafoo Server
 * via {@link ControlChannel#uploadRecordings(List)} when:
 * <ul>
 *   <li>The buffer reaches {@code maxBufferSize} entries</li>
 *   <li>The periodic flush timer fires (every {@code flushIntervalSec} seconds)</li>
 *   <li>{@link #flush()} is called explicitly (e.g., during agent shutdown)</li>
 * </ul></p>
 *
 * <p>If the upload fails, entries are retained in a pending list and retried
 * on the next flush cycle (AC-05: local retention with retry).</p>
 */
public class RecordingBuffer {

    private static final Logger log = LoggerFactory.getLogger(RecordingBuffer.class);

    private final CopyOnWriteArrayList<RecordingEntry> buffer = new CopyOnWriteArrayList<RecordingEntry>();
    private final int maxBufferSize;
    private final int flushIntervalSec;
    private final ScheduledExecutorService scheduler;
    private ScheduledFuture<?> flushTask;

    /** Entries that failed to upload and are pending retry (thread-safe) */
    private final ConcurrentLinkedQueue<RecordingEntry> pendingRetry = new ConcurrentLinkedQueue<RecordingEntry>();

    public RecordingBuffer(int maxBufferSize, int flushIntervalSec) {
        this.maxBufferSize = maxBufferSize > 0 ? maxBufferSize : 100;
        this.flushIntervalSec = flushIntervalSec > 0 ? flushIntervalSec : 30;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(new java.util.concurrent.ThreadFactory() {
            private int count = 0;
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "baafoo-recording-flush-" + (++count));
                t.setDaemon(true);
                return t;
            }
        });
    }

    /**
     * Add a recording entry to the buffer.
     * If the buffer is full, triggers an immediate flush.
     */
    public void add(RecordingEntry entry) {
        buffer.add(entry);
        if (buffer.size() >= maxBufferSize) {
            flush();
        }
    }

    /**
     * Drain the buffer atomically: snapshot and clear under synchronization
     * so no entries are lost between the two operations.
     */
    private List<RecordingEntry> drainBuffer() {
        List<RecordingEntry> snapshot;
        synchronized (this) {
            snapshot = new ArrayList<RecordingEntry>(buffer);
            buffer.clear();
        }
        return snapshot;
    }

    /**
     * Start the periodic flush timer.
     */
    public void start() {
        if (flushTask == null) {
            flushTask = scheduler.scheduleAtFixedRate(this::flush,
                    flushIntervalSec, flushIntervalSec, TimeUnit.SECONDS);
            log.info("RecordingBuffer started (maxSize={}, flushInterval={}s)", maxBufferSize, flushIntervalSec);
        }
    }

    /**
     * Stop the periodic flush timer and flush any remaining entries.
     */
    public void stop() {
        if (flushTask != null) {
            flushTask.cancel(false);
            flushTask = null;
        }
        flush();
        scheduler.shutdown();
    }

    /**
     * Flush buffered entries to the server.
     * On upload failure, entries are retained for retry on the next flush.
     * Thread-safe: atomically drain both pendingRetry (ConcurrentLinkedQueue poll)
     * and buffer (synchronized snapshot+clear) into a single batch.
     */
    public void flush() {
        List<RecordingEntry> batch = new ArrayList<RecordingEntry>();

        // Drain pendingRetry (CAS via ConcurrentLinkedQueue poll — fine for concurrent flush callers)
        RecordingEntry entry;
        while ((entry = pendingRetry.poll()) != null) {
            batch.add(entry);
        }
        // Drain buffer under synchronization — snapshot+clear is now atomic
        batch.addAll(drainBuffer());

        if (batch.isEmpty()) {
            return;
        }

        ControlChannel channel = BaafooAgent.getControlChannel();
        if (channel == null) {
            // No channel available; keep for retry
            for (RecordingEntry e : batch) {
                pendingRetry.add(e);
            }
            log.warn("ControlChannel not available, retaining {} entries for retry", batch.size());
            return;
        }

        try {
            channel.uploadRecordings(batch);
            log.debug("Flushed {} recording entries", batch.size());
        } catch (Exception ex) {
            // Upload failed; keep entries for retry
            for (RecordingEntry retryEntry : batch) {
                pendingRetry.add(retryEntry);
            }
            log.warn("Recording upload failed, retaining {} entries for retry: {}", batch.size(), ex.getMessage());
        }
    }

    /**
     * Get the current buffer size (for testing/monitoring).
     */
    public int size() {
        return buffer.size() + pendingRetry.size();
    }

    /**
     * Get the number of entries pending retry (for testing/monitoring).
     */
    public int pendingRetryCount() {
        return pendingRetry.size();
    }
}
