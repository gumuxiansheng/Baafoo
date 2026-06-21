package com.baafoo.core.model;

import org.junit.Test;
import static org.junit.Assert.*;

public class EnvironmentModeTest {

    @Test
    public void testValues() {
        assertEquals(5, EnvironmentMode.values().length);
        assertEquals(EnvironmentMode.STUB, EnvironmentMode.valueOf("STUB"));
        assertEquals(EnvironmentMode.PASSTHROUGH, EnvironmentMode.valueOf("PASSTHROUGH"));
        assertEquals(EnvironmentMode.RECORD, EnvironmentMode.valueOf("RECORD"));
        assertEquals(EnvironmentMode.RECORD_AND_STUB, EnvironmentMode.valueOf("RECORD_AND_STUB"));
        assertEquals(EnvironmentMode.RECORD_ALL, EnvironmentMode.valueOf("RECORD_ALL"));
    }

    @Test
    public void testGetValue() {
        assertEquals("stub", EnvironmentMode.STUB.getValue());
        assertEquals("passthrough", EnvironmentMode.PASSTHROUGH.getValue());
        assertEquals("record", EnvironmentMode.RECORD.getValue());
        assertEquals("record-and-stub", EnvironmentMode.RECORD_AND_STUB.getValue());
        assertEquals("record-all", EnvironmentMode.RECORD_ALL.getValue());
    }

    @Test
    public void testFromValue() {
        assertEquals(EnvironmentMode.STUB, EnvironmentMode.fromValue("stub"));
        assertEquals(EnvironmentMode.PASSTHROUGH, EnvironmentMode.fromValue("passthrough"));
        assertEquals(EnvironmentMode.RECORD, EnvironmentMode.fromValue("record"));
        assertEquals(EnvironmentMode.RECORD_AND_STUB, EnvironmentMode.fromValue("record-and-stub"));
        assertEquals(EnvironmentMode.RECORD_ALL, EnvironmentMode.fromValue("record-all"));
    }

    @Test
    public void testFromValueWithHyphenNormalization() {
        assertEquals(EnvironmentMode.RECORD_AND_STUB, EnvironmentMode.fromValue("record_and_stub"));
        assertEquals(EnvironmentMode.RECORD_ALL, EnvironmentMode.fromValue("record_all"));
    }

    @Test
    public void testFromValueCaseInsensitive() {
        assertEquals(EnvironmentMode.STUB, EnvironmentMode.fromValue("STUB"));
        assertEquals(EnvironmentMode.PASSTHROUGH, EnvironmentMode.fromValue("PASSTHROUGH"));
    }

    @Test
    public void testFromValueNullReturnsStub() {
        assertEquals(EnvironmentMode.STUB, EnvironmentMode.fromValue(null));
    }

    @Test
    public void testFromValueUnknownReturnsStub() {
        assertEquals(EnvironmentMode.STUB, EnvironmentMode.fromValue("unknown-mode"));
    }

    @Test
    public void testFromValueByName() {
        assertEquals(EnvironmentMode.RECORD, EnvironmentMode.fromValue("RECORD"));
        assertEquals(EnvironmentMode.RECORD_AND_STUB, EnvironmentMode.fromValue("RECORD_AND_STUB"));
        assertEquals(EnvironmentMode.RECORD_ALL, EnvironmentMode.fromValue("RECORD_ALL"));
    }
}
