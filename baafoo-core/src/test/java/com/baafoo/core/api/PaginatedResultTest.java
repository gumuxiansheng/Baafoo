package com.baafoo.core.api;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

public class PaginatedResultTest {

    @Test
    public void testDefaultConstructor() {
        PaginatedResult<String> r = new PaginatedResult<>();
        assertEquals(0, r.getPage());
        assertEquals(0, r.getSize());
        assertEquals(0L, r.getTotal());
        assertEquals(0, r.getTotalPages());
        assertNull(r.getItems());
    }

    @Test
    public void testParameterizedConstructor() {
        List<String> items = Arrays.asList("a", "b", "c");
        PaginatedResult<String> r = new PaginatedResult<>(1, 10, 25, items);
        assertEquals(1, r.getPage());
        assertEquals(10, r.getSize());
        assertEquals(25L, r.getTotal());
        assertEquals(3, r.getTotalPages()); // ceil(25/10)=3
        assertEquals(items, r.getItems());
    }

    @Test
    public void testTotalPagesExactDivision() {
        PaginatedResult<String> r = new PaginatedResult<>(1, 5, 20, Collections.<String>emptyList());
        assertEquals(4, r.getTotalPages()); // 20/5=4
    }

    @Test
    public void testTotalPagesRoundsUp() {
        PaginatedResult<String> r = new PaginatedResult<>(1, 3, 10, Collections.<String>emptyList());
        assertEquals(4, r.getTotalPages()); // ceil(10/3)=4
    }

    @Test
    public void testTotalPagesZero() {
        PaginatedResult<String> r = new PaginatedResult<>(1, 10, 0, Collections.<String>emptyList());
        assertEquals(0, r.getTotalPages());
    }

    @Test
    public void testTotalPagesSizeZero() {
        PaginatedResult<String> r = new PaginatedResult<>(1, 0, 100, Collections.<String>emptyList());
        assertEquals(0, r.getTotalPages());
    }

    @Test
    public void testSetTotalRecalculatesTotalPages() {
        PaginatedResult<String> r = new PaginatedResult<>(1, 10, 0, Collections.<String>emptyList());
        assertEquals(0, r.getTotalPages());
        r.setTotal(25);
        assertEquals(3, r.getTotalPages());
    }

    @Test
    public void testSetTotalWithZeroSize() {
        PaginatedResult<String> r = new PaginatedResult<>();
        r.setSize(0);
        r.setTotal(100);
        // size=0 should not recalculate totalPages
        assertEquals(0, r.getTotalPages());
    }

    @Test
    public void testSetPage() {
        PaginatedResult<String> r = new PaginatedResult<>();
        r.setPage(5);
        assertEquals(5, r.getPage());
    }

    @Test
    public void testSetSize() {
        PaginatedResult<String> r = new PaginatedResult<>();
        r.setSize(20);
        assertEquals(20, r.getSize());
    }

    @Test
    public void testSetTotalPagesDirectly() {
        PaginatedResult<String> r = new PaginatedResult<>();
        r.setTotalPages(99);
        assertEquals(99, r.getTotalPages());
    }

    @Test
    public void testSetItems() {
        PaginatedResult<String> r = new PaginatedResult<>();
        List<String> items = Arrays.asList("x", "y");
        r.setItems(items);
        assertEquals(items, r.getItems());
    }

    @Test
    public void testSingleItemTotal() {
        PaginatedResult<String> r = new PaginatedResult<>(1, 10, 1, Arrays.asList("only"));
        assertEquals(1, r.getTotalPages());
    }

    @Test
    public void testLargeTotal() {
        PaginatedResult<String> r = new PaginatedResult<>(1, 100, 1_000_000, Collections.<String>emptyList());
        assertEquals(10000, r.getTotalPages());
    }
}
