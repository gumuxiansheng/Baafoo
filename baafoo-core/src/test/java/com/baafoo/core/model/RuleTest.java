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
        r.setPort(8080);
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
        assertEquals(Integer.valueOf(8080), r.getPort());
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
    public void testToString() {
        Rule r = new Rule();
        r.setId("r1");
        r.setName("test");
        assertTrue(r.toString().contains("r1"));
    }
}
