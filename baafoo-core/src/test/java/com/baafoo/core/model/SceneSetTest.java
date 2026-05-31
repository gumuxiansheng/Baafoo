package com.baafoo.core.model;

import org.junit.Test;
import java.util.Arrays;
import static org.junit.Assert.*;

public class SceneSetTest {

    @Test
    public void testDefaults() {
        SceneSet s = new SceneSet();
        assertTrue(s.getItemIds().isEmpty());
        assertTrue(s.getTags().isEmpty());
        assertTrue(s.getEnvironments().isEmpty());
        assertFalse(s.isActive());
    }

    @Test
    public void testGettersAndSetters() {
        SceneSet s = new SceneSet();
        s.setId("scene-1");
        s.setName("test-scenario");
        s.setDescription("test description");
        s.setItemIds(Arrays.asList("r1", "r2"));
        s.setActive(true);
        s.setTags(Arrays.asList("smoke"));
        s.setEnvironments(Arrays.asList("dev"));
        s.setCreatedAt(100L);
        s.setUpdatedAt(200L);

        assertEquals("scene-1", s.getId());
        assertEquals("test-scenario", s.getName());
        assertEquals("test description", s.getDescription());
        assertEquals(2, s.getItemIds().size());
        assertTrue(s.isActive());
        assertEquals(1, s.getTags().size());
        assertEquals(1, s.getEnvironments().size());
        assertEquals(100L, s.getCreatedAt());
        assertEquals(200L, s.getUpdatedAt());
    }

    @Test
    public void testToString() {
        SceneSet s = new SceneSet();
        s.setId("sc1");
        s.setName("scenario1");
        assertTrue(s.toString().contains("sc1"));
    }
}
