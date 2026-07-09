package com.baafoo.server.api.dto;

import org.junit.Test;
import static org.junit.Assert.*;

public class ApiKeyResponseTest {

    @Test
    public void fluentBuilderSetsApiKey() {
        ApiKeyResponse resp = new ApiKeyResponse().apiKey("secret-123");
        assertEquals("secret-123", resp.apiKey);
    }
}
