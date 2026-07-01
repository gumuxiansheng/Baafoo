package com.baafoo.core.util;

import com.baafoo.core.model.MatchCondition;
import com.baafoo.core.model.ResponseEntry;
import com.baafoo.core.model.Rule;
import org.junit.Before;
import org.junit.Test;

import java.util.*;

import static org.junit.Assert.*;

public class MatchEngineTest {

    private final MatchEngine engine = new MatchEngine();

    @Before
    public void setUp() {
        // Reset the global counter store before each test to ensure isolation
        StatefulCounterStore.global().resetAll();
    }

    @Test
    public void testNoMatchWhenRulesNull() {
        MatchEngine.MatchResult result = engine.match(null, "http", "host", 80, null, "GET", "/", Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), "");
        assertFalse(result.isMatched());
    }

    @Test
    public void testNoMatchWhenRulesEmpty() {
        MatchEngine.MatchResult result = engine.match(new ArrayList<Rule>(), "http", "host", 80, null, "GET", "/", Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), "");
        assertFalse(result.isMatched());
    }

    @Test
    public void testSkipDisabledRule() {
        Rule r = new Rule();
        r.setId("r1");
        r.setEnabled(false);

        MatchEngine.MatchResult result = engine.match(Collections.singletonList(r), "http", "host", 80, null, "GET", "/", Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), "");
        assertFalse(result.isMatched());
    }

    @Test
    public void testMatchByProtocol() {
        Rule r = createSimpleRule("r1");
        r.setProtocol("http");

        MatchEngine.MatchResult result = engine.match(Collections.singletonList(r), "http", "host", 80, null, "GET", "/", Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), "");
        assertTrue(result.isMatched());

        result = engine.match(Collections.singletonList(r), "tcp", "host", 80, null, "GET", "/", Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), "");
        assertFalse(result.isMatched());
    }

    @Test
    public void testWildcardProtocol() {
        Rule r = createSimpleRule("r1");
        r.setProtocol(null);

        MatchEngine.MatchResult result = engine.match(Collections.singletonList(r), "http", "host", 80, null, "GET", "/", Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), "");
        assertTrue(result.isMatched());
    }

    @Test
    public void testMatchByServiceName() {
        Rule r = createSimpleRule("r1");
        r.setServiceName("my-service");

        MatchEngine.MatchResult result = engine.match(Collections.singletonList(r), "http", "host", 80, "my-service", "GET", "/", Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), "");
        assertTrue(result.isMatched());

        result = engine.match(Collections.singletonList(r), "http", "host", 80, "other-service", "GET", "/", Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), "");
        assertFalse(result.isMatched());
    }

    @Test
    public void testMatchByHost() {
        Rule r = createSimpleRule("r1");
        r.setHost("api.example.com");

        MatchEngine.MatchResult result = engine.match(Collections.singletonList(r), "http", "api.example.com", 80, null, "GET", "/", Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), "");
        assertTrue(result.isMatched());

        result = engine.match(Collections.singletonList(r), "http", "other.com", 80, null, "GET", "/", Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), "");
        assertFalse(result.isMatched());
    }

    @Test
    public void testMatchByPort() {
        Rule r = createSimpleRule("r1");
        r.setHost("api.example.com");
        r.setPort(8084);

        MatchEngine.MatchResult result = engine.match(Collections.singletonList(r), "http", "api.example.com", 8084, null, "GET", "/", Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), "");
        assertTrue(result.isMatched());

        result = engine.match(Collections.singletonList(r), "http", "api.example.com", 9090, null, "GET", "/", Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), "");
        assertFalse(result.isMatched());
    }

    @Test
    public void testMatchMethodCondition() {
        Rule r = createSimpleRule("r1");
        r.setConditions(Arrays.asList(MatchCondition.method("GET")));

        MatchEngine.MatchResult result = engine.match(Collections.singletonList(r), "http", "host", 80, null, "GET", "/api", Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), "");
        assertTrue(result.isMatched());

        result = engine.match(Collections.singletonList(r), "http", "host", 80, null, "POST", "/api", Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), "");
        assertFalse(result.isMatched());
    }

    @Test
    public void testMatchPathConditionEquals() {
        Rule r = createSimpleRule("r1");
        r.setConditions(Arrays.asList(MatchCondition.path("equals", "/api/users")));

        MatchEngine.MatchResult result = engine.match(Collections.singletonList(r), "http", "host", 80, null, "GET", "/api/users", Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), "");
        assertTrue(result.isMatched());
    }

    @Test
    public void testMatchPathConditionStartsWith() {
        Rule r = createSimpleRule("r1");
        r.setConditions(Arrays.asList(MatchCondition.path("startsWith", "/api")));

        MatchEngine.MatchResult result = engine.match(Collections.singletonList(r), "http", "host", 80, null, "GET", "/api/users/123", Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), "");
        assertTrue(result.isMatched());

        result = engine.match(Collections.singletonList(r), "http", "host", 80, null, "GET", "/other", Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), "");
        assertFalse(result.isMatched());
    }

    @Test
    public void testMatchHeaderCondition() {
        Rule r = createSimpleRule("r1");
        r.setConditions(Arrays.asList(MatchCondition.header("Authorization", "contains", "Bearer")));

        Map<String, String> headers = new HashMap<String, String>();
        headers.put("Authorization", "Bearer xxx");

        MatchEngine.MatchResult result = engine.match(Collections.singletonList(r), "http", "host", 80, null, "GET", "/", headers, Collections.<String, String>emptyMap(), "");
        assertTrue(result.isMatched());
    }

    @Test
    public void testMatchHeaderConditionMissing() {
        Rule r = createSimpleRule("r1");
        r.setConditions(Arrays.asList(MatchCondition.header("X-Missing", "equals", "val")));

        MatchEngine.MatchResult result = engine.match(Collections.singletonList(r), "http", "host", 80, null, "GET", "/", Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), "");
        assertFalse(result.isMatched());
    }

    @Test
    public void testMatchQueryCondition() {
        Rule r = createSimpleRule("r1");
        r.setConditions(Arrays.asList(MatchCondition.query("page", "equals", "2")));

        Map<String, String> params = new HashMap<String, String>();
        params.put("page", "2");

        MatchEngine.MatchResult result = engine.match(Collections.singletonList(r), "http", "host", 80, null, "GET", "/", Collections.<String, String>emptyMap(), params, "");
        assertTrue(result.isMatched());
    }

    @Test
    public void testMatchBodyCondition() {
        Rule r = createSimpleRule("r1");
        r.setConditions(Arrays.asList(MatchCondition.body("contains", "error")));

        MatchEngine.MatchResult result = engine.match(Collections.singletonList(r), "http", "host", 80, null, "POST", "/", Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), "{\"error\":true}");
        assertTrue(result.isMatched());

        result = engine.match(Collections.singletonList(r), "http", "host", 80, null, "POST", "/", Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), "{\"ok\":true}");
        assertFalse(result.isMatched());
    }

    @Test
    public void testMultipleConditionsAnd() {
        Rule r = createSimpleRule("r1");
        r.setConditions(Arrays.asList(
                MatchCondition.method("GET"),
                MatchCondition.path("startsWith", "/api"),
                MatchCondition.header("Accept", "contains", "json")
        ));

        Map<String, String> headers = new HashMap<String, String>();
        headers.put("Accept", "application/json");

        MatchEngine.MatchResult result = engine.match(Collections.singletonList(r), "http", "host", 80, null, "GET", "/api/users", headers, Collections.<String, String>emptyMap(), "");
        assertTrue(result.isMatched());

        result = engine.match(Collections.singletonList(r), "http", "host", 80, null, "POST", "/api/users", headers, Collections.<String, String>emptyMap(), "");
        assertFalse(result.isMatched());
    }

    @Test
    public void testResponseConditionMatch() {
        Rule r = createSimpleRule("r1");
        r.setConditions(Arrays.asList(MatchCondition.method("GET")));

        ResponseEntry condResp = new ResponseEntry();
        condResp.setBody("conditioned");
        condResp.setName("cond");
        condResp.setCondition(MatchCondition.path("equals", "/special"));

        ResponseEntry defaultResp = new ResponseEntry();
        defaultResp.setBody("default");
        defaultResp.setName("default");

        r.setResponses(Arrays.asList(condResp, defaultResp));

        MatchEngine.MatchResult result = engine.match(Collections.singletonList(r), "http", "host", 80, null, "GET", "/special", Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), "");
        assertTrue(result.isMatched());
        assertEquals("cond", result.getResponse().getName());

        result = engine.match(Collections.singletonList(r), "http", "host", 80, null, "GET", "/other", Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), "");
        assertTrue(result.isMatched());
        assertEquals("default", result.getResponse().getName());
    }

    @Test
    public void testRegexCondition() {
        Rule r = createSimpleRule("r1");
        MatchCondition cond = MatchCondition.path("regex", "/api/.*");
        r.setConditions(Arrays.asList(cond));

        MatchEngine.MatchResult result = engine.match(Collections.singletonList(r), "http", "host", 80, null, "GET", "/api/users/123", Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), "");
        assertTrue(result.isMatched());

        result = engine.match(Collections.singletonList(r), "http", "host", 80, null, "GET", "/other", Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), "");
        assertFalse(result.isMatched());
    }

    @Test
    public void testBodyContainsCondition() {
        Rule r = createSimpleRule("r1");
        MatchCondition cond = new MatchCondition();
        cond.setType("bodyContains");
        cond.setValue("error");
        r.setConditions(Arrays.asList(cond));

        MatchEngine.MatchResult result = engine.match(Collections.singletonList(r), "http", "host", 80, null, "POST", "/", Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), "this has an error");
        assertTrue(result.isMatched());

        result = engine.match(Collections.singletonList(r), "http", "host", 80, null, "POST", "/", Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), "all good");
        assertFalse(result.isMatched());
    }

    @Test
    public void testMatchTopicConditionEquals() {
        // topic is an alias of path, used by MQ (Kafka/Pulsar) rules to match topic name.
        // The broker passes the topic through the "path" parameter slot.
        Rule r = createSimpleRule("r1");
        r.setProtocol("kafka");
        r.setConditions(Arrays.asList(MatchCondition.topic("equals", "order-events")));

        MatchEngine.MatchResult result = engine.match(
                Collections.singletonList(r), "kafka", null, 0, null, null, "order-events",
                Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), "");
        assertTrue(result.isMatched());

        result = engine.match(
                Collections.singletonList(r), "kafka", null, 0, null, null, "other-topic",
                Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), "");
        assertFalse(result.isMatched());
    }

    @Test
    public void testMatchTopicConditionStartsWith() {
        Rule r = createSimpleRule("r1");
        r.setProtocol("pulsar");
        r.setConditions(Arrays.asList(MatchCondition.topic("startsWith", "persistent://tenant/ns/")));

        MatchEngine.MatchResult result = engine.match(
                Collections.singletonList(r), "pulsar", null, 0, null, null,
                "persistent://tenant/ns/order",
                Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), "");
        assertTrue(result.isMatched());
    }

    @Test
    public void testMatchTopicConditionViaStringType() {
        // Same as testMatchTopicConditionEquals but building condition via setType("topic")
        // to mirror how rules are deserialized from storage JSON.
        Rule r = createSimpleRule("r1");
        r.setProtocol("kafka");
        MatchCondition cond = new MatchCondition();
        cond.setType("topic");
        cond.setOperator("equals");
        cond.setValue("order-events");
        r.setConditions(Arrays.asList(cond));

        MatchEngine.MatchResult result = engine.match(
                Collections.singletonList(r), "kafka", null, 0, null, null, "order-events",
                Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), "");
        assertTrue(result.isMatched());
    }

    @Test
    public void testExistsOperator() {
        Rule r = createSimpleRule("r1");
        MatchCondition cond = new MatchCondition();
        cond.setType("header");
        cond.setKey("X-Custom");
        cond.setOperator("exists");
        r.setConditions(Arrays.asList(cond));

        Map<String, String> headers = new HashMap<String, String>();
        headers.put("X-Custom", "present");

        MatchEngine.MatchResult result = engine.match(Collections.singletonList(r), "http", "host", 80, null, "GET", "/", headers, Collections.<String, String>emptyMap(), "");
        assertTrue(result.isMatched());

        result = engine.match(Collections.singletonList(r), "http", "host", 80, null, "GET", "/", Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), "");
        assertFalse(result.isMatched());
    }

    @Test
    public void testEndsWithOperator() {
        Rule r = createSimpleRule("r1");
        r.setConditions(Arrays.asList(MatchCondition.path("endsWith", ".html")));

        MatchEngine.MatchResult result = engine.match(Collections.singletonList(r), "http", "host", 80, null, "GET", "/page.html", Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), "");
        assertTrue(result.isMatched());
    }

    @Test
    public void testCaseSensitive() {
        Rule r = createSimpleRule("r1");
        MatchCondition cond = MatchCondition.method("get");
        cond.setCaseSensitive(true);
        r.setConditions(Arrays.asList(cond));

        MatchEngine.MatchResult result = engine.match(Collections.singletonList(r), "http", "host", 80, null, "GET", "/", Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), "");
        assertFalse(result.isMatched());
    }

    @Test
    public void testCaseInsensitive() {
        Rule r = createSimpleRule("r1");
        MatchCondition cond = MatchCondition.method("get");
        cond.setCaseSensitive(false);
        r.setConditions(Arrays.asList(cond));

        MatchEngine.MatchResult result = engine.match(Collections.singletonList(r), "http", "host", 80, null, "GET", "/", Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), "");
        assertTrue(result.isMatched());
    }

    @Test
    public void testPriorityOrdering() {
        Rule r1 = createSimpleRule("r1-low");
        r1.setPriority(200);
        Rule r2 = createSimpleRule("r2-higher");
        r2.setPriority(10);

        // MatchEngine iterates in list order; first match wins
        List<Rule> rules = new ArrayList<Rule>();
        rules.add(r2);
        rules.add(r1);

        MatchEngine.MatchResult result = engine.match(rules, "http", "host", 80, null, "GET", "/", Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), "");
        assertTrue(result.isMatched());
        assertEquals("r2-higher", result.getRule().getId());
    }

    @Test
    public void testBodyJsonPathEquals() {
        // bodyJsonPath with key=path, value=expected, operator=equals
        Rule r = createSimpleRule("r1");
        MatchCondition cond = new MatchCondition();
        cond.setType("bodyJsonPath");
        cond.setKey("$.error");
        cond.setOperator("equals");
        cond.setValue("true");
        r.setConditions(Arrays.asList(cond));

        MatchEngine.MatchResult result = engine.match(Collections.singletonList(r), "http", "host", 80, null, "POST", "/", Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), "{\"error\":true}");
        assertTrue(result.isMatched());

        result = engine.match(Collections.singletonList(r), "http", "host", 80, null, "POST", "/", Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), "{\"error\":false}");
        assertFalse(result.isMatched());
    }

    @Test
    public void testBodyJsonPathExists() {
        Rule r = createSimpleRule("r1");
        MatchCondition cond = new MatchCondition();
        cond.setType("bodyJsonPath");
        cond.setKey("$.user.name");
        cond.setOperator("exists");
        r.setConditions(Arrays.asList(cond));

        MatchEngine.MatchResult result = engine.match(Collections.singletonList(r), "http", "host", 80, null, "POST", "/", Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), "{\"user\":{\"name\":\"alice\"}}");
        assertTrue(result.isMatched());

        result = engine.match(Collections.singletonList(r), "http", "host", 80, null, "POST", "/", Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), "{\"user\":{}}");
        assertFalse(result.isMatched());
    }

    @Test
    public void testBodyJsonPathBackwardCompatValueAsPath() {
        // Backward-compat: when key is unset, value is treated as the path
        // with implicit "exists" semantics.
        Rule r = createSimpleRule("r1");
        MatchCondition cond = new MatchCondition();
        cond.setType("bodyJsonPath");
        cond.setValue("$.error");
        r.setConditions(Arrays.asList(cond));

        MatchEngine.MatchResult result = engine.match(Collections.singletonList(r), "http", "host", 80, null, "POST", "/", Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), "{\"error\":true}");
        assertTrue(result.isMatched());

        result = engine.match(Collections.singletonList(r), "http", "host", 80, null, "POST", "/", Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), "{\"ok\":true}");
        assertFalse(result.isMatched());
    }

    @Test
    public void testBodyJsonPathNested() {
        Rule r = createSimpleRule("r1");
        MatchCondition cond = new MatchCondition();
        cond.setType("bodyJsonPath");
        cond.setKey("$.user.address.city");
        cond.setOperator("equals");
        cond.setValue("Beijing");
        r.setConditions(Arrays.asList(cond));

        MatchEngine.MatchResult result = engine.match(Collections.singletonList(r), "http", "host", 80, null, "POST", "/", Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), "{\"user\":{\"address\":{\"city\":\"Beijing\"}}}");
        assertTrue(result.isMatched());
    }

    @Test
    public void testGraphqlOperationNameEquals() {
        Rule r = createSimpleRule("r1");
        r.setConditions(Arrays.asList(MatchCondition.graphqlOperationName("equals", "GetUser")));

        String body = "{\"query\":\"query GetUser { user { id } }\",\"operationName\":\"GetUser\"}";

        MatchEngine.MatchResult result = engine.match(Collections.singletonList(r), "http", "host", 80, null, "POST", "/graphql", Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), body);
        assertTrue(result.isMatched());

        body = "{\"query\":\"query OtherUser { user { id } }\",\"operationName\":\"OtherUser\"}";
        result = engine.match(Collections.singletonList(r), "http", "host", 80, null, "POST", "/graphql", Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), body);
        assertFalse(result.isMatched());
    }

    @Test
    public void testGraphqlOperationNameExists() {
        Rule r = createSimpleRule("r1");
        r.setConditions(Arrays.asList(MatchCondition.graphqlOperationName("exists", null)));

        String body = "{\"query\":\"query GetUser { user { id } }\",\"operationName\":\"GetUser\"}";
        MatchEngine.MatchResult result = engine.match(Collections.singletonList(r), "http", "host", 80, null, "POST", "/graphql", Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), body);
        assertTrue(result.isMatched());

        // No operationName field → should not match
        body = "{\"query\":\"query GetUser { user { id } }\"}";
        result = engine.match(Collections.singletonList(r), "http", "host", 80, null, "POST", "/graphql", Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), body);
        assertFalse(result.isMatched());
    }

    @Test
    public void testGraphqlOperationTypeQuery() {
        Rule r = createSimpleRule("r1");
        r.setConditions(Arrays.asList(MatchCondition.graphqlOperationType("equals", "query")));

        // Explicit query
        String body = "{\"query\":\"query GetUser { user { id } }\"}";
        MatchEngine.MatchResult result = engine.match(Collections.singletonList(r), "http", "host", 80, null, "POST", "/graphql", Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), body);
        assertTrue(result.isMatched());

        // Anonymous query (shorthand syntax)
        body = "{\"query\":\"{ user { id } }\"}";
        result = engine.match(Collections.singletonList(r), "http", "host", 80, null, "POST", "/graphql", Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), body);
        assertTrue(result.isMatched());
    }

    @Test
    public void testGraphqlOperationTypeMutation() {
        Rule r = createSimpleRule("r1");
        r.setConditions(Arrays.asList(MatchCondition.graphqlOperationType("equals", "mutation")));

        String body = "{\"query\":\"mutation UpdateUser($id: ID!) { updateUser(id: $id) { id } }\"}";
        MatchEngine.MatchResult result = engine.match(Collections.singletonList(r), "http", "host", 80, null, "POST", "/graphql", Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), body);
        assertTrue(result.isMatched());

        // query should NOT match mutation rule
        body = "{\"query\":\"query GetUser { user { id } }\"}";
        result = engine.match(Collections.singletonList(r), "http", "host", 80, null, "POST", "/graphql", Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), body);
        assertFalse(result.isMatched());
    }

    @Test
    public void testGraphqlOperationTypeSubscription() {
        Rule r = createSimpleRule("r1");
        r.setConditions(Arrays.asList(MatchCondition.graphqlOperationType("equals", "subscription")));

        String body = "{\"query\":\"subscription UserUpdates { userUpdates { id } }\"}";
        MatchEngine.MatchResult result = engine.match(Collections.singletonList(r), "http", "host", 80, null, "POST", "/graphql", Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), body);
        assertTrue(result.isMatched());
    }

    @Test
    public void testGraphqlOperationTypeWithLeadingComment() {
        Rule r = createSimpleRule("r1");
        r.setConditions(Arrays.asList(MatchCondition.graphqlOperationType("equals", "query")));

        // GraphQL query with a leading # comment
        String body = "{\"query\":\"# This is a comment\\nquery GetUser { user { id } }\"}";
        MatchEngine.MatchResult result = engine.match(Collections.singletonList(r), "http", "host", 80, null, "POST", "/graphql", Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), body);
        assertTrue(result.isMatched());
    }

    @Test
    public void testGraphqlCombinedConditions() {
        // Combine graphqlOperationName + graphqlOperationType (AND logic)
        Rule r = createSimpleRule("r1");
        r.setConditions(Arrays.asList(
                MatchCondition.graphqlOperationName("equals", "GetUser"),
                MatchCondition.graphqlOperationType("equals", "query")
        ));

        String body = "{\"query\":\"query GetUser { user { id } }\",\"operationName\":\"GetUser\"}";
        MatchEngine.MatchResult result = engine.match(Collections.singletonList(r), "http", "host", 80, null, "POST", "/graphql", Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), body);
        assertTrue(result.isMatched());

        // operationName matches but type is mutation → no match
        body = "{\"query\":\"mutation GetUser { user { id } }\",\"operationName\":\"GetUser\"}";
        result = engine.match(Collections.singletonList(r), "http", "host", 80, null, "POST", "/graphql", Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), body);
        assertFalse(result.isMatched());
    }

    @Test
    public void testGraphqlOperationTypeMissingQueryField() {
        Rule r = createSimpleRule("r1");
        r.setConditions(Arrays.asList(MatchCondition.graphqlOperationType("equals", "query")));

        // Body without "query" field → should not match
        String body = "{\"operationName\":\"GetUser\"}";
        MatchEngine.MatchResult result = engine.match(Collections.singletonList(r), "http", "host", 80, null, "POST", "/graphql", Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), body);
        assertFalse(result.isMatched());
    }

    @Test
    public void testGraphqlOperationNameCaseInsensitive() {
        Rule r = createSimpleRule("r1");
        MatchCondition cond = MatchCondition.graphqlOperationName("equals", "getuser");
        cond.setCaseSensitive(false);
        r.setConditions(Arrays.asList(cond));

        String body = "{\"query\":\"query GetUser { user { id } }\",\"operationName\":\"GetUser\"}";
        MatchEngine.MatchResult result = engine.match(Collections.singletonList(r), "http", "host", 80, null, "POST", "/graphql", Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), body);
        assertTrue(result.isMatched());
    }

    @Test
    public void testUnknownConditionType() {
        Rule r = createSimpleRule("r1");
        MatchCondition cond = new MatchCondition();
        cond.setType("unknown");
        r.setConditions(Arrays.asList(cond));

        MatchEngine.MatchResult result = engine.match(Collections.singletonList(r), "http", "host", 80, null, "GET", "/", Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), "");
        assertFalse(result.isMatched());
    }

    @Test
    public void testMatchResultNoMatch() {
        MatchEngine.MatchResult result = MatchEngine.MatchResult.NO_MATCH;
        assertFalse(result.isMatched());
        assertNull(result.getRule());
        assertEquals(-1, result.getResponseIndex());
        assertNull(result.getResponse());
    }

    @Test
    public void testMatchResultGetResponse() {
        Rule r = createSimpleRule("r1");
        ResponseEntry resp = new ResponseEntry();
        resp.setBody("hello");
        r.setResponses(Arrays.asList(resp));

        MatchEngine.MatchResult result = new MatchEngine.MatchResult(r, 0);
        assertTrue(result.isMatched());
        assertEquals(r, result.getRule());
        assertEquals(0, result.getResponseIndex());
        assertNotNull(result.getResponse());
        assertEquals("hello", result.getResponse().getBody());
    }

    @Test
    public void testMatchResultGetResponseOutOfBounds() {
        Rule r = createSimpleRule("r1");
        ResponseEntry resp = new ResponseEntry();
        resp.setBody("default");
        r.setResponses(Arrays.asList(resp));

        MatchEngine.MatchResult result = new MatchEngine.MatchResult(r, 5);
        assertEquals("default", result.getResponse().getBody());
    }

    @Test
    public void testMqRuleWithHostAndNullHostParam() {
        // MQ brokers (Kafka/Pulsar/JMS) call match() with host=null, but the
        // rule may still be configured with a host. Host filter must not
        // short-circuit when host is unknown.
        Rule r = createSimpleRule("r1");
        r.setProtocol("pulsar");
        r.setHost("pulsar-broker");
        r.setPort(6650);
        r.setConditions(Arrays.asList(MatchCondition.topic("equals", "order-events")));

        // host=null, port=0 — MQ broker passes null/0 because it doesn't know the
        // real target host/port.
        MatchEngine.MatchResult result = engine.match(
                Collections.singletonList(r), "pulsar", null, 0, null, null,
                "order-events",
                Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), "");
        assertTrue(result.isMatched());
    }

    @Test
    public void testMqRuleWithServiceNameCondition() {
        // MQ brokers also pass topic through the serviceName slot as an alias.
        Rule r = createSimpleRule("r1");
        r.setProtocol("kafka");
        r.setServiceName("order-events");

        MatchEngine.MatchResult result = engine.match(
                Collections.singletonList(r), "kafka", null, 0, "order-events", null, "order-events",
                Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), "");
        assertTrue(result.isMatched());
    }

    // ===== Stateful Mock tests (PRD §3 R-S2 AC-13) =====

    @Test
    public void testRequestCountLessThan() {
        // PRD example: first 2 requests return "pending", 3rd onwards returns "paid"
        Rule r = createSimpleRule("stateful-order");
        ResponseEntry pendingResp = new ResponseEntry();
        pendingResp.setBody("pending");
        pendingResp.setName("pending");
        pendingResp.setCondition(MatchCondition.requestCount("lessThan", "3"));

        ResponseEntry paidResp = new ResponseEntry();
        paidResp.setBody("paid");
        paidResp.setName("paid");

        r.setResponses(Arrays.asList(pendingResp, paidResp));

        // Request 1: count=1, lessThan 3 → pending
        MatchEngine.MatchResult result = engine.match(
                Collections.singletonList(r), "http", "host", 80, null, "GET", "/order",
                Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), "");
        assertTrue(result.isMatched());
        assertEquals("pending", result.getResponse().getBody());
        assertEquals(1, result.getRequestCount());

        // Request 2: count=2, lessThan 3 → pending
        result = engine.match(
                Collections.singletonList(r), "http", "host", 80, null, "GET", "/order",
                Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), "");
        assertTrue(result.isMatched());
        assertEquals("pending", result.getResponse().getBody());
        assertEquals(2, result.getRequestCount());

        // Request 3: count=3, lessThan 3 → false → default "paid"
        result = engine.match(
                Collections.singletonList(r), "http", "host", 80, null, "GET", "/order",
                Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), "");
        assertTrue(result.isMatched());
        assertEquals("paid", result.getResponse().getBody());
        assertEquals(3, result.getRequestCount());
    }

    @Test
    public void testRequestCountEquals() {
        Rule r = createSimpleRule("stateful-equals");
        ResponseEntry specialResp = new ResponseEntry();
        specialResp.setBody("special");
        specialResp.setName("special");
        specialResp.setCondition(MatchCondition.requestCount("equals", "2"));

        ResponseEntry defaultResp = new ResponseEntry();
        defaultResp.setBody("default");
        defaultResp.setName("default");

        r.setResponses(Arrays.asList(specialResp, defaultResp));

        // Request 1: count=1, equals 2 → false → default
        MatchEngine.MatchResult result = engine.match(
                Collections.singletonList(r), "http", "host", 80, null, "GET", "/",
                Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), "");
        assertEquals("default", result.getResponse().getBody());

        // Request 2: count=2, equals 2 → true → special
        result = engine.match(
                Collections.singletonList(r), "http", "host", 80, null, "GET", "/",
                Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), "");
        assertEquals("special", result.getResponse().getBody());

        // Request 3: count=3, equals 2 → false → default
        result = engine.match(
                Collections.singletonList(r), "http", "host", 80, null, "GET", "/",
                Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), "");
        assertEquals("default", result.getResponse().getBody());
    }

    @Test
    public void testRequestCountGreaterThan() {
        Rule r = createSimpleRule("stateful-gt");
        ResponseEntry afterResp = new ResponseEntry();
        afterResp.setBody("after");
        afterResp.setName("after");
        afterResp.setCondition(MatchCondition.requestCount("greaterThan", "2"));

        ResponseEntry beforeResp = new ResponseEntry();
        beforeResp.setBody("before");
        beforeResp.setName("before");

        r.setResponses(Arrays.asList(afterResp, beforeResp));

        // Requests 1, 2: count ≤ 2 → before
        for (int i = 0; i < 2; i++) {
            MatchEngine.MatchResult result = engine.match(
                    Collections.singletonList(r), "http", "host", 80, null, "GET", "/",
                    Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), "");
            assertEquals("before", result.getResponse().getBody());
        }

        // Request 3: count=3 > 2 → after
        MatchEngine.MatchResult result = engine.match(
                Collections.singletonList(r), "http", "host", 80, null, "GET", "/",
                Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), "");
        assertEquals("after", result.getResponse().getBody());
    }

    @Test
    public void testRequestCountRange() {
        Rule r = createSimpleRule("stateful-range");
        ResponseEntry midResp = new ResponseEntry();
        midResp.setBody("mid");
        midResp.setName("mid");
        midResp.setCondition(MatchCondition.requestCount("range", "[2,4]"));

        ResponseEntry defaultResp = new ResponseEntry();
        defaultResp.setBody("default");
        defaultResp.setName("default");

        r.setResponses(Arrays.asList(midResp, defaultResp));

        // Request 1: count=1, not in [2,4] → default
        MatchEngine.MatchResult result = engine.match(
                Collections.singletonList(r), "http", "host", 80, null, "GET", "/",
                Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), "");
        assertEquals("default", result.getResponse().getBody());

        // Requests 2-4: in [2,4] → mid
        for (int i = 0; i < 3; i++) {
            result = engine.match(
                    Collections.singletonList(r), "http", "host", 80, null, "GET", "/",
                    Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), "");
            assertEquals("mid", result.getResponse().getBody());
        }

        // Request 5: count=5, not in [2,4] → default
        result = engine.match(
                Collections.singletonList(r), "http", "host", 80, null, "GET", "/",
                Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), "");
        assertEquals("default", result.getResponse().getBody());
    }

    @Test
    public void testRequestCountMod() {
        // Every 3rd request triggers special response
        Rule r = createSimpleRule("stateful-mod");
        ResponseEntry specialResp = new ResponseEntry();
        specialResp.setBody("every-third");
        specialResp.setName("every-third");
        specialResp.setCondition(MatchCondition.requestCount("mod", "0", "3"));

        ResponseEntry defaultResp = new ResponseEntry();
        defaultResp.setBody("normal");
        defaultResp.setName("normal");

        r.setResponses(Arrays.asList(specialResp, defaultResp));

        // Requests 1, 2: count%3 != 0 → normal
        for (int i = 0; i < 2; i++) {
            MatchEngine.MatchResult result = engine.match(
                    Collections.singletonList(r), "http", "host", 80, null, "GET", "/",
                    Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), "");
            assertEquals("normal", result.getResponse().getBody());
        }

        // Request 3: count=3, 3%3=0 → every-third
        MatchEngine.MatchResult result = engine.match(
                Collections.singletonList(r), "http", "host", 80, null, "GET", "/",
                Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), "");
        assertEquals("every-third", result.getResponse().getBody());

        // Requests 4, 5: normal
        for (int i = 0; i < 2; i++) {
            result = engine.match(
                    Collections.singletonList(r), "http", "host", 80, null, "GET", "/",
                    Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), "");
            assertEquals("normal", result.getResponse().getBody());
        }

        // Request 6: count=6, 6%3=0 → every-third
        result = engine.match(
                Collections.singletonList(r), "http", "host", 80, null, "GET", "/",
                Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), "");
        assertEquals("every-third", result.getResponse().getBody());
    }

    @Test
    public void testRequestCountResetThreshold() {
        // requestCountReset=3: counter resets after count reaches 3
        Rule r = createSimpleRule("stateful-reset");
        r.setRequestCountReset(3);

        ResponseEntry firstResp = new ResponseEntry();
        firstResp.setBody("first");
        firstResp.setName("first");
        firstResp.setCondition(MatchCondition.requestCount("equals", "1"));

        ResponseEntry otherResp = new ResponseEntry();
        otherResp.setBody("other");
        otherResp.setName("other");

        r.setResponses(Arrays.asList(firstResp, otherResp));

        // Request 1: count=1 → first
        MatchEngine.MatchResult result = engine.match(
                Collections.singletonList(r), "http", "host", 80, null, "GET", "/",
                Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), "");
        assertEquals("first", result.getResponse().getBody());

        // Request 2: count=2 → other
        result = engine.match(
                Collections.singletonList(r), "http", "host", 80, null, "GET", "/",
                Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), "");
        assertEquals("other", result.getResponse().getBody());

        // Request 3: count=3 → other, then counter resets
        result = engine.match(
                Collections.singletonList(r), "http", "host", 80, null, "GET", "/",
                Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), "");
        assertEquals("other", result.getResponse().getBody());

        // Request 4: count=1 again (reset) → first
        result = engine.match(
                Collections.singletonList(r), "http", "host", 80, null, "GET", "/",
                Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), "");
        assertEquals("first", result.getResponse().getBody());
    }

    @Test
    public void testRequestCountManualReset() {
        Rule r = createSimpleRule("stateful-manual-reset");
        ResponseEntry firstResp = new ResponseEntry();
        firstResp.setBody("first");
        firstResp.setName("first");
        firstResp.setCondition(MatchCondition.requestCount("equals", "1"));

        ResponseEntry otherResp = new ResponseEntry();
        otherResp.setBody("other");
        otherResp.setName("other");

        r.setResponses(Arrays.asList(firstResp, otherResp));

        // Request 1: count=1 → first
        MatchEngine.MatchResult result = engine.match(
                Collections.singletonList(r), "http", "host", 80, null, "GET", "/",
                Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), "");
        assertEquals("first", result.getResponse().getBody());

        // Request 2: count=2 → other
        result = engine.match(
                Collections.singletonList(r), "http", "host", 80, null, "GET", "/",
                Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), "");
        assertEquals("other", result.getResponse().getBody());

        // Manual reset
        StatefulCounterStore.global().reset("stateful-manual-reset");

        // Request 3: count=1 again → first
        result = engine.match(
                Collections.singletonList(r), "http", "host", 80, null, "GET", "/",
                Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), "");
        assertEquals("first", result.getResponse().getBody());
    }

    @Test
    public void testRequestCountWithNoConditions() {
        // Rule with no rule-level conditions still increments counter
        Rule r = createSimpleRule("stateful-no-cond");
        ResponseEntry firstResp = new ResponseEntry();
        firstResp.setBody("first");
        firstResp.setName("first");
        firstResp.setCondition(MatchCondition.requestCount("equals", "1"));

        ResponseEntry otherResp = new ResponseEntry();
        otherResp.setBody("other");
        otherResp.setName("other");

        r.setResponses(Arrays.asList(firstResp, otherResp));
        // No rule-level conditions set

        MatchEngine.MatchResult result = engine.match(
                Collections.singletonList(r), "http", "host", 80, null, "GET", "/",
                Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), "");
        assertTrue(result.isMatched());
        assertEquals(1, result.getRequestCount());
        assertEquals("first", result.getResponse().getBody());
    }

    @Test
    public void testRequestCountInvalidValue() {
        Rule r = createSimpleRule("stateful-invalid");
        ResponseEntry resp = new ResponseEntry();
        resp.setBody("resp");
        resp.setName("resp");
        resp.setCondition(MatchCondition.requestCount("equals", "not-a-number"));

        r.setResponses(Arrays.asList(resp));

        // Invalid number → condition fails → falls through to default (first entry)
        MatchEngine.MatchResult result = engine.match(
                Collections.singletonList(r), "http", "host", 80, null, "GET", "/",
                Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), "");
        assertTrue(result.isMatched());
        // The conditioned entry didn't match, so it falls through to entry 0 (the same entry)
        assertEquals("resp", result.getResponse().getBody());
    }

    @Test
    public void testRequestCountUnknownOperator() {
        Rule r = createSimpleRule("stateful-unknown-op");
        ResponseEntry condResp = new ResponseEntry();
        condResp.setBody("cond");
        condResp.setName("cond");
        condResp.setCondition(MatchCondition.requestCount("unknownOp", "1"));

        ResponseEntry defaultResp = new ResponseEntry();
        defaultResp.setBody("default");
        defaultResp.setName("default");

        r.setResponses(Arrays.asList(condResp, defaultResp));

        MatchEngine.MatchResult result = engine.match(
                Collections.singletonList(r), "http", "host", 80, null, "GET", "/",
                Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), "");
        assertTrue(result.isMatched());
        // Unknown operator → condition fails → default
        assertEquals("default", result.getResponse().getBody());
    }

    private static Rule createSimpleRule(String id) {
        Rule r = new Rule();
        r.setId(id);
        r.setName(id);
        r.setEnabled(true);
        ResponseEntry defaultResp = new ResponseEntry();
        defaultResp.setBody("response-" + id);
        r.setResponses(Arrays.asList(defaultResp));
        return r;
    }

    /**
     * P2-5: A catastrophic-backtracking regex must not hang the matching
     * thread. The engine should time out and treat the match as a non-match.
     */
    @Test
    public void testRegexReDoSTimeout() {
        // Engine with a short 100ms timeout, and a counter store independent
        // of the global one for isolation.
        StatefulCounterStore store = new StatefulCounterStore();
        MatchEngine timedEngine = new MatchEngine(store, 100L);

        Rule r = createSimpleRule("redos");
        // Classic evil regex: (a+)+ against a long string of a's followed by a non-matching char
        r.setConditions(Collections.singletonList(
                MatchCondition.path("regex", "(a+)+b")));

        // Long enough to trigger the timeout path (>= 64 chars).
        char[] chars = new char[80];
        Arrays.fill(chars, 'a');
        String evilInput = new String(chars); // no trailing 'b' -> catastrophic backtracking

        long start = System.currentTimeMillis();
        MatchEngine.MatchResult result = timedEngine.match(
                Collections.singletonList(r), "http", "host", 80, null, "GET", evilInput,
                Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), "");
        long elapsed = System.currentTimeMillis() - start;

        assertFalse("ReDoS regex should time out and not match", result.isMatched());
        // Should return well under 1 second, not hang for minutes.
        assertTrue("Regex timeout should be enforced quickly (elapsed=" + elapsed + "ms)",
                elapsed < 1000L);
    }

    /**
     * P2-6: MatchEngine must accept an injected StatefulCounterStore instead
     * of always using the global singleton.
     */
    @Test
    public void testCounterStoreInjection() {
        StatefulCounterStore customStore = new StatefulCounterStore();
        MatchEngine injectedEngine = new MatchEngine(customStore, 100L);

        Rule r = createSimpleRule("injected-counter");
        ResponseEntry firstResp = new ResponseEntry();
        firstResp.setBody("first");
        firstResp.setCondition(MatchCondition.requestCount("equals", "1"));
        r.setResponses(Arrays.asList(firstResp));

        // Snapshot the global store's count for this rule before invoking the
        // injected engine. The global store should remain untouched.
        StatefulCounterStore.global().reset("injected-counter");
        int globalBefore = StatefulCounterStore.global().get("injected-counter");

        MatchEngine.MatchResult result = injectedEngine.match(
                Collections.singletonList(r), "http", "host", 80, null, "GET", "/",
                Collections.<String, String>emptyMap(), Collections.<String, String>emptyMap(), "");
        assertTrue(result.isMatched());
        assertEquals(1, result.getRequestCount());

        // The injected engine must NOT have touched the global counter store.
        int globalAfter = StatefulCounterStore.global().get("injected-counter");
        assertEquals("Injected engine must not touch the global counter store",
                globalBefore, globalAfter);
    }

    /**
     * P2-1: MatchRequest overload must produce the same result as the
     * positional-parameter overload.
     */
    @Test
    public void testMatchRequestOverloadMatchesPositional() {
        Rule r = createSimpleRule("matchrequest-overload");
        r.setConditions(Collections.singletonList(MatchCondition.path("equals", "/api/test")));

        List<Rule> rules = Collections.singletonList(r);
        Map<String, String> headers = Collections.<String, String>emptyMap();
        Map<String, String> queryParams = Collections.<String, String>emptyMap();

        // Positional call
        MatchEngine.MatchResult posResult = engine.match(
                rules, "http", "host", 80, null, "GET", "/api/test",
                null, headers, queryParams, "body");

        // MatchRequest call
        MatchRequest req = new MatchRequest("http", "host", 80)
                .setMethod("GET")
                .setPath("/api/test")
                .setHeaders(headers)
                .setQueryParams(queryParams)
                .setBody("body");
        MatchEngine.MatchResult reqResult = engine.match(rules, req);

        assertTrue("positional call should match", posResult.isMatched());
        assertTrue("MatchRequest call should match", reqResult.isMatched());
        assertEquals(posResult.getRule().getId(), reqResult.getRule().getId());
        assertEquals(posResult.getResponseIndex(), reqResult.getResponseIndex());
    }

    /**
     * P2-1: matchWithFallback with MatchRequest must retry with port=0 when
     * the original port doesn't match.
     */
    @Test
    public void testMatchRequestWithFallbackToPortZero() {
        // Rule that matches any port (port=0 in matchesTarget)
        Rule r = createSimpleRule("fallback-port-zero");
        r.setPort(0); // wildcard port

        List<Rule> rules = Collections.singletonList(r);
        MatchRequest req = MatchRequest.http("http", "host", 8080, "GET", "/x",
                Collections.<String, String>emptyMap(),
                Collections.<String, String>emptyMap(), "");

        MatchEngine.MatchResult result = engine.matchWithFallback(rules, req);
        assertTrue("Fallback to port=0 should match the wildcard rule", result.isMatched());
        assertEquals("Port should be restored after fallback", 8080, req.getPort());
    }
}
