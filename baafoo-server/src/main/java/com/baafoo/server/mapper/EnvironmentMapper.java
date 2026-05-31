package com.baafoo.server.mapper;

import com.baafoo.server.mapper.entity.EnvironmentEntity;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface EnvironmentMapper {

    List<EnvironmentEntity> selectAll();

    EnvironmentEntity selectById(@Param("id") String id);

    EnvironmentEntity selectByName(@Param("name") String name);

    int insert(EnvironmentEntity env);

    int update(EnvironmentEntity env);

    int deleteById(@Param("id") String id);
}
