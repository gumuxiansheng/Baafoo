package com.baafoo.agent.state;

import com.baafoo.agent.GlobalRouteState;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import static org.junit.Assert.*;

public class ProtocolMapperTest {

    private final ProtocolMapper mapper = new ProtocolMapper();

    @Before
    public void saveState() {
        GlobalRouteState.CURRENT_MODE = GlobalRouteState.MODE_STUB;
    }

    @After
    public void restoreState() {
        GlobalRouteState.CURRENT_MODE = GlobalRouteState.MODE_STUB;
    }

    // --- isInternal ---

    @Test
    public void isInternalRecognizesLocalhost() {
        assertTrue(mapper.isInternal("localhost", GlobalRouteState.HTTP_PORT));
    }

    @Test
    public void isInternalRecognizes127001() {
        assertTrue(mapper.isInternal("127.0.0.1", GlobalRouteState.TCP_PORT));
    }

    @Test
    public void isInternalRecognizesServerHost() {
        String saved = GlobalRouteState.SERVER_HOST;
        try {
            GlobalRouteState.SERVER_HOST = "myserver";
            assertTrue(mapper.isInternal("myserver", GlobalRouteState.KAFKA_PORT));
        } finally {
            GlobalRouteState.SERVER_HOST = saved;
        }
    }

    @Test
    public void isInternalRecognizesServerHostIp() {
        String savedIp = GlobalRouteState.SERVER_HOST_IP;
        try {
            GlobalRouteState.SERVER_HOST_IP = "10.0.0.1";
            assertTrue(mapper.isInternal("10.0.0.1", GlobalRouteState.PULSAR_PORT));
        } finally {
            GlobalRouteState.SERVER_HOST_IP = savedIp;
        }
    }

    @Test
    public void isInternalRejectsUnknownHost() {
        assertFalse(mapper.isInternal("external.com", GlobalRouteState.HTTP_PORT));
    }

    @Test
    public void isInternalRejectsUnknownPort() {
        assertFalse(mapper.isInternal("127.0.0.1", 12345));
    }

    @Test
    public void isInternalRecognizesServerPort() {
        assertTrue(mapper.isInternal("127.0.0.1", GlobalRouteState.SERVER_PORT));
    }

    @Test
    public void isInternalRecognizesGrpcPort() {
        assertTrue(mapper.isInternal("127.0.0.1", GlobalRouteState.GRPC_PORT));
    }

    @Test
    public void isInternalRecognizesJmsPort() {
        assertTrue(mapper.isInternal("127.0.0.1", GlobalRouteState.JMS_PORT));
    }

    // --- forceRedirectPort ---

    @Test
    public void forceRedirectHttp() {
        assertEquals(GlobalRouteState.HTTP_PORT, mapper.forceRedirectPort(80));
        assertEquals(GlobalRouteState.HTTP_PORT, mapper.forceRedirectPort(443));
        assertEquals(GlobalRouteState.HTTP_PORT, mapper.forceRedirectPort(8080));
        assertEquals(GlobalRouteState.HTTP_PORT, mapper.forceRedirectPort(8443));
    }

    @Test
    public void forceRedirectGrpc() {
        assertEquals(GlobalRouteState.GRPC_PORT, mapper.forceRedirectPort(50051));
        assertEquals(GlobalRouteState.GRPC_PORT, mapper.forceRedirectPort(50052));
        assertEquals(GlobalRouteState.GRPC_PORT, mapper.forceRedirectPort(9090));
    }

    @Test
    public void forceRedirectKafka() {
        assertEquals(GlobalRouteState.KAFKA_PORT, mapper.forceRedirectPort(9092));
        assertEquals(GlobalRouteState.KAFKA_PORT, mapper.forceRedirectPort(9093));
        assertEquals(GlobalRouteState.KAFKA_PORT, mapper.forceRedirectPort(9094));
    }

    @Test
    public void forceRedirectPulsar() {
        assertEquals(GlobalRouteState.PULSAR_PORT, mapper.forceRedirectPort(6650));
        assertEquals(GlobalRouteState.PULSAR_PORT, mapper.forceRedirectPort(6651));
    }

    @Test
    public void forceRedirectJms() {
        assertEquals(GlobalRouteState.JMS_PORT, mapper.forceRedirectPort(61616));
    }

    @Test
    public void forceRedirectDefaultTcp() {
        assertEquals(GlobalRouteState.TCP_PORT, mapper.forceRedirectPort(12345));
    }

    // --- shouldIntercept ---

    @Test
    public void shouldInterceptTrueForStub() {
        assertTrue(mapper.shouldIntercept(GlobalRouteState.MODE_STUB));
    }

    @Test
    public void shouldInterceptFalseForPassthrough() {
        assertFalse(mapper.shouldIntercept(GlobalRouteState.MODE_PASSTHROUGH));
    }

    @Test
    public void shouldInterceptTrueForRecord() {
        assertTrue(mapper.shouldIntercept(GlobalRouteState.MODE_RECORD));
    }

    @Test
    public void shouldInterceptTrueForRecordAndStub() {
        assertTrue(mapper.shouldIntercept(GlobalRouteState.MODE_RECORD_AND_STUB));
    }

