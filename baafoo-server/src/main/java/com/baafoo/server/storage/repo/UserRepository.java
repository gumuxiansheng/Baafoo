package com.baafoo.server.storage.repo;

import com.baafoo.core.model.User;
import com.baafoo.core.util.IdGenerator;
import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

public class UserRepository {
    private static final Logger log = LoggerFactory.getLogger(UserRepository.class);
    private final HikariDataSource dataSource;

    public UserRepository(HikariDataSource dataSource) {
        this.dataSource = dataSource;
    }

    public List<User> listUsers() {
        String sql = "SELECT * FROM users ORDER BY created_at ASC";
        List<User> result = new ArrayList<>();
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                result.add(mapUser(rs));
            }
        } catch (SQLException e) {
            log.error("Failed to list users: {}", e.getMessage());
        }
        return result;
    }

    public User getUserByUsername(String username) {
        String sql = "SELECT * FROM users WHERE username = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapUser(rs);
            }
        } catch (SQLException e) {
            log.error("Failed to get user {}: {}", username, e.getMessage());
        }
        return null;
    }

    public User getUserByApiKey(String apiKey) {
        String sql = "SELECT * FROM users WHERE api_key = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, apiKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return mapUser(rs);
            }
        } catch (SQLException e) {
            log.error("Failed to get user by API key: {}", e.getMessage());
        }
        return null;
    }

    public User createUser(User user) {
        if (user.getId() == null || user.getId().isEmpty()) {
            user.setId(IdGenerator.uuid());
        }
        long now = System.currentTimeMillis();
        user.setCreatedAt(now);
        user.setUpdatedAt(now);

        String sql = "INSERT INTO users (id, username, password_hash, display_name, email, role, api_key, created_at, updated_at, last_login_at) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, user.getId());
            ps.setString(2, user.getUsername());
            ps.setString(3, user.getPasswordHash());
            ps.setString(4, user.getDisplayName());
            ps.setString(5, user.getEmail());
            ps.setString(6, user.getRole());
            ps.setString(7, user.getApiKey());
            ps.setLong(8, user.getCreatedAt());
            ps.setLong(9, user.getUpdatedAt());
            if (user.getLastLoginAt() != null) {
                ps.setLong(10, user.getLastLoginAt());
            } else {
                ps.setNull(10, Types.BIGINT);
            }
            ps.executeUpdate();
            return user;
        } catch (SQLException e) {
            log.error("Failed to create user: {}", e.getMessage());
            return null;
        }
    }

    public boolean updateUserRole(String username, String role) {
        String sql = "UPDATE users SET role = ?, updated_at = ? WHERE username = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, role);
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, username);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("Failed to update role for user {}: {}", username, e.getMessage());
            return false;
        }
    }

    public boolean updateUserApiKey(String username, String apiKey) {
        String sql = "UPDATE users SET api_key = ?, updated_at = ? WHERE username = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, apiKey);
            ps.setLong(2, System.currentTimeMillis());
            ps.setString(3, username);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("Failed to update API key for user {}: {}", username, e.getMessage());
            return false;
        }
    }

    public boolean updateUserLastLogin(String username) {
        String sql = "UPDATE users SET last_login_at = ? WHERE username = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, System.currentTimeMillis());
            ps.setString(2, username);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("Failed to update last login for user {}: {}", username, e.getMessage());
            return false;
        }
    }

    public boolean deleteUser(String username) {
        String sql = "DELETE FROM users WHERE username = ?";
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            return ps.executeUpdate() > 0;
        } catch (SQLException e) {
            log.error("Failed to delete user {}: {}", username, e.getMessage());
            return false;
        }
    }

    private User mapUser(ResultSet rs) throws SQLException {
        User u = new User();
        u.setId(rs.getString("id"));
        u.setUsername(rs.getString("username"));
        u.setPasswordHash(rs.getString("password_hash"));
        u.setDisplayName(rs.getString("display_name"));
        u.setEmail(rs.getString("email"));
        u.setRole(rs.getString("role"));
        u.setApiKey(rs.getString("api_key"));
        u.setCreatedAt(rs.getLong("created_at"));
        u.setUpdatedAt(rs.getLong("updated_at"));
        long lastLogin = rs.getLong("last_login_at");
        u.setLastLoginAt(rs.wasNull() ? null : lastLogin);
        return u;
    }
}
