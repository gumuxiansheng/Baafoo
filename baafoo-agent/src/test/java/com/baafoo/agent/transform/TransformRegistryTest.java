package com.baafoo.agent.transform;

import org.junit.Test;
import static org.junit.Assert.*;

public class TransformRegistryTest {

    @Test
    public void testRegisterAndGetTransforms() {
        TransformRegistry registry = new TransformRegistry();
        registry.register("com.example.Target", "com.example.Advice", "http");
        assertEquals(1, registry.getCount());
        assertEquals(1, registry.getTransforms().size());
    }

    @Test
    public void testGetCount() {
        TransformRegistry registry = new TransformRegistry();
        assertEquals(0, registry.getCount());
        registry.register("A", "B", "tcp");
        assertEquals(1, registry.getCount());
        registry.register("C", "D", "kafka");
        assertEquals(2, registry.getCount());
    }

    @Test
    public void testGetTransformsReturnsUnmodifiable() {
        TransformRegistry registry = new TransformRegistry();
        registry.register("T", "A", "p");
        try {
            registry.getTransforms().add(null);
            fail("Should be unmodifiable");
        } catch (UnsupportedOperationException e) {
        }
    }

    @Test
    public void testTransformEntryGetters() {
        TransformRegistry.TransformEntry entry = new TransformRegistry.TransformEntry(
                "com.example.Target", "com.example.Advice", "http");
        assertEquals("com.example.Target", entry.getTargetClass());
        assertEquals("com.example.Advice", entry.getAdviceClass());
        assertEquals("http", entry.getProtocol());
    }

    @Test
    public void testTransformEntryNullValues() {
        TransformRegistry.TransformEntry entry = new TransformRegistry.TransformEntry(null, null, null);
        assertNull(entry.getTargetClass());
        assertNull(entry.getAdviceClass());
        assertNull(entry.getProtocol());
    }

    @Test
    public void testTransformEntryEmptyValues() {
        TransformRegistry.TransformEntry entry = new TransformRegistry.TransformEntry("", "", "");
        assertEquals("", entry.getTargetClass());
        assertEquals("", entry.getAdviceClass());
        assertEquals("", entry.getProtocol());
    }
}
