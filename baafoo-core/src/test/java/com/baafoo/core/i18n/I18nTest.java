package com.baafoo.core.i18n;

import org.junit.Test;

import java.util.Locale;

import static org.junit.Assert.*;

public class I18nTest {

    @Test
    public void testDefaultInstance() {
        I18n i18n = I18n.defaultInstance();
        assertNotNull(i18n);
        assertEquals(Locale.SIMPLIFIED_CHINESE, i18n.getLocale());
    }

    @Test
    public void testOfNullLocale() {
        I18n i18n = I18n.of((Locale) null);
        assertNotNull(i18n);
        assertEquals(Locale.SIMPLIFIED_CHINESE, i18n.getLocale());
    }

    @Test
    public void testOfNonNullLocale() {
        I18n i18n = I18n.of(Locale.ENGLISH);
        assertNotNull(i18n);
        assertEquals(Locale.ENGLISH, i18n.getLocale());
    }

    @Test
    public void testOfNullLanguageTag() {
        I18n i18n = I18n.of((String) null);
        assertNotNull(i18n);
        assertEquals(Locale.SIMPLIFIED_CHINESE, i18n.getLocale());
    }

    @Test
    public void testOfEmptyLanguageTag() {
        I18n i18n = I18n.of("");
        assertNotNull(i18n);
        assertEquals(Locale.SIMPLIFIED_CHINESE, i18n.getLocale());
    }

    @Test
    public void testOfLanguageTag() {
        I18n i18n = I18n.of("en");
        assertNotNull(i18n);
        assertEquals(Locale.ENGLISH, i18n.getLocale());
    }

    @Test
    public void testGetReturnsRawKeyWhenNotFound() {
        I18n i18n = I18n.defaultInstance();
        assertEquals("nonexistent.key.12345", i18n.get("nonexistent.key.12345"));
    }

    @Test
    public void testGetWithArgsReturnsRawKeyWhenNotFound() {
        I18n i18n = I18n.defaultInstance();
        assertEquals("missing.key", i18n.get("missing.key", "arg0", "arg1"));
    }

    @Test
    public void testContainsKeyFalse() {
        I18n i18n = I18n.defaultInstance();
        assertFalse(i18n.containsKey("completely.nonexistent.key.xyz"));
    }

    @Test
    public void testCacheReturnsSameInstance() {
        I18n a = I18n.of(Locale.ENGLISH);
        I18n b = I18n.of(Locale.ENGLISH);
        assertSame(a, b);
    }

    @Test
    public void testDefaultLocaleFallback() {
        // Default locale has no properties file in test resources,
        // so get() should return the key itself.
        I18n i18n = I18n.defaultInstance();
        assertEquals("any.key", i18n.get("any.key"));
    }
}
