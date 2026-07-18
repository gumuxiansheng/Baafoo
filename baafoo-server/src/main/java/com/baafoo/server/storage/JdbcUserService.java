package com.baafoo.server.storage;

import com.baafoo.core.model.User;
import com.baafoo.server.storage.mapper.UserMapper;
import org.apache.ibatis.session.SqlSession;
import org.apache.ibatis.session.SqlSessionFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * JDBC implementation of {@link UserService}.
 *
 * <p>P0-4: extracted from {@code JdbcStorageService}. Owns user CRUD and
 * credential/role updates. No caching — user reads are low-frequency.</p>
 */
public class JdbcUserService extends BaseJdbcService implements UserService {

    private static final Logger log = LoggerFactory.getLogger(JdbcUserService.class);

    public JdbcUserService(SqlSessionFactory sqlSessionFactory) {
        super(sqlSessionFactory);
    }

    @Override
    public List<User> listUsers() {
        try (SqlSession session = openSession()) {
            return session.getMapper(UserMapper.class).listUsers();
        }
    }

    @Override
    public User getUserByUsername(String username) {
        try (SqlSession session = openSession()) {
            return session.getMapper(UserMapper.class).getUserByUsername(username);
        }
    }

    @Override
    public User getUserByApiKey(String apiKey) {
        try (SqlSession session = openSession()) {
            return session.getMapper(UserMapper.class).getUserByApiKey(apiKey);
        }
    }

    @Override
    public User createUser(User user) {
        // M-9: validate the role code resolves to a real sys_role row before
        // INSERT. The UserMapper.createUser SQL uses a subquery
        // (SELECT id FROM sys_role WHERE code = #{role}) which silently
        // writes NULL role_id when the code is unknown — that would later
        // break role-based authz checks. Fail fast here instead.
        String role = user != null ? user.getRole() : null;
        if (role == null || role.isEmpty()) {
            log.warn("createUser rejected: role is null or empty (username={})",
                    user != null ? user.getUsername() : null);
            return null;
        }
        try (SqlSession session = openSession()) {
            UserMapper mapper = session.getMapper(UserMapper.class);
            Long roleId = mapper.findRoleIdByCode(role);
            if (roleId == null) {
                log.warn("createUser rejected: role '{}' does not exist in sys_role (username={})",
                        role, user.getUsername());
                return null;
            }
            mapper.createUser(user);
            return user;
        } catch (Exception e) {
            log.error("Failed to create user: {}", e.getMessage());
            return null;
        }
    }

    @Override
    public boolean updateUserRole(String username, String role) {
        try (SqlSession session = openSession()) {
            return session.getMapper(UserMapper.class).updateUserRole(username, role) > 0;
        } catch (Exception e) {
            log.error("Failed to update role for user {}: {}", username, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean updateUserApiKey(String username, String apiKey) {
        try (SqlSession session = openSession()) {
            return session.getMapper(UserMapper.class).updateUserApiKey(username, apiKey) > 0;
        } catch (Exception e) {
            log.error("Failed to update API key for user {}: {}", username, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean updateUserPassword(String username, String password) {
        try (SqlSession session = openSession()) {
            return session.getMapper(UserMapper.class).updateUserPassword(username, password) > 0;
        } catch (Exception e) {
            log.error("Failed to update password for user {}: {}", username, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean updateUserLastLogin(String username) {
        try (SqlSession session = openSession()) {
            return session.getMapper(UserMapper.class).updateUserLastLogin(username) > 0;
        } catch (Exception e) {
            log.error("Failed to update last login for user {}: {}", username, e.getMessage());
            return false;
        }
    }

    @Override
    public boolean deleteUser(String username) {
        try (SqlSession session = openSession()) {
            return session.getMapper(UserMapper.class).deleteUser(username) > 0;
        } catch (Exception e) {
            log.error("Failed to delete user {}: {}", username, e.getMessage());
            return false;
        }
    }
}
