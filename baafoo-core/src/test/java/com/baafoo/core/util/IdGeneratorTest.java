package com.baafoo.core.util;

import org.junit.Test;
import static org.junit.Assert.*;

public class IdGeneratorTest {

    @Test
    public void testUuid() {
        String id = IdGenerator.uuid();
        assertNotNull(id);
        assertEquals(16, id.length());
    }

    @Test
    public void testUuidUnique() {
        String id1 = IdGenerator.uuid();
        String id2 = IdGenerator.uuid();
        assertNotEquals(id1, id2);
    }

    @Test
    public void testSeq() {
        String id = IdGenerator.seq("rule");
        assertTrue(id.startsWith("rule-"));
        assertTrue(id.length() > "rule-".length());
    }

    @Test
    public void testSeqIncrements() {
        String id1 = IdGenerator.seq("r");
        String id2 = IdGenerator.seq("r");
        assertNotEquals(id1, id2);
    }

    @Test
    public void testTimestamp() {
        String ts = IdGenerator.timestamp();
        assertNotNull(ts);
        assertTrue(ts.matches("\\d+"));
    }
}
