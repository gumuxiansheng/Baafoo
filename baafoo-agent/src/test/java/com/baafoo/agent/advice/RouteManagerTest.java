package com.baafoo.agent.advice;

import com.baafoo.agent.AgentManifest;
import com.baafoo.agent.GlobalRouteState;
import com.baafoo.agent.RouteTable;
import com.baafoo.core.model.*;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.*;

import static org.junit.Assert.*;

public class RouteManagerTest {

    @Before
    public void setup() {
        RouteManager.updateRules(new ArrayList<Rule>());
        GlobalRouteState.SERVER_HOST = "127.0.0.1";
        GlobalRouteState.HTTP_PORT = 9000;
        GlobalRouteState.TCP_PORT = 9001;
        GlobalRouteState.KAFKA_PORT = 9002;
        GlobalRouteState.PULSAR_PORT = 9003;
        GlobalRouteState.JMS_PORT = 9004;
        GlobalRouteState.GRPC_PORT = 9005;
        AgentManifest.ROUTE_TABLE.set(new RouteTable());
    }

    @After
    public void teardown() {
        RouteManager.updateRules(new ArrayList<Rule>());
    }

    // --- RouteResult ---

    @Test
    public void routeResultDefaults() {
        RouteManager.RouteResult result = new RouteManager.RouteResult();
        assertNull(result.protocol);
        assertNull(result.host);
        assertEquals(0, result.port);
        assertNull(result.serviceName);
        assertNull(result.method);
        assertNull(result.path);
        assertFalse(result.matched);
        assertNull(result.rule);
        assertNull(result.responseEntry);
        assertEquals(0, result.responseIndex);
    }

    @Test
    public void routeResultSetAllFields() {
        RouteManager.RouteResult result = new RouteManager.RouteResult();
        result.protocol = "http";
        result.host = "example.com";
        result.port = 8084;
        result.serviceName = "my-service";
        result.method = "GET";
        result.path = "/api/test";
        result.matched = true;
        result.rule = new Rule();
        result.responseEntry = new ResponseEntry();
        result.responseIndex = 1;

        assertEquals("http", result.protocol);
        assertEquals("example.com", result.host);
        assertEquals(8084, result.port);
        assertEquals("my-service", result.serviceName);
        assertEquals("GET", result.method);
        assertEquals("/api/test", result.path);
        assertTrue(result.matched);
        assertNotNull(result.rule);
        assertNotNull(result.responseEntry);
        assertEquals(1, result.responseIndex);
    }

    // --- updateRules / getRules ---

    @Test
    public void updateRulesStoresSorted() {
        Rule r1 = new Rule();
        r1.setPriority(200);
        r1.setName("r1");
        Rule r2 = new Rule();
        r2.setPriority(100);
        r2.setName("r2");

        List<Rule> rules = Arrays.asList(r1, r2);
        RouteManager.updateRules(rules);

        List<Rule> stored = RouteManager.getRules();
        assertEquals(2, stored.size());
        assertEquals("r2", stored.get(0).getName());
        assertEquals("r1", stored.get(1).getName());
    }

    @Test
    public void getRulesReturnsCurrentList() {
        assertTrue(RouteManager.getRules().isEmpty());
    }

    // --- setMode / getMode ---

    @Test
    public void setModeStub() {
        RouteManager.setMode(EnvironmentMode.STUB);
        assertEquals(EnvironmentMode.STUB, RouteManager.getMode());
        assertFalse(RouteManager.isRecording());
    }

    @Test
    public void setModePassthrough() {
        RouteManager.setMode(EnvironmentMode.PASSTHROUGH);
        assertEquals(EnvironmentMode.PASSTHROUGH, RouteManager.getMode());
        assertFalse(RouteManager.isRecording());
    }

    @Test
    public void setModeRecord() {
        RouteManager.setMode(EnvironmentMode.RECORD);
        assertEquals(EnvironmentMode.RECORD, RouteManager.getMode());
        assertTrue(RouteManager.isRecording());
    }

    @Test
    public void setModeRecordAndStub() {
        RouteManager.setMode(EnvironmentMode.RECORD_AND_STUB);
        assertEquals(EnvironmentMode.RECORD_AND_STUB, RouteManager.getMode());
        assertTrue(RouteManager.isRecording());
    }

    @Test
    public void setModeRecordAll() {
        RouteManager.setMode(EnvironmentMode.RECORD_ALL);
        assertEquals(EnvironmentMode.RECORD_ALL, RouteManager.getMode());
        assertTrue(RouteManager.isRecording());
    }

    // --- hasProtocolRoutes ---

    @Test
    public void hasProtocolRoutesNullReturnsFalse() {
        assertFalse(RouteManager.hasProtocolRoutes(null));
    }

    @Test
    public void hasProtocolRoutesFalseWhenEmpty() {
        assertFalse(RouteManager.hasProtocolRoutes("kafka"));
    }

