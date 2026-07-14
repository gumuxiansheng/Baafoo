package com.baafoo.agent.bootstrap;

import java.io.File;
import java.lang.instrument.Instrumentation;
import java.util.jar.JarFile;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Responsible for all Bootstrap ClassLoader infrastructure operations:
 * appending the helper JAR to the Bootstrap CL search path, verifying
 * classloader isolation, and exposing reflection helpers used to keep the
 * Bootstrap-CL copy of {@code GlobalRouteState} in sync with the App-CL copy.
 *
 * <p>This is a static utility class extracted from {@code BaafooAgent}; it
 * holds the {@code bootstrapGRSClass} and {@code bootstrapHostPortCtor}
 * references that are populated during sync and consumed by both this class
 * and {@link BootstrapStateSync}.</p>
 */
public class BootstrapClassPathSetup {

    private static final Logger log = LoggerFactory.getLogger(BootstrapClassPathSetup.class);

    private static volatile Class<?> bootstrapGRSClass;
    private static volatile java.lang.reflect.Constructor<?> bootstrapHostPortCtor;

    public static Class<?> getBootstrapGRSClass() {
        return bootstrapGRSClass;
    }

    public static java.lang.reflect.Constructor<?> getBootstrapHostPortCtor() {
        return bootstrapHostPortCtor;
    }

    /** Package-private setter used by {@link BootstrapStateSync}. */
    static void setBootstrapGRSClass(Class<?> cls) {
        bootstrapGRSClass = cls;
    }

    /** Package-private setter used by {@link BootstrapStateSync}. */
    static void setBootstrapHostPortCtor(java.lang.reflect.Constructor<?> ctor) {
        bootstrapHostPortCtor = ctor;
    }

    public static void setupBootstrapClassPath(Instrumentation inst) {
        appendToBootstrapClassLoaderSearch(inst);

        // ---- Defensive self-check: Bootstrap CL must NOT load plugin-api classes ----
        //
        // WHY: commit 73f7849 fixed a NoClassDefFoundError / LinkageError caused by
        // Bootstrap-CL advice classes (inlined by ByteBuddy into java.net.Socket,
        // java.net.InetAddress, sun.nio.ch.SocketChannelImpl) referencing
        // com.baafoo.plugin.* types. The plugin-api package is loaded by the App CL,
        // not the Bootstrap CL — any such reference pushes HTTP/TCP/DNS interception
        // into fail-closed mode with no visible error. This self-check verifies the
        // invariant from the loading side: after the Bootstrap helper JAR is appended,
        // the Bootstrap CL must NOT be able to load com.baafoo.plugin.PluginEvent. If
        // it can, createBootstrapJar()'s classResources array incorrectly packages
        // plugin-api classes and the 73f7849 bug will recur. Wrapped in
        // try/catch(Throwable) so it never breaks agent startup.
        verifyBootstrapClassPathIsolation();

        BootstrapStateSync.syncGlobalRouteStateToBootstrapCL();
        log.info("GlobalRouteState synced to Bootstrap CL version");
    }

    /**
     * Defensive self-check that the Bootstrap ClassLoader cannot load
     * {@code com.baafoo.plugin.PluginEvent} after the Bootstrap helper JAR is
     * appended. Logs an error (but never throws) if the invariant is violated.
     * See {@link #setupBootstrapClassPath} for the rationale.
     */
    public static void verifyBootstrapClassPathIsolation() {
        try {
            Class<?> bootGRS = findBootstrapClass("com.baafoo.agent.GlobalRouteState");
            // bootGRS.getClassLoader() is null for the true Bootstrap CL;
            // Class.forName(name, false, null) uses the Bootstrap CL, so passing
            // the null loader directly is the correct Bootstrap-CL resolution.
            ClassLoader bootstrapCL = bootGRS.getClassLoader();
            try {
                Class<?> offending = Class.forName("com.baafoo.plugin.PluginEvent", false, bootstrapCL);
                log.error("Bootstrap CL self-check FAILED: com.baafoo.plugin.PluginEvent is loadable from " +
                                "the Bootstrap ClassLoader (loader={}). This means the Bootstrap helper JAR " +
                                "incorrectly packages plugin-api classes, which will cause Bootstrap-CL advice " +
                                "to directly reference plugin types and break class linkage. Inspect " +
                                "createBootstrapJar() classResources array.",
                        offending.getClassLoader());
            } catch (ClassNotFoundException expected) {
                // Expected: Bootstrap CL must NOT be able to load plugin-api classes.
                log.info("Bootstrap CL self-check passed: plugin-api classes are not loadable from the Bootstrap CL.");
            }
        } catch (Throwable t) {
            log.error("Bootstrap CL self-check error (non-fatal, continuing): {}", t.getMessage(), t);
        }
    }

    public static void appendToBootstrapClassLoaderSearch(Instrumentation inst) {
        try {
            File bootstrapJar = createBootstrapJar();
            if (bootstrapJar != null && bootstrapJar.exists()) {
                inst.appendToBootstrapClassLoaderSearch(new JarFile(bootstrapJar));
                log.info("Added Bootstrap helper jar to Bootstrap CL: {}", bootstrapJar.getAbsolutePath());
            } else {
                log.error("Failed to create Bootstrap helper jar. " +
                        "Advice classes will fail with ClassNotFoundException.");
            }
        } catch (Exception e) {
            log.error("Failed to add Bootstrap helper jar to Bootstrap ClassLoader search path: {}", e.getMessage(), e);
        }
    }

