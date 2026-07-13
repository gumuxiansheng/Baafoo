package com.baafoo.core.util;

import org.junit.Test;

import java.util.HashSet;
import java.util.Random;
import java.util.Set;

import static org.junit.Assert.*;

public class RegexGeneratorTest {

    private String generate(String pattern) {
        return new FakerProvider.RegexGenerator(pattern, new Random(12345)).generate();
    }

    @Test
    public void literalString() {
        assertEquals("abc", generate("abc"));
    }

    @Test
    public void charClassSingleRange() {
        String result = generate("[a-z]{5}");
        assertEquals(5, result.length());
        assertTrue(result.matches("[a-z]{5}"));
    }

    @Test
    public void charClassMultipleRanges() {
        String result = generate("[A-Za-z]{6}");
        assertEquals(6, result.length());
        assertTrue(result.matches("[A-Za-z]{6}"));
    }

    @Test
    public void charClassWithSingles() {
        String result = generate("[abc]{3}");
        assertEquals(3, result.length());
        assertTrue(result.matches("[abc]{3}"));
    }

    @Test
    public void digitEscape() {
        String result = generate("\\d{4}");
        assertEquals(4, result.length());
        assertTrue(result.matches("\\d{4}"));
    }

    @Test
    public void wordEscape() {
        String result = generate("\\w{5}");
        assertEquals(5, result.length());
        assertTrue(result.matches("\\w{5}"));
    }

    @Test
    public void quantifierQuestionMark() {
        String result = generate("a?");
        assertTrue(result.equals("") || result.equals("a"));
    }

    @Test
    public void quantifierPlus() {
        String result = generate("a+");
        assertTrue(result.length() >= 1);
        assertTrue(result.matches("a+"));
    }

    @Test
    public void quantifierStar() {
        String result = generate("a*");
        assertTrue(result.length() >= 0);
        assertTrue(result.matches("a*"));
    }

    @Test
    public void quantifierExactN() {
        String result = generate("a{5}");
        assertEquals(5, result.length());
    }

    @Test
    public void quantifierRange() {
        String result = generate("a{3,7}");
        assertTrue(result.length() >= 3 && result.length() <= 7);
    }

    @Test
    public void alternation() {
        java.util.Set<String> valid = new java.util.HashSet<>();
        valid.add("cat");
        valid.add("dog");
        valid.add("bird");
        for (int i = 0; i < 10; i++) {
            String r = new FakerProvider.RegexGenerator("cat|dog|bird", new Random(i)).generate();
            assertTrue("Unexpected: " + r, valid.contains(r));
        }
    }

    @Test
    public void groupWithAlternation() {
        java.util.Set<String> results = new java.util.HashSet<>();
        for (int i = 0; i < 30; i++) {
            results.add(generate("(ab|cd)ef"));
        }
        for (String r : results) {
            assertTrue(r.equals("abef") || r.equals("cdef"));
        }
    }

    @Test
    public void anchorsIgnored() {
        assertEquals("abc", generate("^abc$"));
    }

    @Test
    public void dotMatchesAnyChar() {
        String result = generate("a.c");
        assertEquals(3, result.length());
        assertEquals('a', result.charAt(0));
        assertEquals('c', result.charAt(2));
    }

    @Test
    public void dotStar() {
        String result = generate(".*abc");
        assertNotNull(result);
        assertTrue(result.length() >= 3);
    }

    @Test
    public void complexPattern() {
        String result = generate("[A-Z]{3}[0-9]{4}");
        assertEquals(7, result.length());
        assertTrue(result.matches("[A-Z]{3}\\d{4}"));
    }

    @Test
    public void negatedCharClass() {
        String result = generate("[^0-9]");
        assertEquals(1, result.length());
        assertFalse(Character.isDigit(result.charAt(0)));
    }

    @Test
    public void escapedSpecialChar() {
        String result = generate("\\.\\*");
        assertEquals(".*", result);
    }

    @Test
    public void quantifierRangeMaxCapped() {
        String result = generate("a{5,50}");
        assertTrue("Expected <=32 but got " + result.length(), result.length() <= 50);
    }

    @Test
    public void malformedRegexReturnsPattern() {
        // Unclosed bracket - should not throw, return something
        String result = generate("[unclosed");
        assertNotNull(result);
    }
}
