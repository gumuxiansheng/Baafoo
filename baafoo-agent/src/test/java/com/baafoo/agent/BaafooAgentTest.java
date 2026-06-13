package com.baafoo.agent;

import org.junit.Test;

import static org.junit.Assert.*;

public class BaafooAgentTest {

    @Test
    public void testNotInitializedByDefault() {
        // BaafooAgent should not be initialized without premain
        assertFalse(BaafooAgent.isInitialized());
    }

    @Test
    public void testConfigIsNullBeforeInit() {
        assertNull(BaafooAgent.getConfig());
    }

    @Test
    public void testControlChannelIsNullBeforeInit() {
        assertNull(BaafooAgent.getControlChannel());
    }

    @Test
    public void testPluginManagerIsNullBeforeInit() {
        assertNull(BaafooAgent.getPluginManager());
    }

    @Test
    public void testBootstrapRoutesIsNullBeforeInit() {
        assertNull(BaafooAgent.getBootstrapRoutes());
    }

    @Test
    public void testBootstrapGRSClassIsNullBeforeInit() {
        assertNull(BaafooAgent.getBootstrapGRSClass());
    }

    @Test
    public void testBootstrapHostPortCtorIsNullBeforeInit() {
        assertNull(BaafooAgent.getBootstrapHostPortCtor());
    }
}
