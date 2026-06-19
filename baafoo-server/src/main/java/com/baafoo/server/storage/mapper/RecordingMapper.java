package com.baafoo.server.storage.mapper;

import com.baafoo.core.model.RecordingEntry;
import org.apache.ibatis.annotations.Param;

import java.util.List;

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

    int deleteRecordingsOlderThan(@Param("cutoffTime") long cutoffTime);

    long countAllRecordings();

    /** Sum of LENGTH(response_body) + LENGTH(request_body) across all recordings. */
    long sumAllRecordingBodyBytes();

    List<RecordingEntry> listOldestRecordings(@Param("limit") int limit);
}
