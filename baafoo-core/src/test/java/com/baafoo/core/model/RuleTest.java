package com.baafoo.core.model;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class RuleTest {

    @Test
    public void testDefaults() {
        Rule r = new Rule();
        assertTrue(r.getConditions().isEmpty());
        assertTrue(r.getResponses().isEmpty());
        assertTrue(r.getTags().isEmpty());
        assertTrue(r.getEnvironments().isEmpty());
        assertTrue(r.getTcpRounds().isEmpty());
        assertFalse(r.isTcpLoop());
        assertNull(r.getTcpPattern());
        assertNull(r.getTcpPrefixHex());
        assertEquals(-1, r.getTcpOffsetStart());
        assertEquals(-1, r.getTcpOffsetEnd());
        assertNull(r.getTcpOffsetHex());
        assertTrue(r.isEnabled());
        assertEquals(100, r.getPriority());
        assertEquals(1, r.getVersion());
    }

    @Test
    public void testGettersAndSetters() {
        Rule r = new Rule();
        r.setId("rule-1");
        r.setName("test rule");
        r.setProtocol("http");
        r.setServiceName("svc");
        r.setHost("api.test.com");
        r.setPort(8084);
        r.setEnabled(false);
        r.setPriority(50);
        r.setTags(Arrays.asList("tag1", "tag2"));
        r.setEnvironments(Arrays.asList("dev", "staging"));
        r.setVersion(2);
        r.setCreatedAt(1000L);
        r.setUpdatedAt(2000L);

        MatchCondition mc = MatchCondition.method("GET");
        r.setConditions(Arrays.asList(mc));

        ResponseEntry re = new ResponseEntry();
        re.setBody("{\"ok\":true}");
        r.setResponses(Arrays.asList(re));

        assertEquals("rule-1", r.getId());
        assertEquals("test rule", r.getName());
        assertEquals("http", r.getProtocol());
        assertEquals("svc", r.getServiceName());
        assertEquals("api.test.com", r.getHost());
        assertEquals(Integer.valueOf(8084), r.getPort());
        assertFalse(r.isEnabled());
        assertEquals(50, r.getPriority());
        assertEquals(2, r.getTags().size());
        assertEquals(2, r.getEnvironments().size());
        assertEquals(2, r.getVersion());
        assertEquals(1000L, r.getCreatedAt());
        assertEquals(2000L, r.getUpdatedAt());
        assertEquals(1, r.getConditions().size());
        assertEquals(1, r.getResponses().size());
    }

    @Test
    public void testTcpFieldsGettersAndSetters() {
        Rule r = new Rule();

        TcpRound round1 = new TcpRound();
        round1.setName("handshake");
        round1.setPattern("68656c6c6f");
        round1.setPrefixHex("6865");
        round1.setOffsetStart(0);
        round1.setOffsetEnd(2);
        round1.setOffsetHex("0001");
        ResponseEntry resp1 = new ResponseEntry();
        resp1.setBody("ok");
        round1.setResponse(resp1);

        TcpRound round2 = new TcpRound();
        round2.setName("data");

        r.setTcpRounds(Arrays.asList(round1, round2));
        r.setTcpLoop(true);
        r.setTcpPattern("00.*");
        r.setTcpPrefixHex("0001");
        r.setTcpOffsetStart(4);
        r.setTcpOffsetEnd(6);
        r.setTcpOffsetHex("0102");

        assertEquals(2, r.getTcpRounds().size());
        assertEquals("handshake", r.getTcpRounds().get(0).getName());
        assertEquals("68656c6c6f", r.getTcpRounds().get(0).getPattern());
        assertEquals("6865", r.getTcpRounds().get(0).getPrefixHex());
        assertEquals(0, r.getTcpRounds().get(0).getOffsetStart());
        assertEquals(2, r.getTcpRounds().get(0).getOffsetEnd());
        assertEquals("0001", r.getTcpRounds().get(0).getOffsetHex());
        assertNotNull(r.getTcpRounds().get(0).getResponse());
        assertTrue(r.isTcpLoop());
        assertEquals("00.*", r.getTcpPattern());
        assertEquals("0001", r.getTcpPrefixHex());
        assertEquals(4, r.getTcpOffsetStart());
        assertEquals(6, r.getTcpOffsetEnd());
        assertEquals("0102", r.getTcpOffsetHex());
    }

    @Test
    public void testToString() {
        Rule r = new Rule();
        r.setId("r1");
        r.setName("test");
        assertTrue(r.toString().contains("r1"));
    }
}
