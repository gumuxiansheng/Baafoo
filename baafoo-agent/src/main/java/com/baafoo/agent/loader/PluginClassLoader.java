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
 */
public class PluginClassLoader extends URLClassLoader {

    private static final Logger log = LoggerFactory.getLogger(PluginClassLoader.class);

    public PluginClassLoader(URL[] urls) {
        super(urls, null); // parent=null for isolation
    }

    @Override
    protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
        // Check if already loaded
        Class<?> c = findLoadedClass(name);
        if (c != null) {
            return c;
        }

        // SPI API classes should come from Bootstrap ClassLoader
        if (name.startsWith("com.baafoo.plugin.")) {
            try {
                c = ClassLoader.getSystemClassLoader().loadClass(name);
                if (c != null) {
                    if (resolve) resolveClass(c);
                    return c;
                }
            } catch (ClassNotFoundException e) {
                // Fall through to local loading
            }
        }

        // JDK classes from Bootstrap
        if (name.startsWith("java.") || name.startsWith("javax.")) {
            try {
                c = ClassLoader.getSystemClassLoader().loadClass(name);
                if (c != null) {
                    if (resolve) resolveClass(c);
                    return c;
                }
            } catch (ClassNotFoundException e) {
                // Fall through
            }
        }

        // Load from plugin JAR
        try {
            c = findClass(name);
            if (resolve) resolveClass(c);
            return c;
        } catch (ClassNotFoundException e) {
            // Class not in plugin JAR, try parent
            return super.loadClass(name, resolve);
        }
    }
}
