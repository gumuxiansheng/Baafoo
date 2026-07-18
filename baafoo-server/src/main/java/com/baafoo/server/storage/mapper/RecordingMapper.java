package com.baafoo.server.storage.mapper;

import com.baafoo.core.model.RecordingEntry;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

public interface RecordingMapper {

    List<RecordingEntry> listRecordings(@Param("ruleId") String ruleId, @Param("limit") int limit);

    long countRecordings(@Param("ruleId") String ruleId,
                         @Param("agentId") String agentId,
                         @Param("agentIp") String agentIp,
                         @Param("protocol") String protocol,
                         @Param("method") String method,
                         @Param("path") String path,
                         @Param("statusCode") Integer statusCode,
                         @Param("keyword") String keyword);

    List<RecordingEntry> listRecordingsPaged(@Param("ruleId") String ruleId,
                                             @Param("agentId") String agentId,
                                             @Param("agentIp") String agentIp,
                                             @Param("protocol") String protocol,
                                             @Param("method") String method,
                                             @Param("path") String path,
                                             @Param("statusCode") Integer statusCode,
                                             @Param("keyword") String keyword,
                                             @Param("limit") int limit,
                                             @Param("offset") int offset);

    int insertRecording(RecordingEntry recording);

    int trimRecordings(@Param("keepCount") int keepCount);

    int deleteRecording(@Param("id") String id);

    /**
     * L-2: bulk-delete the {@code limit} oldest recordings in a single SQL
     * statement. Replaces the previous N+1 pattern in
     * {@link com.baafoo.server.storage.RecordingCleanupTask#deleteOldestRecordings}
     * (list N rows, then loop deleteRecording per id). Returns the number of
     * rows deleted.
     */
    int deleteOldestN(@Param("limit") int limit);

    int deleteRecordingsOlderThan(@Param("cutoffTime") long cutoffTime);

    long countAllRecordings();

    /**
     * Sum of OCTET_LENGTH(response_body) + OCTET_LENGTH(request_body) across
     * all recordings. L-3: switched from LENGTH (character count) to
     * OCTET_LENGTH (byte count) so multi-byte content (GBK/CJK) is accounted
     * correctly when comparing against recordingMaxSizeMb.
     */
    long sumAllRecordingBodyBytes();

    List<RecordingEntry> listOldestRecordings(@Param("limit") int limit);

    List<Map<String, Object>> countRecordingsByDay(@Param("startTime") long startTime);
}
