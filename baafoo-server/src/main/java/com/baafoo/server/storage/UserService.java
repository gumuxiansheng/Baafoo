package com.baafoo.server.storage;

import com.baafoo.core.model.User;

import java.util.List;

/**
 * User 聚合根的存储接口。
 *
 * <p>包含用户 CRUD、角色/密码/API Key 更新等操作。</p>
 */
public interface UserService {

    List<User> listUsers();

    User getUserByUsername(String username);

    User getUserByApiKey(String apiKey);

    User createUser(User user);

    boolean updateUserRole(String username, String role);

    boolean updateUserApiKey(String username, String apiKey);

    boolean updateUserPassword(String username, String password);

    boolean updateUserLastLogin(String username);

    boolean deleteUser(String username);
}
