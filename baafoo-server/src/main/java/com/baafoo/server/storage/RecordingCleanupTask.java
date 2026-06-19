package com.baafoo.server.storage;

import com.baafoo.core.config.ServerConfig;
import com.baafoo.core.model.RecordingEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Periodic task that cleans up old recordings based on retention policy.
 *
 * <p>Two cleanup strategies:
 * <ul>
 *   <li>Time-based: Delete recordings older than {@code recordingRetentionDays}</li>
 *   <li>Size-based: If total size exceeds {@code recordingMaxSizeMb}, delete oldest recordings</li>
 * </ul></p>
 *
 * <p>Runs every hour by default.</p>
 */
public class RecordingCleanupTask {

    private static final Logger log = LoggerFactory.getLogger(RecordingCleanupTask.class);
    private static final long CLEANUP_INTERVAL_MINUTES = 60;

    private final StorageService storage;
    private final ServerConfig config;
    private final ScheduledExecutorService scheduler;

    public RecordingCleanupTask(StorageService storage, ServerConfig config) {
        this.storage = storage;
        this.config = config;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(new java.util.concurrent.ThreadFactory() {
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "baafoo-recording-cleanup");
                t.setDaemon(true);
                return t;
            }
        });
    }

    /**
     * Start the periodic cleanup task.
     */
    public void start() {
        scheduler.scheduleAtFixedRate(new Runnable() {
            @Override
            public void run() {
                try {
                    cleanup();
                } catch (Exception e) {
                    log.error("Recording cleanup task failed: {}", e.getMessage(), e);
                }
            }
        }, CLEANUP_INTERVAL_MINUTES, CLEANUP_INTERVAL_MINUTES, TimeUnit.MINUTES);
        log.info("Recording cleanup task scheduled (every {} min, retention={} days, maxSize={} MB)",
                CLEANUP_INTERVAL_MINUTES, config.getRecordingRetentionDays(), config.getRecordingMaxSizeMb());
    }

    /**
     * Stop the cleanup task.
     */
    public void stop() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
        }
        log.info("Recording cleanup task stopped");
    }

    /**
     * Execute one cleanup cycle.
     */
    void cleanup() {
        int retentionDays = config.getRecordingRetentionDays();
        int maxSizeMb = config.getRecordingMaxSizeMb();

        // 1. Delete recordings older than retention days
        int deletedByAge = storage.deleteRecordingsOlderThan(retentionDays);
        if (deletedByAge > 0) {
            log.info("Recording cleanup: deleted {} recordings older than {} days", deletedByAge, retentionDays);
        }

        // 2. Check total size and delete oldest if over limit
        long maxSizeBytes = (long) maxSizeMb * 1024 * 1024;
        long totalSizeBytes = storage.getRecordingTotalSizeBytes();
        if (totalSizeBytes > maxSizeBytes) {
            long excessBytes = totalSizeBytes - maxSizeBytes;
            // Estimate how many recordings to delete based on average body size.
            // Falls back to 2KB if count is zero (avoids division by zero).
            long count = storage.getRecordingCount();
            long avgBytes = count > 0 ? totalSizeBytes / count : 2048;
            if (avgBytes < 1) avgBytes = 1;
            long estimatedDeleteCount = (excessBytes / avgBytes) + 100; // +100 buffer
            int deletedBySize = deleteOldestRecordings((int) Math.min(estimatedDeleteCount, 10000));
            if (deletedBySize > 0) {
                log.info("Recording cleanup: deleted {} oldest recordings to reduce size below {} MB (was ~{} MB)",
                        deletedBySize, maxSizeMb, totalSizeBytes / (1024 * 1024));
            }
        }

        long remainingCount = storage.getRecordingCount();
        log.info("Recording cleanup completed: {} recordings remaining", remainingCount);
    }

    private int deleteOldestRecordings(int count) {
        List<RecordingEntry> oldest = storage.listRecordings(null, count);
        int deleted = 0;
        for (RecordingEntry r : oldest) {
            if (storage.deleteRecording(r.getId())) {
                deleted++;
            }
        }
        return deleted;
    }
}
