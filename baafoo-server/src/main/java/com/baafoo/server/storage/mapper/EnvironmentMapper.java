package com.baafoo.server.storage.mapper;

import com.baafoo.core.model.Environment;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface EnvironmentMapper {

    List<Environment> listEnvironments();

    Environment getEnvironment(@Param("id") String id);

    Environment getEnvironmentByName(@Param("name") String name);

    int createEnvironment(Environment env);

    int updateEnvironment(Environment env);

    int deleteEnvironment(@Param("id") String id);
}
