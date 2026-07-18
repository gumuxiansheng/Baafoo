package com.baafoo.agent.advice;

import com.baafoo.agent.BaafooAgent;
import com.baafoo.agent.channel.ControlChannel;
import com.baafoo.core.model.RecordingEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

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
 *
 * <p><b>IO-thread safety (C1 fix)</b>: {@code add()} may be called from the
 * Netty EventLoop via {@code SocketChannelReadAdvice/WriteAdvice} bridges.
 * To avoid blocking the EventLoop on HTTP upload, threshold-triggered flushes
 * are submitted to a dedicated single-thread {@code uploadExecutor} instead
 * of being executed inline. The periodic and shutdown flushes also run on
 * this executor.</p>
 *
 * <p><b>Bounded retry (C2 fix)</b>: {@code pendingRetry} is capped at
 * {@link #MAX_PENDING_RETRY} entries; on overflow the oldest entry is
 * dropped and counted in {@code droppedCount}.</p>
 */
public class RecordingBuffer {

    private static final Logger log = LoggerFactory.getLogger(RecordingBuffer.class);

    /** Upper bound on retained retry entries to prevent OOM under sustained upload failure. */
    private static final int MAX_PENDING_RETRY = 10000;

    private final ConcurrentLinkedQueue<RecordingEntry> buffer = new ConcurrentLinkedQueue<RecordingEntry>();
    private final int maxBufferSize;
    private final int flushIntervalSec;
    private final ScheduledExecutorService scheduler;
    private ScheduledFuture<?> flushTask;

    /** Entries that failed to upload and are pending retry (thread-safe, bounded). */
    private final ConcurrentLinkedQueue<RecordingEntry> pendingRetry = new ConcurrentLinkedQueue<RecordingEntry>();

    /** Count of entries dropped due to {@link #pendingRetry} overflow (reset after each flush). */
    private final AtomicLong droppedCount = new AtomicLong(0L);

    /**
     * Dedicated single-thread executor for HTTP upload work. Decouples upload
     * latency (5s connect + 5s read) from caller threads — especially the
     * Netty EventLoop, which must never block on network I/O.
     */
    private final ExecutorService uploadExecutor;

    /** L10: latch to make {@link #stop()} one-shot and {@link #start()} fail-fast after stop. */
    private volatile boolean stopped = false;

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
        this.uploadExecutor = Executors.newSingleThreadExecutor(new java.util.concurrent.ThreadFactory() {
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "baafoo-recording-uploader");
                t.setDaemon(true);
                return t;
            }
        });
    }

    /**
     * Add a recording entry to the buffer.
     * If the buffer is full, triggers an asynchronous flush on the upload
     * executor — never blocks the caller (which may be a Netty EventLoop).
     */
    public void add(RecordingEntry entry) {
        buffer.add(entry);
        // size() on ConcurrentLinkedQueue is O(n) but is only called once per
        // add — acceptable cost for a non-copying write path.
        if (buffer.size() >= maxBufferSize) {
            // C1: do NOT call flush() inline — caller may be a Netty EventLoop.
            // Submit to the dedicated uploader thread so the EventLoop is not
            // blocked on the 5s+5s HTTP upload.
            try {
                uploadExecutor.submit(this::flush);
            } catch (java.util.concurrent.RejectedExecutionException e) {
                // Executor was shut down (e.g., during agent shutdown) — drop the
                // flush trigger; the periodic/shutdown flush will pick up the data.
                log.debug("Recording flush trigger rejected (executor shutting down)");
            }
        }
    }

    /**
     * Drain the buffer atomically — uses poll() in a loop so concurrent
     * add() callers cannot observe a partial drain (entries added between
     * snapshot and clear under the previous COWArrayList design).
     */
    private List<RecordingEntry> drainBuffer() {
        List<RecordingEntry> snapshot = new ArrayList<RecordingEntry>();
        RecordingEntry e;
        while ((e = buffer.poll()) != null) {
            snapshot.add(e);
        }
        return snapshot;
    }

    /**
     * Start the periodic flush timer.
     *
     * @throws IllegalStateException if {@link #stop()} has already been called (L10).
     */
    public void start() {
        if (stopped) {
            throw new IllegalStateException("RecordingBuffer has been stopped and cannot be restarted");
        }
        if (flushTask == null) {
            flushTask = scheduler.scheduleAtFixedRate(this::flush,
                    flushIntervalSec, flushIntervalSec, TimeUnit.SECONDS);
            log.info("RecordingBuffer started (maxSize={}, flushInterval={}s)", maxBufferSize, flushIntervalSec);
        }
    }

    /**
     * Stop the periodic flush timer and flush any remaining entries.
     * One-shot: subsequent {@link #start()} calls will throw.
     */
    public void stop() {
        stopped = true;
        if (flushTask != null) {
            flushTask.cancel(false);
            flushTask = null;
        }
        // C1: stop accepting new async flushes, but drain the upload queue first
        // so any in-flight threshold-triggered flush completes before the final
        // synchronous flush below.
        uploadExecutor.shutdown();
        try {
            if (!uploadExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                uploadExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            uploadExecutor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        // Final synchronous flush of anything that remained in buffer/pendingRetry.
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
            // No channel available; keep for retry (subject to MAX_PENDING_RETRY)
            enqueueForRetry(batch);
            log.warn("ControlChannel not available, retaining {} entries for retry", batch.size());
            return;
        }

        try {
            channel.uploadRecordings(batch);
            log.debug("Flushed {} recording entries", batch.size());
        } catch (Exception ex) {
            // Upload failed; keep entries for retry (subject to MAX_PENDING_RETRY)
            enqueueForRetry(batch);
            log.warn("Recording upload failed, retaining {} entries for retry: {}", batch.size(), ex.getMessage());
        }
    }

    /**
     * Re-enqueue a batch of entries for retry, enforcing {@link #MAX_PENDING_RETRY}.
     * On overflow the oldest entries are dropped (FIFO eviction via poll()) and
     * counted in {@link #droppedCount}.
     */
    private void enqueueForRetry(List<RecordingEntry> batch) {
        for (RecordingEntry retryEntry : batch) {
            if (pendingRetry.size() >= MAX_PENDING_RETRY) {
                // C2: bounded — drop the oldest entry to make room.
                pendingRetry.poll();
                droppedCount.incrementAndGet();
            }
            pendingRetry.add(retryEntry);
        }
        if (droppedCount.get() > 0) {
            log.warn("Dropped {} recording entries due to pendingRetry overflow", droppedCount.getAndSet(0));
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
