package com.baafoo.server.storage;

import com.baafoo.core.api.PaginatedResult;
import com.baafoo.core.model.RecordingEntry;

import java.util.List;
import java.util.Map;

/**
 * Recording 聚合根的存储接口。
 *
 * <p>包含录制记录的增删查、批量写入、保留期清理、统计聚合等操作。</p>
 */
public interface RecordingService {

    List<RecordingEntry> listRecordings(String ruleId, int limit);

    PaginatedResult<RecordingEntry> listRecordingsPaged(String ruleId, String agentId, String agentIp,
                                                         String protocol, String method, String path,
                                                         Integer statusCode, String keyword,
                                                         int page, int size);

    void addRecording(RecordingEntry recording);

    void addRecordings(List<RecordingEntry> batch);

    boolean deleteRecording(String id);

    /**
     * L-2: bulk-delete the {@code limit} oldest recordings in a single SQL
     * statement. Used by {@link RecordingCleanupTask} for size-based cleanup
     * to avoid the previous N+1 list-then-loop-delete pattern.
     *
     * @param limit max number of oldest recordings to delete
     * @return number of recordings actually deleted
     */
    int deleteOldestN(int limit);

    /**
     * Delete recordings older than the specified number of days.
     *
     * @param retentionDays number of days to retain
     * @return number of recordings deleted
     */
    int deleteRecordingsOlderThan(int retentionDays);

    /**
     * Get the total count of recordings.
     *
     * @return total recording count
     */
    long getRecordingCount();

    /**
     * Get the total size of recordings in bytes (estimated).
     *
     * @return estimated total size in bytes
     */
    long getRecordingTotalSizeBytes();

    /**
     * Get recording counts grouped by day since the given start time.
     *
     * @param startTime start time in milliseconds
     * @return list of maps with "day" (epoch millis) and "count" keys
     */
    List<Map<String, Object>> getRecordingCountsByDay(long startTime);
}
