package com.baafoo.server.storage.mapper;

import com.baafoo.core.model.User;
import org.apache.ibatis.annotations.Param;

import java.util.List;

public interface UserMapper {

    List<User> listUsers();

    User getUserByUsername(@Param("username") String username);

    User getUserByApiKey(@Param("apiKey") String apiKey);

    /**
     * M-9: look up the sys_role primary key by role code. Used by
     * {@link JdbcUserService#createUser} to validate the role before insert,
     * so a typo'd role code surfaces as a 400 instead of silently writing
     * a NULL role_id (the {@code createUser} SQL uses a subquery that
     * returns NULL for unknown codes).
     */
    Long findRoleIdByCode(@Param("code") String code);

    int createUser(User user);

    int updateUserRole(@Param("username") String username, @Param("role") String role);

    int updateUserApiKey(@Param("username") String username, @Param("apiKey") String apiKey);

    int updateUserPassword(@Param("username") String username, @Param("password") String password);

    int updateUserProfile(@Param("username") String username,
                         @Param("displayName") String displayName,
                         @Param("email") String email,
                         @Param("phone") String phone);

    int updateUserLastLogin(@Param("username") String username);

    int deleteUser(@Param("username") String username);
}
