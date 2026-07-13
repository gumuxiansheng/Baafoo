package com.baafoo.agent;

import com.baafoo.agent.state.ProtocolMapper;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public class AgentManifestTest {

    private int savedMode;

    @Before
    public void saveState() {
        savedMode = AgentManifest.currentMode;
    }

    @After
    public void restoreState() {
        AgentManifest.currentMode = savedMode;
    }

    @Test
    public void constants() {
        assertEquals(0, AgentManifest.MODE_STUB);
        assertEquals(1, AgentManifest.MODE_PASSTHROUGH);
        assertEquals(2, AgentManifest.MODE_RECORD);
        assertEquals(3, AgentManifest.MODE_RECORD_AND_STUB);
    }

    @Test
    public void defaultValues() {
        assertEquals("127.0.0.1", AgentManifest.serverHost);
        assertEquals(8084, AgentManifest.serverPort);
        assertEquals(9000, AgentManifest.httpPort);
        assertEquals(9001, AgentManifest.tcpPort);
        assertEquals(9002, AgentManifest.kafkaPort);
        assertEquals(9003, AgentManifest.pulsarPort);
        assertEquals(9004, AgentManifest.jmsPort);
        assertEquals(9005, AgentManifest.grpcPort);
        assertEquals("default", AgentManifest.environmentId);
    }

    @Test
    public void setServerHost() {
        AgentManifest.setServerHost("myhost");
        assertEquals("myhost", AgentManifest.serverHost);
        assertEquals("myhost", GlobalRouteState.SERVER_HOST);
        AgentManifest.setServerHost("127.0.0.1");
    }

    @Test
    public void setServerPort() {
        AgentManifest.setServerPort(9999);
        assertEquals(9999, AgentManifest.serverPort);
        assertEquals(9999, GlobalRouteState.SERVER_PORT);
        AgentManifest.setServerPort(8084);
    }

    @Test
    public void setHttpPort() {
        AgentManifest.setHttpPort(8000);
        assertEquals(8000, AgentManifest.httpPort);
        assertEquals(8000, GlobalRouteState.HTTP_PORT);
        AgentManifest.setHttpPort(9000);
    }

    @Test
    public void setTcpPort() {
        AgentManifest.setTcpPort(8001);
        assertEquals(8001, AgentManifest.tcpPort);
        assertEquals(8001, GlobalRouteState.TCP_PORT);
        AgentManifest.setTcpPort(9001);
    }

    @Test
    public void setKafkaPort() {
        AgentManifest.setKafkaPort(8002);
        assertEquals(8002, AgentManifest.kafkaPort);
        assertEquals(8002, GlobalRouteState.KAFKA_PORT);
        AgentManifest.setKafkaPort(9002);
    }

    @Test
    public void setPulsarPort() {
        AgentManifest.setPulsarPort(8003);
        assertEquals(8003, AgentManifest.pulsarPort);
        assertEquals(8003, GlobalRouteState.PULSAR_PORT);
        AgentManifest.setPulsarPort(9003);
    }

    @Test
    public void setJmsPort() {
        AgentManifest.setJmsPort(8004);
        assertEquals(8004, AgentManifest.jmsPort);
        assertEquals(8004, GlobalRouteState.JMS_PORT);
        AgentManifest.setJmsPort(9004);
    }

    @Test
    public void setGrpcPort() {
        AgentManifest.setGrpcPort(8005);
        assertEquals(8005, AgentManifest.grpcPort);
        assertEquals(8005, GlobalRouteState.GRPC_PORT);
        AgentManifest.setGrpcPort(9005);
    }

    @Test
    public void setCurrentMode() {
        AgentManifest.setCurrentMode(AgentManifest.MODE_RECORD);
        assertEquals(AgentManifest.MODE_RECORD, AgentManifest.currentMode);
        assertEquals(AgentManifest.MODE_RECORD, GlobalRouteState.CURRENT_MODE);
        AgentManifest.setCurrentMode(AgentManifest.MODE_STUB);
    }

    @Test
    public void isPassthroughTrue() {
        AgentManifest.setCurrentMode(AgentManifest.MODE_PASSTHROUGH);
        assertTrue(AgentManifest.isPassthrough());
    }

    @Test
    public void isPassthroughFalse() {
        AgentManifest.setCurrentMode(AgentManifest.MODE_STUB);
        assertFalse(AgentManifest.isPassthrough());
    }

    @Test
    public void isRecordingTrueForRecord() {
        AgentManifest.setCurrentMode(AgentManifest.MODE_RECORD);
        assertTrue(AgentManifest.isRecording());
    }

    @Test
    public void isRecordingTrueForRecordAndStub() {
        AgentManifest.setCurrentMode(AgentManifest.MODE_RECORD_AND_STUB);
        assertTrue(AgentManifest.isRecording());
    }

    @Test
    public void isRecordingFalseForStub() {
        AgentManifest.setCurrentMode(AgentManifest.MODE_STUB);
        assertFalse(AgentManifest.isRecording());
    }

    @Test
    public void isRecordingFalseForPassthrough() {
        AgentManifest.setCurrentMode(AgentManifest.MODE_PASSTHROUGH);
        assertFalse(AgentManifest.isRecording());
    }

    @Test
    public void getModeNameStub() {
        AgentManifest.setCurrentMode(0);
        assertEquals("stub", AgentManifest.getModeName());
    }

    @Test
    public void getModeNamePassthrough() {
        AgentManifest.setCurrentMode(1);
        assertEquals("passthrough", AgentManifest.getModeName());
    }

    @Test
    public void getModeNameRecord() {
        AgentManifest.setCurrentMode(2);
        assertEquals("record", AgentManifest.getModeName());
    }

    @Test
    public void getModeNameRecordAndStub() {
        AgentManifest.setCurrentMode(3);
        assertEquals("record-and-stub", AgentManifest.getModeName());
    }

    @Test
    public void getModeNameUnknown() {
        AgentManifest.setCurrentMode(99);
        assertEquals("unknown", AgentManifest.getModeName());
    }

    @Test
    public void routeTableAtomicSwap() {
        RouteTable newTable = new RouteTable();
        AgentManifest.ROUTE_TABLE.set(newTable);
        assertSame(newTable, AgentManifest.ROUTE_TABLE.get());
    }
}
