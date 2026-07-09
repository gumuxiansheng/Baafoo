package com.baafoo.server.api.dto;

import org.junit.Test;

import java.util.Arrays;

import static org.junit.Assert.*;

public class AuthMeResponseTest {

    @Test
    public void fluentBuilderSetsAllFields() {
        AuthMeResponse resp = new AuthMeResponse()
                .authenticated(true)
                .role("admin")
                .username("alice")
                .authMethod("api-key")
                .permissions(Arrays.asList("read", "write"));

        assertTrue(resp.authenticated);
        assertEquals("admin", resp.role);
        assertEquals("alice", resp.username);
        assertEquals("api-key", resp.authMethod);
        assertEquals(2, resp.permissions.size());
    }
}
