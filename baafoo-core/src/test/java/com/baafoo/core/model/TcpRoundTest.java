package com.baafoo.core.model;

import org.junit.Test;

import static org.junit.Assert.*;

public class TcpRoundTest {

    @Test
    public void testDefaults() {
        TcpRound round = new TcpRound();
        assertNull(round.getName());
        assertNull(round.getPattern());
        assertNull(round.getPrefixHex());
        assertEquals(-1, round.getOffsetStart());
        assertEquals(-1, round.getOffsetEnd());
        assertNull(round.getOffsetHex());
        assertTrue(round.getConditions().isEmpty());
        assertNull(round.getResponse());
    }

    @Test
    public void testGettersAndSetters() {
        TcpRound round = new TcpRound();
        round.setName("handshake");
        round.setPattern("68656c6c6f");
        round.setPrefixHex("6865");
        round.setOffsetStart(0);
        round.setOffsetEnd(2);
        round.setOffsetHex("0001");

        ResponseEntry resp = new ResponseEntry();
        resp.setBody("ok");
        round.setResponse(resp);

        assertEquals("handshake", round.getName());
        assertEquals("68656c6c6f", round.getPattern());
        assertEquals("6865", round.getPrefixHex());
        assertEquals(0, round.getOffsetStart());
        assertEquals(2, round.getOffsetEnd());
        assertEquals("0001", round.getOffsetHex());
        assertNotNull(round.getResponse());
        assertEquals("ok", round.getResponse().getBody());
    }

    @Test
    public void testToString() {
        TcpRound round = new TcpRound();
        round.setName("test-round");
        round.setPattern("ab.*");
        assertTrue(round.toString().contains("test-round"));
        assertTrue(round.toString().contains("ab.*"));
    }
}