    @Test
    public void shouldInterceptTrueForRecordAll() {
        assertTrue(mapper.shouldIntercept(GlobalRouteState.MODE_RECORD_ALL));
    }

    // --- shouldRecordStream ---

    @Test
    public void shouldRecordStreamFalseForStub() {
        assertFalse(mapper.shouldRecordStream(GlobalRouteState.MODE_STUB));
    }

    @Test
    public void shouldRecordStreamFalseForPassthrough() {
        assertFalse(mapper.shouldRecordStream(GlobalRouteState.MODE_PASSTHROUGH));
    }

    @Test
    public void shouldRecordStreamTrueForRecord() {
        assertTrue(mapper.shouldRecordStream(GlobalRouteState.MODE_RECORD));
    }

    @Test
    public void shouldRecordStreamTrueForRecordAndStub() {
        assertTrue(mapper.shouldRecordStream(GlobalRouteState.MODE_RECORD_AND_STUB));
    }

    @Test
    public void shouldRecordStreamTrueForRecordAll() {
        assertTrue(mapper.shouldRecordStream(GlobalRouteState.MODE_RECORD_ALL));
    }

    // --- isRecordAndStub ---

    @Test
    public void isRecordAndStubTrue() {
        assertTrue(mapper.isRecordAndStub(GlobalRouteState.MODE_RECORD_AND_STUB));
    }

    @Test
    public void isRecordAndStubFalseForOther() {
        assertFalse(mapper.isRecordAndStub(GlobalRouteState.MODE_STUB));
        assertFalse(mapper.isRecordAndStub(GlobalRouteState.MODE_RECORD));
    }

    // --- shouldRedirectUnmatched ---

    @Test
    public void shouldRedirectUnmatchedTrueForRecordAll() {
        assertTrue(mapper.shouldRedirectUnmatched(GlobalRouteState.MODE_RECORD_ALL));
    }

    @Test
    public void shouldRedirectUnmatchedFalseForOthers() {
        assertFalse(mapper.shouldRedirectUnmatched(GlobalRouteState.MODE_STUB));
        assertFalse(mapper.shouldRedirectUnmatched(GlobalRouteState.MODE_RECORD_AND_STUB));
    }

    // --- isPassthrough / isRecording ---

    @Test
    public void isPassthroughTrue() {
        GlobalRouteState.CURRENT_MODE = GlobalRouteState.MODE_PASSTHROUGH;
        assertTrue(mapper.isPassthrough());
    }

    @Test
    public void isPassthroughFalse() {
        GlobalRouteState.CURRENT_MODE = GlobalRouteState.MODE_STUB;
        assertFalse(mapper.isPassthrough());
    }

    @Test
    public void isRecordingTrueForRecord() {
        GlobalRouteState.CURRENT_MODE = GlobalRouteState.MODE_RECORD;
        assertTrue(mapper.isRecording());
    }

    @Test
    public void isRecordingTrueForRecordAndStub() {
        GlobalRouteState.CURRENT_MODE = GlobalRouteState.MODE_RECORD_AND_STUB;
        assertTrue(mapper.isRecording());
    }

    @Test
    public void isRecordingTrueForRecordAll() {
        GlobalRouteState.CURRENT_MODE = GlobalRouteState.MODE_RECORD_ALL;
        assertTrue(mapper.isRecording());
    }

    @Test
    public void isRecordingFalseForStub() {
        GlobalRouteState.CURRENT_MODE = GlobalRouteState.MODE_STUB;
        assertFalse(mapper.isRecording());
    }

    @Test
    public void isRecordingFalseForPassthrough() {
        GlobalRouteState.CURRENT_MODE = GlobalRouteState.MODE_PASSTHROUGH;
        assertFalse(mapper.isRecording());
    }

    // --- inferProtocol ---

    @Test
    public void inferProtocolHttpPort() {
        assertEquals("http", mapper.inferProtocol("127.0.0.1", GlobalRouteState.HTTP_PORT));
    }

    @Test
    public void inferProtocolTcpPort() {
        assertEquals("tcp", mapper.inferProtocol("127.0.0.1", GlobalRouteState.TCP_PORT));
    }

    @Test
    public void inferProtocolKafkaPort() {
        assertEquals("kafka", mapper.inferProtocol("127.0.0.1", GlobalRouteState.KAFKA_PORT));
    }

    @Test
    public void inferProtocolPulsarPort() {
        assertEquals("pulsar", mapper.inferProtocol("127.0.0.1", GlobalRouteState.PULSAR_PORT));
    }

    @Test
    public void inferProtocolJmsPort() {
        assertEquals("jms", mapper.inferProtocol("127.0.0.1", GlobalRouteState.JMS_PORT));
    }

    @Test
    public void inferProtocolGrpcPort() {
        assertEquals("grpc", mapper.inferProtocol("127.0.0.1", GlobalRouteState.GRPC_PORT));
    }

    @Test
    public void inferProtocolUnknownReturnsTcp() {
        assertEquals("tcp", mapper.inferProtocol("external.com", 12345));
    }
}
