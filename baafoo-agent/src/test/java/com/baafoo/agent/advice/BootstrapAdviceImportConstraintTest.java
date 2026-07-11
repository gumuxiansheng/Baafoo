package com.baafoo.agent.advice;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

/**
 * Compile-time constraint that prevents Bootstrap ClassLoader advice classes
 * from referencing {@code com.baafoo.plugin.*}.
 *
 * <p>Background: the agent JAR's {@code BaafooAgent.createBootstrapJar()}
 * packages only a small set of classes ({@code GlobalRouteState} + the six
 * {@code com.baafoo.agent.state.*} managers) into a temp JAR appended to the
 * Bootstrap CL search path. The Bootstrap CL <em>cannot</em> load
 * {@code com.baafoo.plugin.*} (that package is loaded by the App CL). When
 * ByteBuddy inlines an Advice class into a JDK class (e.g. {@code java.net.Socket}),
 * the advice body runs in the Bootstrap CL context — any reference to
 * {@code com.baafoo.plugin.PluginEvent} (or any type from that package) throws
 * {@code NoClassDefFoundError} / {@code LinkageError} at runtime, pushing
 * HTTP/TCP/DNS interception into fail-closed mode.</p>
 *
 * <p>This test scans the bytecode of every Bootstrap-CL advice class (the 10
 * advice classes whose targets live in {@code java.net.*} / {@code sun.*}, plus
 * {@code GlobalRouteState} itself) and asserts that no constant-pool entry
 * references the {@code com/baafoo/plugin/} package. See commit 73f7849 for the
 * original bug and fix.</p>
 */
public class BootstrapAdviceImportConstraintTest {

    /** Forbidden internal-name prefix (bytecode uses '/' not '.'). */
    private static final byte[] FORBIDDEN = ascii("com/baafoo/plugin");

    /**
     * The 10 Bootstrap-CL advice classes (targets in {@code java.net.*} /
     * {@code sun.*} run on the Bootstrap CL, determined by inspecting
     * {@code BaafooAgent.installTransforms}), plus {@code GlobalRouteState}
     * itself (also packaged into the Bootstrap JAR by
     * {@code createBootstrapJar()}).
     *
     * <p>Note: {@code GlobalRouteState} legitimately references
     * {@code com.baafoo.agent.state.*} (RouteTable, PluginBridge, etc.) which
     * ARE packaged in the Bootstrap JAR. Only {@code com/baafoo/plugin/} is
     * forbidden for these classes.</p>
     */
    private static final String[] BOOTSTRAP_CLASSES = {
            "com.baafoo.agent.advice.HttpOpenServerAdvice",
            "com.baafoo.agent.advice.DnsResolveAdvice",
            "com.baafoo.agent.advice.DnsResolveAllAdvice",
            "com.baafoo.agent.advice.NioSocketConnectAdvice",
            "com.baafoo.agent.advice.NioSocketFinishConnectAdvice",
            "com.baafoo.agent.advice.SocketChannelReadAdvice",
            "com.baafoo.agent.advice.SocketChannelWriteAdvice",
            "com.baafoo.agent.advice.SocketCloseAdvice",
            "com.baafoo.agent.advice.SocketConnectAdvice",
            "com.baafoo.agent.advice.SocketInputStreamAdvice",
            "com.baafoo.agent.advice.SocketOutputStreamAdvice",
            // GlobalRouteState is packaged into the Bootstrap JAR by createBootstrapJar()
            // and loaded by the Bootstrap CL; the same constraint applies.
            "com.baafoo.agent.GlobalRouteState",
    };

    @Test
    public void noBootstrapAdviceClassReferencesPluginPackage() throws Exception {
        List<String> failures = new ArrayList<>();
        for (String className : BOOTSTRAP_CLASSES) {
            byte[] bytecode = readClassResource(className);
            assertTrue("Bootstrap-CL class bytecode not found on test classpath: " + className,
                    bytecode != null && bytecode.length > 0);
            int idx = indexOf(bytecode, FORBIDDEN);
            if (idx >= 0) {
                String offending = extractInternalName(bytecode, idx);
                failures.add("Bootstrap-CL advice " + className
                        + " references forbidden package " + offending
                        + " — Bootstrap CL cannot load com.baafoo.plugin.*. Use GlobalRouteState's "
                        + "Object-typed bridge fields (PLUGIN_CONSULT_FN_EXT, EVENT_FIRE_FN, etc.) instead.");
            }
        }
        if (!failures.isEmpty()) {
            fail(failures.size() + " Bootstrap-CL class(es) reference the forbidden plugin package:\n  - "
                    + String.join("\n  - ", failures));
        }
    }

    /** Read a class's compiled {@code .class} resource from the test classpath. */
    private static byte[] readClassResource(String className) throws Exception {
        String resource = className.replace('.', '/') + ".class";
        ClassLoader cl = BootstrapAdviceImportConstraintTest.class.getClassLoader();
        try (InputStream in = cl.getResourceAsStream(resource)) {
            if (in == null) return null;
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) != -1) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }

    /** Simple byte-pattern search (no regex, no constant-pool parser). */
    private static int indexOf(byte[] data, byte[] pattern) {
        if (pattern.length == 0) return 0;
        int max = data.length - pattern.length;
        for (int i = 0; i <= max; i++) {
            boolean match = true;
            for (int j = 0; j < pattern.length; j++) {
                if (data[i + j] != pattern[j]) {
                    match = false;
                    break;
                }
            }
            if (match) return i;
        }
        return -1;
    }

    /**
     * Extract the full JVM internal name starting at {@code start} by scanning
     * forward while bytes are valid internal-name characters
     * ({@code [A-Za-z0-9_/$]}). Stops at {@code ;} (array descriptor terminator)
     * or any other non-name byte, yielding a clean name like
     * {@code com/baafoo/plugin/PluginEvent}.
     */
    private static String extractInternalName(byte[] data, int start) {
        int end = start;
        while (end < data.length && isInternalNameByte(data[end])) {
            end++;
        }
        return new String(data, start, end - start, StandardCharsets.US_ASCII);
    }

    private static boolean isInternalNameByte(byte b) {
        return (b >= 'a' && b <= 'z')
                || (b >= 'A' && b <= 'Z')
                || (b >= '0' && b <= '9')
                || b == '/' || b == '_' || b == '$';
    }

    private static byte[] ascii(String s) {
        return s.getBytes(StandardCharsets.US_ASCII);
    }
}
