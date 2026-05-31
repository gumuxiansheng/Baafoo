package com.baafoo.server.mapper;

import com.baafoo.server.mapper.entity.AgentEntity;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface AgentMapper {

    List<AgentEntity> selectAll();

    List<AgentEntity> selectByEnvironment(@Param("environment") String environment);

    int merge(AgentEntity agent);

    int updateHeartbeat(@Param("agentId") String agentId, @Param("heartbeatTime") long heartbeatTime);

    int deleteById(@Param("agentId") String agentId);
}
