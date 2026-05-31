package com.baafoo.server.mapper;

import com.baafoo.server.mapper.entity.RecordingEntity;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface RecordingMapper {

    List<RecordingEntity> selectAll(@Param("limit") int limit);

    List<RecordingEntity> selectByRuleId(@Param("ruleId") String ruleId, @Param("limit") int limit);

    int insert(RecordingEntity recording);

    int deleteById(@Param("id") String id);

    int trimRecordings(@Param("keepCount") int keepCount);
}
