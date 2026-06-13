package com.baafoo.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.File;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.util.*;

/**
 * Baafoo CLI — Quick project initialization tool.
 *
 * <p>Usage: java -jar baafoo-cli.jar init [project-dir]</p>
 *
 * <p>Generates:
 * <ul>
 *   <li>baafoo-agent.yml — Agent configuration template</li>
 *   <li>baafoo-server.yml — Server configuration template</li>
 *   <li>start-agent.sh / start-agent.bat — Agent launch scripts</li>
 *   <li>start-server.sh / start-server.bat — Server launch scripts</li>
 *   <li>baafoo-rules.yml — Example rules</li>
 * </ul></p>
 */
public class BaafooCli {

    private static final ObjectMapper YAML_MAPPER = new ObjectMapper(new YAMLFactory());

    public static void main(String[] args) {
        if (args.length == 0 || "help".equals(args[0]) || "--help".equals(args[0])) {
            printHelp();
            return;
        }

        String command = args[0];
        String targetDir = args.length > 1 ? args[1] : ".";

        switch (command) {
            case "init":
                init(targetDir);
                break;
            case "version":
                System.out.println("Baafoo CLI v1.0.0-SNAPSHOT");
                break;
            default:
                System.err.println("Unknown command: " + command);
                printHelp();
        }
    }

