package com.baafoo.server.api.dto;

import org.junit.Test;
import static org.junit.Assert.*;

public class LoginResponseTest {

    @Test
    public void fluentBuilderSetsFields() {
        LoginResponse resp = new LoginResponse()
                .token("jwt-abc")
                .role("admin");
        assertEquals("jwt-abc", resp.token);
        assertEquals("admin", resp.role);
    }
}
