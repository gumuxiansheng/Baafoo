package com.baafoo.server.mapper;

import com.baafoo.server.mapper.entity.UserEntity;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface UserMapper {

    List<UserEntity> selectAll();

    UserEntity selectByUsername(@Param("username") String username);

    UserEntity selectByApiKey(@Param("apiKey") String apiKey);

    int insert(UserEntity user);

    int updateRole(@Param("username") String username, @Param("role") String role);

    int updateApiKey(@Param("username") String username, @Param("apiKey") String apiKey);

    int updateLastLogin(@Param("username") String username, @Param("lastLoginAt") long lastLoginAt);

    int deleteByUsername(@Param("username") String username);
}
