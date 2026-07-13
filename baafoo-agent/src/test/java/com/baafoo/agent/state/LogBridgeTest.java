package com.baafoo.agent.state;

import com.baafoo.agent.GlobalRouteState;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.Assert.*;

public class LogBridgeTest {

    private final LogBridge bridge = new LogBridge();

    @Before
    public void clearHandlers() {
        GlobalRouteState.LOG_INFO_HANDLER = null;
        GlobalRouteState.LOG_WARN_HANDLER = null;
        GlobalRouteState.LOG_ERROR_HANDLER = null;
        GlobalRouteState.LOG_DEBUG_HANDLER = null;
    }

    @After
    public void clearHandlersAfter() {
        GlobalRouteState.LOG_INFO_HANDLER = null;
        GlobalRouteState.LOG_WARN_HANDLER = null;
        GlobalRouteState.LOG_ERROR_HANDLER = null;
        GlobalRouteState.LOG_DEBUG_HANDLER = null;
    }

    @Test
    public void infoWithHandler() {
        List<String> captured = new ArrayList<>();
        GlobalRouteState.LOG_INFO_HANDLER = captured::add;
        bridge.info("hello");
        assertEquals(1, captured.size());
        assertEquals("hello", captured.get(0));
    }

    @Test
    public void infoWithoutHandlerPrintsToStdout() {
        PrintStream old = System.out;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        System.setOut(new PrintStream(baos));
        try {
            bridge.info("test message");
        } finally {
            System.setOut(old);
        }
        assertTrue(baos.toString().contains("test message"));
    }

    @Test
    public void warnWithHandler() {
        List<String> captured = new ArrayList<>();
        GlobalRouteState.LOG_WARN_HANDLER = captured::add;
        bridge.warn("warn msg");
        assertEquals("warn msg", captured.get(0));
    }

    @Test
    public void warnWithoutHandler() {
        PrintStream old = System.out;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        System.setOut(new PrintStream(baos));
        try {
            bridge.warn("warn stdout");
        } finally {
            System.setOut(old);
        }
        assertTrue(baos.toString().contains("warn stdout"));
    }

    @Test
    public void errorWithHandler() {
        List<String> captured = new ArrayList<>();
        GlobalRouteState.LOG_ERROR_HANDLER = captured::add;
        bridge.error("err msg");
        assertEquals("err msg", captured.get(0));
    }

    @Test
    public void errorWithoutHandler() {
        PrintStream old = System.out;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        System.setOut(new PrintStream(baos));
        try {
            bridge.error("err stdout");
        } finally {
            System.setOut(old);
        }
        assertTrue(baos.toString().contains("err stdout"));
    }

    @Test
    public void debugWithHandler() {
        List<String> captured = new ArrayList<>();
        GlobalRouteState.LOG_DEBUG_HANDLER = captured::add;
        bridge.debug("dbg msg");
        assertEquals("dbg msg", captured.get(0));
    }

    @Test
    public void debugWithoutHandler() {
        PrintStream old = System.out;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        System.setOut(new PrintStream(baos));
        try {
            bridge.debug("dbg stdout");
        } finally {
            System.setOut(old);
        }
        assertTrue(baos.toString().contains("dbg stdout"));
    }

    @Test
    public void handlerExceptionFallsBackToStdout() {
        Consumer<String> throwingHandler = s -> { throw new RuntimeException("boom"); };
        GlobalRouteState.LOG_INFO_HANDLER = throwingHandler;

        PrintStream old = System.out;
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        System.setOut(new PrintStream(baos));
        try {
            bridge.info("fallback msg");
        } finally {
            System.setOut(old);
        }
        assertTrue(baos.toString().contains("fallback msg"));
    }

    @Test
    public void setHandlersSetsAll() {
        Consumer<String> info = s -> {};
        Consumer<String> warn = s -> {};
        Consumer<String> error = s -> {};
        Consumer<String> debug = s -> {};

        bridge.setHandlers(info, warn, error, debug);

        assertSame(info, GlobalRouteState.LOG_INFO_HANDLER);
        assertSame(warn, GlobalRouteState.LOG_WARN_HANDLER);
        assertSame(error, GlobalRouteState.LOG_ERROR_HANDLER);
        assertSame(debug, GlobalRouteState.LOG_DEBUG_HANDLER);
    }

    @Test
    public void setHandlersClearsAll() {
        bridge.setHandlers(null, null, null, null);
        assertNull(GlobalRouteState.LOG_INFO_HANDLER);
        assertNull(GlobalRouteState.LOG_WARN_HANDLER);
        assertNull(GlobalRouteState.LOG_ERROR_HANDLER);
        assertNull(GlobalRouteState.LOG_DEBUG_HANDLER);
    }
}
