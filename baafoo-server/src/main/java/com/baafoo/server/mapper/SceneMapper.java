package com.baafoo.server.mapper;

import com.baafoo.server.mapper.entity.SceneEntity;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface SceneMapper {

    List<SceneEntity> selectAll();

    SceneEntity selectById(@Param("id") String id);

    int insert(SceneEntity scene);

    int update(SceneEntity scene);

    int deleteById(@Param("id") String id);
}
