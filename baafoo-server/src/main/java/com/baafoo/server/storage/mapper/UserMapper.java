package com.baafoo.server.storage.mapper;

import com.baafoo.core.model.User;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface UserMapper {

    List<User> listUsers();

    User getUserByUsername(@Param("username") String username);

    User getUserByApiKey(@Param("apiKey") String apiKey);

    int createUser(User user);

    int updateUserRole(@Param("username") String username, @Param("role") String role);

    int updateUserApiKey(@Param("username") String username, @Param("apiKey") String apiKey);

    int updateUserPassword(@Param("username") String username, @Param("password") String password);

    int updateUserLastLogin(@Param("username") String username);

    int deleteUser(@Param("username") String username);
}
