package com.baafoo.server.storage.mapper;

import com.baafoo.core.model.RecordingEntry;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface RecordingMapper {

    List<RecordingEntry> listRecordings(@Param("ruleId") String ruleId, @Param("limit") int limit);

    long countRecordings(@Param("ruleId") String ruleId);

    List<RecordingEntry> listRecordingsPaged(@Param("ruleId") String ruleId,
                                             @Param("limit") int limit,
                                             @Param("offset") int offset);

    int insertRecording(RecordingEntry recording);

    int trimRecordings(@Param("keepCount") int keepCount);

    int deleteRecording(@Param("id") String id);
}
