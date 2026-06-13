package com.baafoo.cli;

import org.junit.Test;
import java.io.*;
import java.nio.file.Files;
import static org.junit.Assert.*;

public class BaafooCliTest {

    @Test
    public void testMainHelp() {
        PrintStream originalOut = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out));
        try {
            BaafooCli.main(new String[]{"help"});
            String output = out.toString();
            assertTrue(output.contains("Usage"));
            assertTrue(output.contains("init"));
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    public void testMainDashDashHelp() {
        PrintStream originalOut = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out));
        try {
            BaafooCli.main(new String[]{"--help"});
            String output = out.toString();
            assertTrue(output.contains("Usage"));
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    public void testMainNoArgs() {
        PrintStream originalOut = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out));
        try {
            BaafooCli.main(new String[0]);
            String output = out.toString();
            assertTrue(output.contains("Usage"));
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    public void testMainVersion() {
        PrintStream originalOut = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out));
        try {
            BaafooCli.main(new String[]{"version"});
            String output = out.toString();
            assertTrue(output.contains("v1.0.0"));
        } finally {
            System.setOut(originalOut);
        }
    }

    @Test
    public void testInitCreatesFiles() throws Exception {
        File tempDir = new File(System.getProperty("java.io.tmpdir"), "baafoo-test-" + System.currentTimeMillis());
        tempDir.mkdirs();

        PrintStream originalOut = System.out;
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        System.setOut(new PrintStream(out));
        try {
            BaafooCli.main(new String[]{"init", tempDir.getAbsolutePath(), "--non-interactive"});
            String output = out.toString();
            assertTrue(output.contains("baafoo-agent.yml"));
            assertTrue(output.contains("baafoo-server.yml"));
            assertTrue(output.contains("baafoo-rules.yml"));
            assertTrue(output.contains("Baafoo initialized"));

            assertTrue(new File(tempDir, "baafoo-agent.yml").exists());
            assertTrue(new File(tempDir, "baafoo-server.yml").exists());
            assertTrue(new File(tempDir, "baafoo-rules.yml").exists());
            assertTrue(new File(tempDir, "start-agent.sh").exists());
            assertTrue(new File(tempDir, "start-agent.bat").exists());
            assertTrue(new File(tempDir, "start-server.sh").exists());
            assertTrue(new File(tempDir, "start-server.bat").exists());

            assertTrue(new File(tempDir, "start-agent.sh").canExecute());
            assertTrue(new File(tempDir, "start-server.sh").canExecute());
        } finally {
            System.setOut(originalOut);
            deleteDirectory(tempDir);
        }
    }

    @Test
    public void testMainUnknownCommand() {
        PrintStream originalErr = System.err;
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        System.setErr(new PrintStream(err));
        try {
            BaafooCli.main(new String[]{"unknown-cmd"});
            assertTrue(err.toString().contains("Unknown command"));
        } finally {
            System.setErr(originalErr);
        }
    }

    private static void deleteDirectory(File dir) {
        File[] files = dir.listFiles();
        if (files != null) {
            for (File f : files) {
                if (f.isDirectory()) deleteDirectory(f);
                f.delete();
            }
        }
        dir.delete();
    }
}
