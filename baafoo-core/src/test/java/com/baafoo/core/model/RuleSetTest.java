package com.baafoo.core.model;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class RuleSetTest {

    @Test
    public void testDefaults() {
        RuleSet rs = new RuleSet();
        assertTrue(rs.getRuleIds().isEmpty());
        assertTrue(rs.getTags().isEmpty());
        assertTrue(rs.isEnabled());
    }

    @Test
    public void testGettersAndSetters() {
        RuleSet rs = new RuleSet();
        rs.setId("rs-1");
        rs.setName("my-set");
        rs.setDescription("desc");
        rs.setRuleIds(Arrays.asList("r1", "r2"));
        rs.setEnabled(false);
        rs.setTags(Arrays.asList("t1"));
        rs.setCreatedAt(100L);
        rs.setUpdatedAt(200L);

        assertEquals("rs-1", rs.getId());
        assertEquals("my-set", rs.getName());
        assertEquals("desc", rs.getDescription());
        assertEquals(2, rs.getRuleIds().size());
        assertFalse(rs.isEnabled());
        assertEquals(1, rs.getTags().size());
        assertEquals(100L, rs.getCreatedAt());
        assertEquals(200L, rs.getUpdatedAt());
    }

    @Test
    public void testToString() {
        RuleSet rs = new RuleSet();
        rs.setId("s1");
        rs.setName("set1");
        assertTrue(rs.toString().contains("s1"));
    }
}