    private static void init(String targetDir) {
        File dir = new File(targetDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        System.out.println("=== Baafoo Init === ");
        System.out.println("Project directory: " + dir.getAbsolutePath());
        System.out.println();

        try {
            // 1. Generate agent config
            File agentConfig = new File(dir, "baafoo-agent.yml");
            writeAgentConfig(agentConfig);
            System.out.println("  [OK] baafoo-agent.yml");

            // 2. Generate server config
            File serverConfig = new File(dir, "baafoo-server.yml");
            writeServerConfig(serverConfig);
            System.out.println("  [OK] baafoo-server.yml");

            // 3. Generate example rules
            File rulesFile = new File(dir, "baafoo-rules.yml");
            writeExampleRules(rulesFile);
            System.out.println("  [OK] baafoo-rules.yml");

            // 4. Generate launch scripts
            File startAgentSh = new File(dir, "start-agent.sh");
            writeStartAgentScript(startAgentSh);
            startAgentSh.setExecutable(true);
            System.out.println("  [OK] start-agent.sh");

            File startAgentBat = new File(dir, "start-agent.bat");
            writeStartAgentBat(startAgentBat);
            System.out.println("  [OK] start-agent.bat");

            File startServerSh = new File(dir, "start-server.sh");
            writeStartServerScript(startServerSh);
            startServerSh.setExecutable(true);
            System.out.println("  [OK] start-server.sh");

            File startServerBat = new File(dir, "start-server.bat");
            writeStartServerBat(startServerBat);
            System.out.println("  [OK] start-server.bat");

            System.out.println();
            System.out.println("=== Baafoo initialized! ===");
            System.out.println();
            System.out.println("Next steps:");
            System.out.println("  1. cd " + targetDir);
            System.out.println("  2. Review and edit baafoo-agent.yml and baafoo-server.yml");
            System.out.println("  3. Start the server:    ./start-server.sh");
            System.out.println("  4. Start your app with: java -javaagent:baafoo-agent.jar -jar your-app.jar");
            System.out.println("  5. Open Web console:    http://localhost:8084/__baafoo__/");
            System.out.println();

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }

    private static void writeAgentConfig(File file) throws Exception {
        Map<String, Object> config = new LinkedHashMap<String, Object>();
        config.put("agentId", "agent-" + UUID.randomUUID().toString().substring(0, 8));
        config.put("environment", "dev");
        config.put("serverUrl", "http://localhost:8084");
        config.put("heartbeatIntervalSec", 30);
        config.put("pollIntervalSec", 10);
        config.put("consulEnabled", false);
        config.put("consulAddress", "localhost:8500");
        config.put("protocols", Arrays.asList("http", "tcp"));
        config.put("hotReload", true);
        config.put("connectionRetries", 3);
        config.put("retryBackoffMs", 1000);

        try (FileWriter w = new FileWriter(file)) {
            w.write("# Baafoo Agent Configuration\n");
            w.write("# See docs for full configuration reference\n\n");
            YAML_MAPPER.writeValue(w, config);
        }
    }

    private static void writeServerConfig(File file) throws Exception {
        Map<String, Object> config = new LinkedHashMap<String, Object>();
        config.put("httpPort", 8084);

        Map<String, Integer> ports = new LinkedHashMap<String, Integer>();
        ports.put("http", 9000);
        ports.put("tcp", 9001);
        ports.put("kafka", 9002);
        ports.put("pulsar", 9003);
        ports.put("jms", 9004);
        config.put("protocolPorts", ports);

        config.put("dataDir", "./data");
        config.put("rulesDir", "./data/rules");
        config.put("recordingsDir", "./data/recordings");
        config.put("recordingRetentionDays", 7);
        config.put("corsEnabled", true);
        config.put("requestLogging", true);
        config.put("agentHeartbeatTimeoutSec", 60);

        try (FileWriter w = new FileWriter(file)) {
            w.write("# Baafoo Server Configuration\n");
            w.write("# See docs for full configuration reference\n\n");
            YAML_MAPPER.writeValue(w, config);
        }
    }

    private static void writeExampleRules(File file) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("# Baafoo Example Rules\n");
        sb.append("# Import via Web Console or place in data/rules/\n\n");

        List<Map<String, Object>> rules = new ArrayList<Map<String, Object>>();

        // Example 1: HTTP rule
        Map<String, Object> rule1 = new LinkedHashMap<String, Object>();
        rule1.put("id", "example-http-1");
        rule1.put("name", "GET /api/users");
        rule1.put("protocol", "http");
        rule1.put("host", "api.example.com");
        rule1.put("port", 8084);
        rule1.put("enabled", true);
        rule1.put("priority", 10);

        List<Map<String, String>> conditions1 = new ArrayList<Map<String, String>>();
        Map<String, String> c1 = new LinkedHashMap<String, String>();
        c1.put("type", "method");
        c1.put("operator", "equals");
        c1.put("value", "GET");
        conditions1.add(c1);
        Map<String, String> c2 = new LinkedHashMap<String, String>();
        c2.put("type", "path");
        c2.put("operator", "startsWith");
        c2.put("value", "/api/users");
        conditions1.add(c2);
        rule1.put("conditions", conditions1);

        List<Map<String, Object>> responses1 = new ArrayList<Map<String, Object>>();
        Map<String, Object> r1 = new LinkedHashMap<String, Object>();
        r1.put("name", "成功返回用户列表");
        r1.put("statusCode", 200);
        r1.put("body", "{\"code\":0,\"data\":[{\"id\":1,\"name\":\"Alice\"},{\"id\":2,\"name\":\"Bob\"}],\"message\":\"success\"}");
        responses1.add(r1);
        rule1.put("responses", responses1);

        rules.add(rule1);
        YAML_MAPPER.writeValue(file, rules);

        // Append comments
        try (FileWriter w = new FileWriter(file, true)) {
            w.write("\n# Add more rules below:\n");
            w.write("# - name: POST /api/users\n");
            w.write("#   protocol: http\n");
            w.write("#   conditions: [{type: method, operator: equals, value: POST}]\n");
            w.write("#   responses: [{name: 成功, statusCode: 201, body: '{\"code\":0}'}]\n");
        }
    }

    private static void writeStartAgentScript(File file) throws Exception {
        try (PrintWriter w = new PrintWriter(file)) {
            w.println("#!/bin/bash");
            w.println("# Baafoo Agent Start Script");
            w.println("# Add to your JVM args: -javaagent:baafoo-agent.jar=./baafoo-agent.yml");
            w.println("");
            w.println("echo \"To start your app with Baafoo Agent, add this JVM argument:\"");
            w.println("echo \"  -javaagent:/path/to/baafoo-agent.jar=./baafoo-agent.yml\"");
            w.println("echo \"\"");
            w.println("echo \"Example:\"");
            w.println("echo \"  java -javaagent:baafoo-agent.jar=./baafoo-agent.yml -jar your-app.jar\"");
        }
    }

    private static void writeStartAgentBat(File file) throws Exception {
        try (PrintWriter w = new PrintWriter(file)) {
            w.println("@echo off");
            w.println("REM Baafoo Agent Start Script");
            w.println("echo To start your app with Baafoo Agent, add this JVM argument:");
            w.println("echo   -javaagent:baafoo-agent.jar=./baafoo-agent.yml");
            w.println("echo.");
            w.println("echo Example:");
            w.println("echo   java -javaagent:baafoo-agent.jar=./baafoo-agent.yml -jar your-app.jar");
        }
    }

    private static void writeStartServerScript(File file) throws Exception {
        try (PrintWriter w = new PrintWriter(file)) {
            w.println("#!/bin/bash");
            w.println("# Baafoo Server Start Script");
            w.println("java -jar baafoo-server.jar ./baafoo-server.yml");
        }
    }

    private static void writeStartServerBat(File file) throws Exception {
        try (PrintWriter w = new PrintWriter(file)) {
            w.println("@echo off");
            w.println("REM Baafoo Server Start Script");
            w.println("java -jar baafoo-server.jar ./baafoo-server.yml");
        }
    }

    private static void printHelp() {
        System.out.println("Baafoo CLI - API Mock Platform");
        System.out.println();
        System.out.println("Usage: baafoo <command> [options]");
        System.out.println();
        System.out.println("Commands:");
        System.out.println("  init [dir]     Initialize a Baafoo project in the given directory");
        System.out.println("  version        Show version");
        System.out.println("  help           Show this help");
        System.out.println();
        System.out.println("Quick start:");
        System.out.println("  baafoo init my-project");
        System.out.println("  cd my-project");
        System.out.println("  ./start-server.sh");
    }
}
