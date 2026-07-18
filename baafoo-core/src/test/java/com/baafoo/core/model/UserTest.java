package com.baafoo.core.model;

import org.junit.Test;

import static org.junit.Assert.*;

public class UserTest {

    @Test
    public void testDefaultRole() {
        User user = new User();
        assertEquals("guest", user.getRole());
    }

    @Test
    public void testSetAndGetId() {
        User user = new User();
        user.setId(42L);
        assertEquals(Long.valueOf(42L), user.getId());
    }

    @Test
    public void testSetAndGetUsername() {
        User user = new User();
        user.setUsername("admin");
        assertEquals("admin", user.getUsername());
    }

    @Test
    public void testSetAndGetPassword() {
        User user = new User();
        user.setPassword("abc123");
        assertEquals("abc123", user.getPassword());
    }

    @Test
    public void testSetAndGetDisplayName() {
        User user = new User();
        user.setDisplayName("Admin User");
        assertEquals("Admin User", user.getDisplayName());
    }

    @Test
    public void testSetAndGetEmail() {
        User user = new User();
        user.setEmail("admin@example.com");
        assertEquals("admin@example.com", user.getEmail());
    }

    @Test
    public void testSetAndGetRole() {
        User user = new User();
        user.setRole("admin");
        assertEquals("admin", user.getRole());
    }

    @Test
    public void testSetAndGetApiKey() {
        User user = new User();
        user.setApiKey("key-123");
        assertEquals("key-123", user.getApiKey());
    }

    @Test
    public void testNullFieldsByDefault() {
        User user = new User();
        assertNull(user.getId());
        assertNull(user.getUsername());
        assertNull(user.getPassword());
        assertNull(user.getDisplayName());
        assertNull(user.getEmail());
        assertNull(user.getApiKey());
        assertNull(user.getCreatedAt());
        assertNull(user.getUpdatedAt());
        assertNull(user.getLastLoginAt());
    }
}
