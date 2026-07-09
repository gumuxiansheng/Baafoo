package com.baafoo.core.model;

import org.junit.Test;

import static org.junit.Assert.*;

public class MqRelationshipTest {

    @Test
    public void testDefaultConstructor() {
        MqRelationship r = new MqRelationship();
        assertEquals("kafka", r.getFromProtocol());
        assertEquals("kafka", r.getToProtocol());
        assertTrue(r.isEnabled());
    }

    @Test
    public void testSetAndGetId() {
        MqRelationship r = new MqRelationship();
        r.setId("rel-1");
        assertEquals("rel-1", r.getId());
    }

    @Test
    public void testSetAndGetName() {
        MqRelationship r = new MqRelationship();
        r.setName("kafka-to-pulsar");
        assertEquals("kafka-to-pulsar", r.getName());
    }

    @Test
    public void testSetAndGetFromProtocol() {
        MqRelationship r = new MqRelationship();
        r.setFromProtocol("pulsar");
        assertEquals("pulsar", r.getFromProtocol());
    }

    @Test
    public void testSetAndGetFromTopic() {
        MqRelationship r = new MqRelationship();
        r.setFromTopic("orders");
        assertEquals("orders", r.getFromTopic());
    }

    @Test
    public void testSetAndGetToProtocol() {
        MqRelationship r = new MqRelationship();
        r.setToProtocol("kafka");
        assertEquals("kafka", r.getToProtocol());
    }

    @Test
    public void testSetAndGetToTopic() {
        MqRelationship r = new MqRelationship();
        r.setToTopic("orders-processed");
        assertEquals("orders-processed", r.getToTopic());
    }

    @Test
    public void testSetAndGetKeyTemplate() {
        MqRelationship r = new MqRelationship();
        r.setKeyTemplate("{{request.body.id}}");
        assertEquals("{{request.body.id}}", r.getKeyTemplate());
    }

    @Test
    public void testSetAndGetValueTemplate() {
        MqRelationship r = new MqRelationship();
        r.setValueTemplate("{{request.body}}");
        assertEquals("{{request.body}}", r.getValueTemplate());
    }

    @Test
    public void testSetAndGetDelayMs() {
        MqRelationship r = new MqRelationship();
        r.setDelayMs(5000L);
        assertEquals(5000L, r.getDelayMs());
    }

    @Test
    public void testSetAndGetEnabled() {
        MqRelationship r = new MqRelationship();
        assertTrue(r.isEnabled());
        r.setEnabled(false);
        assertFalse(r.isEnabled());
        r.setEnabled(true);
        assertTrue(r.isEnabled());
    }

    @Test
    public void testSetAndGetCreatedAt() {
        MqRelationship r = new MqRelationship();
        r.setCreatedAt(1700000000000L);
        assertEquals(1700000000000L, r.getCreatedAt());
    }

    @Test
    public void testSetAndGetUpdatedAt() {
        MqRelationship r = new MqRelationship();
        r.setUpdatedAt(1700000000000L);
        assertEquals(1700000000000L, r.getUpdatedAt());
    }

    @Test
    public void testToString() {
        MqRelationship r = new MqRelationship();
        r.setId("rel-1");
        r.setFromTopic("orders");
        r.setToTopic("processed");
        r.setEnabled(true);
        String s = r.toString();
        assertTrue(s.contains("rel-1"));
        assertTrue(s.contains("kafka:orders"));
        assertTrue(s.contains("kafka:processed"));
        assertTrue(s.contains("enabled=true"));
    }

    @Test
    public void testNullFieldsByDefault() {
        MqRelationship r = new MqRelationship();
        assertNull(r.getId());
        assertNull(r.getName());
        assertNull(r.getFromTopic());
        assertNull(r.getToTopic());
        assertNull(r.getKeyTemplate());
        assertNull(r.getValueTemplate());
    }
}
