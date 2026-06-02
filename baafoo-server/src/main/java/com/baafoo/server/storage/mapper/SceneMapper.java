package com.baafoo.server.storage.mapper;

import com.baafoo.core.model.SceneSet;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface SceneMapper {

    List<SceneSet> listScenes();

    SceneSet getScene(@Param("id") String id);

    int createScene(SceneSet scene);

    int updateScene(SceneSet scene);

    int deleteScene(@Param("id") String id);
}