    /**
     * Creates a minimal jar containing only the classes needed by inlined Advice code
     * running in the Bootstrap ClassLoader context (e.g. GlobalRouteState).
     *
     * <p>Uses {@code ClassLoader.getResourceAsStream()} instead of {@code JarFile}
     * so this works regardless of how the agent is packaged — shaded jar, exploded
     * directory, or IDE classpath. ByteBuddy and other third-party classes are
     * intentionally excluded to prevent version conflicts with the host application
     * or other agents.</p>
     */
    public static File createBootstrapJar() {
        try {
            File tempJar = File.createTempFile("baafoo-bootstrap-", ".jar");
            tempJar.deleteOnExit();

            // P1-2: the six state manager classes are included so that the
            // Bootstrap-CL copy of GlobalRouteState can instantiate them in its
            // static initializer. Without these, the managers stay null on the
            // Bootstrap CL and every delegating method (lookup, recordDns,
            // isInternal, startRecording, forceRedirectPort, ...) throws NPE
            // inside Bootstrap-CL advice, silently disabling socket/NIO/DNS
            // interception. The managers are stateless — they operate on
            // GlobalRouteState's own static fields, which are already kept in
            // sync by the five reflection sync methods below — so no additional
            // cross-CL sync is required for the managers themselves.
            String[] classResources = {
                    "com/baafoo/agent/GlobalRouteState.class",
                    "com/baafoo/agent/GlobalRouteState$HostPort.class",
                    "com/baafoo/agent/state/RouteTable.class",
                    "com/baafoo/agent/state/DnsCache.class",
                    "com/baafoo/agent/state/RecordingTracker.class",
                    "com/baafoo/agent/state/LogBridge.class",
                    "com/baafoo/agent/state/PluginBridge.class",
                    "com/baafoo/agent/state/ProtocolMapper.class"
            };

            java.util.jar.Manifest manifest = new java.util.jar.Manifest();
            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(tempJar);
                 java.util.jar.JarOutputStream jos = new java.util.jar.JarOutputStream(fos, manifest)) {

                ClassLoader cl = BootstrapClassPathSetup.class.getClassLoader();
                for (String resource : classResources) {
                    java.io.InputStream is = cl.getResourceAsStream(resource);
                    if (is != null) {
                        try {
                            jos.putNextEntry(new java.util.jar.JarEntry(resource));
                            byte[] buf = new byte[4096];
                            int n;
                            while ((n = is.read(buf)) != -1) {
                                jos.write(buf, 0, n);
                            }
                            jos.closeEntry();
                        } finally {
                            is.close();
                        }
                    } else {
                        log.warn("Bootstrap class resource not found: {}", resource);
                    }
                }
            }

            return tempJar;
        } catch (Exception e) {
            log.error("Failed to create Bootstrap helper jar: {}", e.getMessage());
            return null;
        }
    }

    public static Class<?> findBootstrapClass(String name) throws ClassNotFoundException {
        try {
            ClassLoader cl = ClassLoader.getSystemClassLoader();
            while (cl.getParent() != null) {
                cl = cl.getParent();
            }
            return Class.forName(name, false, cl);
        } catch (Exception e) {
            return Class.forName(name);
        }
    }

    // ---- P2-4: hardened Bootstrap CL reflection bridge helpers ----
    //
    // The previous sync methods used Class.getField(fieldName) directly, which
    // throws NoSuchFieldException if a field is renamed or removed. That
    // exception was swallowed by a broad catch (Exception) and logged as a
    // generic warning, causing the bridge to fail silently — the agent would
    // appear to start but connections would not be intercepted.
    //
    // These helpers centralize field access with explicit existence checks
    // and actionable error messages. Sync failures now throw a clear
    // IllegalStateException naming the missing field, so the operator sees
    // the root cause at startup instead of debugging silent interception
    // failures at runtime.

    /**
     * Get a static field on the Bootstrap CL GlobalRouteState class, throwing
     * an actionable error if the field does not exist.
     *
     * @param bootGRS    the Bootstrap CL GlobalRouteState class
     * @param fieldName  the field name to look up
     * @return the reflected field
     * @throws IllegalStateException if the field does not exist (P2-4)
     */
    public static java.lang.reflect.Field requireBootstrapField(Class<?> bootGRS, String fieldName) {
        try {
            return bootGRS.getField(fieldName);
        } catch (NoSuchFieldException e) {
            throw new IllegalStateException(
                    "Bootstrap CL GlobalRouteState is missing field '" + fieldName
                            + "'. The Bootstrap JAR (created by createBootstrapJar) is likely "
                            + "out of sync with the agent source. Rebuild the agent JAR and "
                            + "restart. Underlying cause: " + e.getMessage(), e);
        }
    }

    /**
     * Set an int static field on the Bootstrap CL GlobalRouteState class.
     * Validates field existence with an actionable error message (P2-4).
     */
    public static void setBootstrapInt(Class<?> bootGRS, String fieldName, int value) {
        java.lang.reflect.Field f = requireBootstrapField(bootGRS, fieldName);
        try {
            f.setInt(null, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(
                    "Cannot set Bootstrap CL field '" + fieldName + "': " + e.getMessage(), e);
        }
    }

    /**
     * Set a reference static field on the Bootstrap CL GlobalRouteState class.
     * Validates field existence with an actionable error message (P2-4).
     */
    public static void setBootstrapRef(Class<?> bootGRS, String fieldName, Object value) {
        java.lang.reflect.Field f = requireBootstrapField(bootGRS, fieldName);
        try {
            f.set(null, value);
        } catch (IllegalAccessException e) {
            throw new IllegalStateException(
                    "Cannot set Bootstrap CL field '" + fieldName + "': " + e.getMessage(), e);
        }
    }
}
