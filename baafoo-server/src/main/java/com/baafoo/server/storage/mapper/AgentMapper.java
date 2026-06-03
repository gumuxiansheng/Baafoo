package com.baafoo.server.storage.mapper;

import com.baafoo.server.storage.StorageService.AgentRegistration;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface AgentMapper {

    int upsertAgent(AgentRegistration registration);

    int updateHeartbeat(@Param("agentId") String agentId, @Param("lastHeartbeat") long lastHeartbeat, @Param("agentIp") String agentIp);

    List<AgentRegistration> listAgents();

    List<AgentRegistration> getAgentsForEnvironment(@Param("environment") String environment);
}
