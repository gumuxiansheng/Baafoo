package com.baafoo.agent.loader;

import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.net.URL;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;

import static org.junit.Assert.*;

public class PluginClassLoaderTest {

    @Test
    public void testLoadJdkClass() throws Exception {
        // Create an empty temp jar so the classloader has a URL
        File tempJar = createTempJar();
        try {
            URL[] urls = { tempJar.toURI().toURL() };
            PluginClassLoader cl = new PluginClassLoader(urls);

            // JDK classes should load from system classloader
            Class<?> stringClass = cl.loadClass("java.lang.String");
            assertSame(String.class, stringClass);
        } finally {
            tempJar.delete();
        }
    }

    @Test
    public void testLoadSpiApiClass() throws Exception {
        File tempJar = createTempJar();
        try {
            URL[] urls = { tempJar.toURI().toURL() };
            PluginClassLoader cl = new PluginClassLoader(urls);

            // com.baafoo.plugin.* should delegate to system classloader
            Class<?> pluginClass = cl.loadClass("com.baafoo.plugin.AgentPlugin");
            assertNotNull(pluginClass);
            // Should be the same class as loaded by the system CL
            assertSame(com.baafoo.plugin.AgentPlugin.class, pluginClass);
        } finally {
            tempJar.delete();
        }
    }

    @Test
    public void testParentIsNull() throws Exception {
        File tempJar = createTempJar();
        try {
            URL[] urls = { tempJar.toURI().toURL() };
            PluginClassLoader cl = new PluginClassLoader(urls);
            assertNull(cl.getParent());
        } finally {
            tempJar.delete();
        }
    }

    @Test
    public void testClassNotFoundForNonExistent() throws Exception {
        File tempJar = createTempJar();
        try {
            URL[] urls = { tempJar.toURI().toURL() };
            PluginClassLoader cl = new PluginClassLoader(urls);
            try {
                cl.loadClass("com.nonexistent.FooBar");
                fail("Should throw ClassNotFoundException");
            } catch (ClassNotFoundException e) {
                // Expected
            }
        } finally {
            tempJar.delete();
        }
    }

    private File createTempJar() throws Exception {
        File tempJar = File.createTempFile("test-plugin-", ".jar");
        tempJar.deleteOnExit();
        JarOutputStream jos = new JarOutputStream(new FileOutputStream(tempJar), new Manifest());
        jos.close();
        return tempJar;
    }
}
