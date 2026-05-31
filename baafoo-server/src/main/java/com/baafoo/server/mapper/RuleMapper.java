package com.baafoo.server.mapper;

import com.baafoo.server.mapper.entity.RuleEntity;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface RuleMapper {

    List<RuleEntity> selectAll();

    RuleEntity selectById(@Param("id") String id);

    int insert(RuleEntity rule);

    int update(RuleEntity rule);

    int deleteById(@Param("id") String id);

    int insertHistory(@Param("ruleId") String ruleId, @Param("snapshot") String snapshot, @Param("createdAt") long createdAt);

    String selectLatestHistory(@Param("ruleId") String ruleId);

    int deleteOldHistory(@Param("ruleId") String ruleId, @Param("keepCount") int keepCount);

    int deleteHistoryByRuleId(@Param("ruleId") String ruleId);
}