    @Test
    public void hasProtocolRoutesTrueWhenEnabledRuleExists() {
        Rule r = new Rule();
        r.setEnabled(true);
        r.setProtocol("kafka");
        r.setName("test");
        r.setHost("broker");
        r.setPort(9092);
        RouteManager.updateRules(Arrays.asList(r));

        assertTrue(RouteManager.hasProtocolRoutes("kafka"));
    }

    @Test
    public void hasProtocolRoutesFalseWhenDisabled() {
        Rule r = new Rule();
        r.setEnabled(false);
        r.setProtocol("kafka");
        r.setName("test");
        RouteManager.updateRules(Arrays.asList(r));

        assertFalse(RouteManager.hasProtocolRoutes("kafka"));
    }

    @Test
    public void hasProtocolRoutesCaseInsensitive() {
        Rule r = new Rule();
        r.setEnabled(true);
        r.setProtocol("HTTP");
        r.setName("test");
        r.setHost("host");
        r.setPort(80);
        RouteManager.updateRules(Arrays.asList(r));

        assertTrue(RouteManager.hasProtocolRoutes("http"));
    }

    // --- route ---

    @Test
    public void routeNoMatchReturnsUnmatched() {
        RouteManager.RouteResult result = RouteManager.route(
                "http", "example.com", 80, null,
                "GET", "/api", null, null, null);

        assertFalse(result.matched);
        assertEquals("http", result.protocol);
        assertEquals("example.com", result.host);
        assertEquals(80, result.port);
    }

    @Test
    public void routeWithMatchedRule() {
        Rule r = new Rule();
        r.setEnabled(true);
        r.setName("test-rule");
        r.setProtocol("http");
        r.setHost("example.com");
        r.setPort(80);
        r.setPriority(100);
        ResponseEntry entry = new ResponseEntry();
        entry.setName("resp1");
        r.setResponses(Arrays.asList(entry));
        RouteManager.updateRules(Arrays.asList(r));

        RouteManager.RouteResult result = RouteManager.route(
                "http", "example.com", 80, null,
                "GET", "/api/test", null, null, null);

        assertTrue(result.matched);
        assertEquals("test-rule", result.rule.getName());
    }

    // --- getStubPort (via route internals) ---

    @Test
    public void routeForHttpProtocolUsesHttpPort() {
        Rule r = new Rule();
        r.setEnabled(true);
        r.setName("http-rule");
        r.setProtocol("http");
        r.setHost("myhost.com");
        r.setPort(80);
        r.setPriority(100);
        RouteManager.updateRules(Arrays.asList(r));

        assertTrue(GlobalRouteState.ROUTES.containsKey("myhost.com:80"));
        GlobalRouteState.HostPort hp = GlobalRouteState.ROUTES.get("myhost.com:80");
        assertEquals(GlobalRouteState.HTTP_PORT, hp.port);
    }

    @Test
    public void routeForKafkaProtocolUsesKafkaPort() {
        Rule r = new Rule();
        r.setEnabled(true);
        r.setName("kafka-rule");
        r.setProtocol("kafka");
        r.setHost("broker");
        r.setPort(9092);
        r.setPriority(100);
        RouteManager.updateRules(Arrays.asList(r));

        GlobalRouteState.HostPort hp = GlobalRouteState.ROUTES.get("broker:9092");
        assertEquals(GlobalRouteState.KAFKA_PORT, hp.port);
    }

    @Test
    public void routeForGrpcProtocolUsesGrpcPort() {
        Rule r = new Rule();
        r.setEnabled(true);
        r.setName("grpc-rule");
        r.setProtocol("grpc");
        r.setHost("grpc-server");
        r.setPort(50051);
        r.setPriority(100);
        RouteManager.updateRules(Arrays.asList(r));

        GlobalRouteState.HostPort hp = GlobalRouteState.ROUTES.get("grpc-server:50051");
        assertEquals(GlobalRouteState.GRPC_PORT, hp.port);
    }

    @Test
    public void routeServiceNameEntry() {
        Rule r = new Rule();
        r.setEnabled(true);
        r.setName("svc-rule");
        r.setProtocol("http");
        r.setServiceName("my-service");
        r.setPriority(100);
        RouteManager.updateRules(Arrays.asList(r));

        assertTrue(GlobalRouteState.ROUTES.containsKey("svc:my-service"));
    }

    @Test
    public void routeDisabledRuleSkipped() {
        Rule r = new Rule();
        r.setEnabled(false);
        r.setName("disabled");
        r.setProtocol("http");
        r.setHost("host");
        r.setPort(80);
        RouteManager.updateRules(Arrays.asList(r));

        assertTrue(GlobalRouteState.ROUTES.isEmpty());
    }

    @Test
    public void routeHostOnlyNoPort() {
        Rule r = new Rule();
        r.setEnabled(true);
        r.setName("host-only");
        r.setProtocol("tcp");
        r.setHost("myhost");
        r.setPriority(100);
        RouteManager.updateRules(Arrays.asList(r));

        assertTrue(GlobalRouteState.ROUTES.containsKey("myhost"));
    }
}
