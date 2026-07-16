package com.baafoo.testcontainers;

import com.baafoo.core.model.MatchCondition;
import com.baafoo.core.model.ResponseEntry;
import com.baafoo.core.model.Rule;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.DockerClientFactory;

import java.util.Collections;

import static org.junit.Assert.*;
import static org.junit.Assume.assumeTrue;

public class BaafooServerContainerTest {

    private static final Logger log = LoggerFactory.getLogger(BaafooServerContainerTest.class);

    /**
     * API key shared between the test client and the server container. The
     * container exports it as the BAAFOO_API_KEY environment variable, which the
     * server registers as an admin key; the client sends it via the X-Api-Key
     * header so write operations (create rule / environment) are authorized.
     */
    private static final String TEST_API_KEY = "baafoo-test-api-key";

    private BaafooServerContainer container;

    @Before
    public void setUp() {
        assumeTrue("Docker must be available", isDockerAvailable());

        container = new BaafooServerContainer().withApiKey(TEST_API_KEY);
        container.start();
    }

    @After
    public void tearDown() {
        if (container != null) {
            container.stop();
        }
    }

    @Test
    public void testContainerStartsAndIsHealthy() {
        String status = container.getClient().getStatus().toString();
        assertNotNull("Status should not be null", status);
        log.info("Server status: {}", status);
    }

    @Test
    public void testCreateAndListRules() {
        Rule rule = new Rule();
        rule.setName("test-rule");
        rule.setProtocol("http");
        rule.setHost("example.com");
        rule.setPort(80);
        rule.setConditions(Collections.singletonList(
                MatchCondition.path("equals", "/api/test")));
        ResponseEntry response = new ResponseEntry();
        response.setBody("{\"result\":\"ok\"}");
        response.setStatusCode(200);
        rule.setResponses(Collections.singletonList(response));

        Rule created = container.getClient().createRule(rule);
        assertNotNull("Created rule should have an id", created.getId());
        assertEquals("Rule name should match", "test-rule", created.getName());

        assertFalse("Rule list should not be empty",
                container.getClient().listRules().isEmpty());
    }

    @Test
    public void testGetRuleById() {
        Rule rule = new Rule();
        rule.setName("test-rule-get");
        rule.setProtocol("http");
        rule.setHost("get-test.com");
        rule.setPort(80);
        rule.setConditions(Collections.singletonList(
                MatchCondition.path("equals", "/get")));
        ResponseEntry response = new ResponseEntry();
        response.setBody("get");
        response.setStatusCode(200);
        rule.setResponses(Collections.singletonList(response));

        Rule created = container.getClient().createRule(rule);
        assertNotNull("Created rule should have an id", created.getId());

        Rule fetched = container.getClient().getRule(created.getId());
        assertNotNull("Fetched rule should not be null", fetched);
        assertEquals("Fetched rule name should match", "test-rule-get", fetched.getName());
    }

    @Test
    public void testDeleteRuleRoundTrip() {
        Rule rule = new Rule();
        rule.setName("test-rule-delete");
        rule.setProtocol("http");
        rule.setHost("delete-test.com");
        rule.setPort(80);
        rule.setConditions(Collections.singletonList(
                MatchCondition.path("equals", "/delete")));
        ResponseEntry response = new ResponseEntry();
        response.setBody("delete");
        response.setStatusCode(200);
        rule.setResponses(Collections.singletonList(response));

        Rule created = container.getClient().createRule(rule);
        assertNotNull("Created rule should have an id", created.getId());

        container.getClient().deleteRule(created.getId());

        boolean stillPresent = container.getClient().listRules().stream()
                .anyMatch(r -> created.getId().equals(r.getId()));
        assertFalse("Rule should no longer be present after delete", stillPresent);
    }

    @Test
    public void testCreateAndListEnvironments() {
        container.getClient().createEnvironment("test-env", "stub");

        assertFalse("Environment list should not be empty",
                container.getClient().listEnvironments().isEmpty());
    }

    @Test
    public void testPreloadRuleViaContainer() {
        Rule rule = new Rule();
        rule.setName("preloaded-rule");
        rule.setProtocol("http");
        rule.setHost("preload-test.com");
        rule.setConditions(Collections.singletonList(
                MatchCondition.path("equals", "/preload")));
        ResponseEntry response = new ResponseEntry();
        response.setBody("preloaded");
        rule.setResponses(Collections.singletonList(response));

        BaafooServerContainer preloaded = new BaafooServerContainer()
                .withRule(rule)
                .withApiKey(TEST_API_KEY);
        try {
            preloaded.start();

            assertFalse("Preloaded rule should appear in server",
                    preloaded.getClient().listRules().isEmpty());
            log.info("Preloaded rule verified successfully");
        } finally {
            preloaded.stop();
        }
    }

    @Test
    public void testHttpBaseUrlFormat() {
        String url = container.getHttpBaseUrl();
        assertTrue("Base URL should start with http://", url.startsWith("http://"));
        assertTrue("Base URL should contain port", url.contains(":"));
        log.info("Server HTTP base URL: {}", url);
    }

    @Test
    public void testGetClientReturnsSameInstance() {
        BaafooClient c1 = container.getClient();
        BaafooClient c2 = container.getClient();
        assertSame("getClient() should return the same instance", c1, c2);
    }

    private static boolean isDockerAvailable() {
        try {
            return DockerClientFactory.instance().isDockerAvailable();
        } catch (Throwable t) {
            return false;
        }
    }
}
