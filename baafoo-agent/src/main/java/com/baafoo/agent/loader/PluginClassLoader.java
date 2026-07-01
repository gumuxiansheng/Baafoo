package com.baafoo.agent.loader;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URL;
import java.net.URLClassLoader;

/**
 * Dedicated ClassLoader for Agent Plugins.
 *
 * <p><b>parent=null</b>: Isolates plugin dependencies from the agent's
 * Bootstrap/System ClassLoader. This prevents SDK version conflicts
 * (e.g., Pulsar client version mismatch between the agent and the
 * application being intercepted).</p>
 *
 * <p>Per plugin-arch-advice.md: "Plugin ClassLoader parent=null,
 * only loads SPI API from Bootstrap CL".</p>
 *
 * <p><b>P2-3 fix</b>: SPI interface classes ({@code com.baafoo.plugin.*})
 * and JDK classes ({@code java.*}, {@code javax.*}) must be resolvable by
 * plugins, but the parent is {@code null} for isolation. Previously this
 * class used {@code ClassLoader.getSystemClassLoader().loadClass(name)} to
 * delegate, but in Java EE / web containers / custom launchers the system
 * class loader is not necessarily the application class loader that owns
 * {@code baafoo-plugin-api}. We instead resolve these classes through
 * {@code BaafooAgent.class.getClassLoader()} — the loader that actually
 * owns the agent and plugin-api JARs — falling back to the system loader
 * only as a last resort.</p>
 */
public class PluginClassLoader extends URLClassLoader {

    private static final Logger log = LoggerFactory.getLogger(PluginClassLoader.class);

    /** Loader that owns baafoo-plugin-api; captured at construction time. */
    private final ClassLoader spiLoader;

    public PluginClassLoader(URL[] urls) {
        this(urls, pickSpiLoader());
    }

    /** Test-friendly constructor allowing explicit SPI loader injection. */
    public PluginClassLoader(URL[] urls, ClassLoader spiLoader) {
        super(urls, null); // parent=null for isolation
        this.spiLoader = spiLoader != null ? spiLoader : pickSpiLoader();
    }

    private static ClassLoader pickSpiLoader() {
        // Prefer the loader that loaded the agent (and thus baafoo-plugin-api).
        try {
            Class<?> agentCls = Class.forName("com.baafoo.agent.BaafooAgent");
            ClassLoader cl = agentCls.getClassLoader();
            if (cl != null) return cl;
        } catch (Throwable ignored) {
            // Agent not on classpath (e.g., unit test); fall through.
        }
        // Fallback: system loader. Same behavior as before for non-container envs.
        return ClassLoader.getSystemClassLoader();
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        // Check if already loaded
        Class<?> c = findLoadedClass(name);
        if (c != null) {
            return c;
        }

        // SPI API + JDK classes must be resolved through the agent/plugin-api
        // loader, NOT bundled inside the plugin JAR. Using super.loadClass()
        // would be a no-op because parent is null.
        if (name.startsWith("com.baafoo.plugin.")
                || name.startsWith("java.")
                || name.startsWith("javax.")
                || name.startsWith("org.slf4j.")) {
            try {
                c = spiLoader.loadClass(name);
                if (c != null) {
                    if (resolve) resolveClass(c);
                    return c;
                }
            } catch (ClassNotFoundException e) {
                // Fall through to local loading
            }
        }

        // Load from plugin JAR
        try {
            c = findClass(name);
            if (resolve) resolveClass(c);
            return c;
        } catch (ClassNotFoundException e) {
            // Class not in plugin JAR, try parent (null) — will throw CNFE,
            // which is the correct isolation behavior for unknown classes.
            return super.loadClass(name, resolve);
        }
    }
}
