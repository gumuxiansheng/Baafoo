package com.baafoo.cli;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.*;
import java.util.*;

/**
 * Baafoo CLI — Quick project initialization tool.
 *
 * <p>Usage: java -jar baafoo-cli.jar init [project-dir] [--non-interactive]</p>
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
        String targetDir = ".";
        boolean nonInteractive = false;

        for (int i = 1; i < args.length; i++) {
            if ("--non-interactive".equals(args[i])) {
                nonInteractive = true;
            } else {
                targetDir = args[i];
            }
        }

        switch (command) {
            case "init":
                if (nonInteractive) {
                    initNonInteractive(targetDir);
                } else {
                    initInteractive(targetDir);
                }
                break;
            case "version":
                System.out.println("Baafoo CLI v1.0.0-SNAPSHOT");
                break;
            default:
                System.err.println("Unknown command: " + command);
                printHelp();
        }
    }

    // --- Interactive mode ---

    private static void initInteractive(String targetDir) {
        File dir = new File(targetDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));

        System.out.println("=== Baafoo Interactive Init ===");
        System.out.println("Project directory: " + dir.getAbsolutePath());
        System.out.println();

        try {
            // 1. Ask for downstream services
            String servicesInput = prompt(reader,
                    "What downstream services does your application depend on?",
                    "",
                    "(comma-separated, e.g., \"order-service:8080,payment-service:9090,kafka-cluster:9092\")");

            // Parse services
            List<ServiceDef> services = parseServices(servicesInput);
            if (services.isEmpty()) {
                System.out.println("  No services specified, using defaults.");
            } else {
                System.out.println("  Detected services:");
                for (ServiceDef s : services) {
                    System.out.println("    - " + s.name + ":" + s.port);
                }
            }

            // 2. Ask for protocols
            String defaultProtocols = inferProtocols(services);
            String protocolsInput = prompt(reader,
                    "Which protocols?",
                    defaultProtocols,
                    "(http/tcp/kafka/pulsar/jms, comma-separated)");

            List<String> protocols = parseProtocols(protocolsInput);
            System.out.println("  Protocols: " + protocols);

            // 3. Ask for server host
            String serverHost = prompt(reader,
                    "Server host?",
                    "127.0.0.1",
                    "");

            // 4. Ask for environment name
            String environment = prompt(reader,
                    "Environment name?",
                    "default",
                    "");

            // Check for existing files and prompt for overwrite
            File agentConfig = new File(dir, "baafoo-agent.yml");
            File rulesFile = new File(dir, "baafoo-rules.yml");

            if (agentConfig.exists() || rulesFile.exists()) {
                String overwrite = prompt(reader,
                        "Config files already exist. Overwrite?",
                        "no",
                        "(yes/no)");
                if (!"yes".equalsIgnoreCase(overwrite) && !"y".equalsIgnoreCase(overwrite)) {
                    System.out.println("Aborted. No files were overwritten.");
                    return;
                }
            }

            // Generate configs
            writeAgentConfig(agentConfig, serverHost, environment, protocols);
            System.out.println("  [OK] baafoo-agent.yml");

            File serverConfig = new File(dir, "baafoo-server.yml");
            writeServerConfig(serverConfig, protocols);
            System.out.println("  [OK] baafoo-server.yml");

            writeExampleRules(rulesFile, services);
            System.out.println("  [OK] baafoo-rules.yml");

            File startAgentSh = new File(dir, "start-agent.sh");
            writeStartAgentScript(startAgentSh, serverHost);
            startAgentSh.setExecutable(true);
            System.out.println("  [OK] start-agent.sh");

            File startAgentBat = new File(dir, "start-agent.bat");
            writeStartAgentBat(startAgentBat, serverHost);
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
            System.out.println("JVM startup parameter example:");
            System.out.println();
            System.out.println("  java -javaagent:baafoo-agent.jar=./baafoo-agent.yml -jar your-app.jar");
            System.out.println();
            System.out.println("For Java 9+:");
            System.out.println();
            System.out.println("  java --add-opens java.base/java.net=ALL-UNNAMED -javaagent:baafoo-agent.jar=./baafoo-agent.yml -jar your-app.jar");
            System.out.println();
            System.out.println("Next steps:");
            System.out.println("  1. cd " + targetDir);
            System.out.println("  2. Start the server:    ./start-server.sh");
            System.out.println("  3. Start your app with the JVM parameter above");
            System.out.println("  4. Open Web console:    http://" + serverHost + ":8084/__baafoo__/");
            System.out.println();

        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            System.exit(1);
        }
    }

    private static String prompt(BufferedReader reader, String question, String defaultVal, String hint) throws IOException {
        System.out.print(question);
        if (hint != null && !hint.isEmpty()) {
            System.out.print(" " + hint);
        }
        if (defaultVal != null && !defaultVal.isEmpty()) {
            System.out.print(" [" + defaultVal + "]");
        }
        System.out.print(": ");

        String line = reader.readLine();
        if (line == null) line = "";
        line = line.trim();

        return line.isEmpty() ? defaultVal : line;
    }

    private static List<ServiceDef> parseServices(String input) {
        List<ServiceDef> services = new ArrayList<ServiceDef>();
        if (input == null || input.isEmpty()) return services;

        String[] parts = input.split(",");
        for (String part : parts) {
            part = part.trim();
            if (part.isEmpty()) continue;

            int colonIdx = part.lastIndexOf(':');
            if (colonIdx > 0) {
                String name = part.substring(0, colonIdx).trim();
                String portStr = part.substring(colonIdx + 1).trim();
                try {
                    int port = Integer.parseInt(portStr);
                    services.add(new ServiceDef(name, port));
                } catch (NumberFormatException e) {
                    services.add(new ServiceDef(part, 8080));
                }
            } else {
                services.add(new ServiceDef(part, 8080));
            }
        }
        return services;
    }

    private static String inferProtocols(List<ServiceDef> services) {
        Set<String> protocols = new LinkedHashSet<String>();
        for (ServiceDef s : services) {
            String name = s.name.toLowerCase();
            if (name.contains("kafka")) {
                protocols.add("kafka");
            } else if (name.contains("pulsar")) {
                protocols.add("pulsar");
            } else if (name.contains("jms") || name.contains("mq") || name.contains("queue")) {
                protocols.add("jms");
            } else {
                protocols.add("http");
            }
        }
        if (protocols.isEmpty()) {
            protocols.add("http");
            protocols.add("tcp");
        }
        StringBuilder sb = new StringBuilder();
        for (String p : protocols) {
            if (sb.length() > 0) sb.append(",");
            sb.append(p);
        }
        return sb.toString();
    }

    private static List<String> parseProtocols(String input) {
        List<String> protocols = new ArrayList<String>();
        if (input == null || input.isEmpty()) {
            protocols.add("http");
            return protocols;
        }
        String[] parts = input.split(",");
        for (String part : parts) {
            part = part.trim().toLowerCase();
            if (!part.isEmpty() && !protocols.contains(part)) {
                protocols.add(part);
            }
        }
        return protocols;
    }

    // --- Non-interactive mode (original behavior) ---

    private static void initNonInteractive(String targetDir) {
        File dir = new File(targetDir);
        if (!dir.exists()) {
            dir.mkdirs();
        }

        System.out.println("=== Baafoo Init === ");
        System.out.println("Project directory: " + dir.getAbsolutePath());
        System.out.println();

        try {
            File agentConfig = new File(dir, "baafoo-agent.yml");
            writeAgentConfig(agentConfig, "127.0.0.1", "dev", Arrays.asList("http", "tcp"));
            System.out.println("  [OK] baafoo-agent.yml");

            File serverConfig = new File(dir, "baafoo-server.yml");
            writeServerConfig(serverConfig, Arrays.asList("http", "tcp", "kafka", "pulsar", "jms"));
            System.out.println("  [OK] baafoo-server.yml");

            File rulesFile = new File(dir, "baafoo-rules.yml");
            writeExampleRules(rulesFile, new ArrayList<ServiceDef>());
            System.out.println("  [OK] baafoo-rules.yml");

            File startAgentSh = new File(dir, "start-agent.sh");
            writeStartAgentScript(startAgentSh, "127.0.0.1");
            startAgentSh.setExecutable(true);
            System.out.println("  [OK] start-agent.sh");

            File startAgentBat = new File(dir, "start-agent.bat");
            writeStartAgentBat(startAgentBat, "127.0.0.1");
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

    // --- Config writers ---

    private static void writeAgentConfig(File file, String serverHost, String environment, List<String> protocols) throws Exception {
        Map<String, Object> config = new LinkedHashMap<String, Object>();
        config.put("agentId", "agent-" + UUID.randomUUID().toString().substring(0, 8));
        config.put("environment", environment);
        config.put("serverUrl", "http://" + serverHost + ":8084");
        config.put("heartbeatIntervalSec", 30);
        config.put("pollIntervalSec", 10);
        config.put("consulEnabled", false);
        config.put("consulAddress", "localhost:8500");
        config.put("protocols", protocols);
        config.put("hotReload", true);
        config.put("connectionRetries", 3);
        config.put("retryBackoffMs", 1000);

        try (FileWriter w = new FileWriter(file)) {
            w.write("# Baafoo Agent Configuration\n");
            w.write("# See docs for full configuration reference\n\n");
            YAML_MAPPER.writeValue(w, config);
        }
    }

    private static void writeServerConfig(File file, List<String> protocols) throws Exception {
        Map<String, Object> config = new LinkedHashMap<String, Object>();
        config.put("httpPort", 8084);

        Map<String, Integer> ports = new LinkedHashMap<String, Integer>();
        if (protocols.contains("http")) ports.put("http", 9000);
        if (protocols.contains("tcp")) ports.put("tcp", 9001);
        if (protocols.contains("kafka")) ports.put("kafka", 9002);
        if (protocols.contains("pulsar")) ports.put("pulsar", 9003);
        if (protocols.contains("jms")) ports.put("jms", 9004);
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

    private static void writeExampleRules(File file, List<ServiceDef> services) throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append("# Baafoo Example Rules\n");
        sb.append("# Import via Web Console or place in data/rules/\n\n");

        List<Map<String, Object>> rules = new ArrayList<Map<String, Object>>();

        if (services.isEmpty()) {
            // Default example rule
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
            r1.put("name", "Success");
            r1.put("statusCode", 200);
            r1.put("body", "{\"code\":0,\"data\":[],\"message\":\"success\"}");
            responses1.add(r1);
            rule1.put("responses", responses1);

            rules.add(rule1);
        } else {
            // Generate rules based on user-specified services
            int idx = 1;
            for (ServiceDef svc : services) {
                String protocol = inferProtocolForService(svc.name);

                Map<String, Object> rule = new LinkedHashMap<String, Object>();
                rule.put("id", "example-" + idx);
                rule.put("name", svc.name + " mock rule");
                rule.put("protocol", protocol);
                rule.put("host", svc.name);
                rule.put("port", svc.port);
                rule.put("enabled", true);
                rule.put("priority", 10);

                List<Map<String, String>> conditions = new ArrayList<Map<String, String>>();
                if ("http".equals(protocol)) {
                    Map<String, String> c = new LinkedHashMap<String, String>();
                    c.put("type", "method");
                    c.put("operator", "equals");
                    c.put("value", "GET");
                    conditions.add(c);
                }
                rule.put("conditions", conditions);

                List<Map<String, Object>> responses = new ArrayList<Map<String, Object>>();
                Map<String, Object> resp = new LinkedHashMap<String, Object>();
                resp.put("name", "Default response");
                resp.put("statusCode", 200);
                resp.put("body", "{\"code\":0,\"message\":\"mocked\"}");
                responses.add(resp);
                rule.put("responses", responses);

                rules.add(rule);
                idx++;
            }
        }

        YAML_MAPPER.writeValue(file, rules);

        // Append comments
        try (FileWriter w = new FileWriter(file, true)) {
            w.write("\n# Add more rules below:\n");
            w.write("# - name: POST /api/users\n");
            w.write("#   protocol: http\n");
            w.write("#   conditions: [{type: method, operator: equals, value: POST}]\n");
            w.write("#   responses: [{name: Success, statusCode: 201, body: '{\"code\":0}'}]\n");
        }
    }

    private static String inferProtocolForService(String name) {
        String lower = name.toLowerCase();
        if (lower.contains("kafka")) return "kafka";
        if (lower.contains("pulsar")) return "pulsar";
        if (lower.contains("jms") || lower.contains("mq") || lower.contains("queue")) return "jms";
        return "http";
    }

    private static void writeStartAgentScript(File file, String serverHost) throws Exception {
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
            w.println("echo \"\"");
            w.println("echo \"For Java 9+:\"");
            w.println("echo \"  java --add-opens java.base/java.net=ALL-UNNAMED -javaagent:baafoo-agent.jar=./baafoo-agent.yml -jar your-app.jar\"");
        }
    }

    private static void writeStartAgentBat(File file, String serverHost) throws Exception {
        try (PrintWriter w = new PrintWriter(file)) {
            w.println("@echo off");
            w.println("REM Baafoo Agent Start Script");
            w.println("echo To start your app with Baafoo Agent, add this JVM argument:");
            w.println("echo   -javaagent:baafoo-agent.jar=./baafoo-agent.yml");
            w.println("echo.");
            w.println("echo Example:");
            w.println("echo   java -javaagent:baafoo-agent.jar=./baafoo-agent.yml -jar your-app.jar");
            w.println("echo.");
            w.println("echo For Java 9+:");
            w.println("echo   java --add-opens java.base/java.net=ALL-UNNAMED -javaagent:baafoo-agent.jar=./baafoo-agent.yml -jar your-app.jar");
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
        System.out.println("  init [dir] [--non-interactive]  Initialize a Baafoo project");
        System.out.println("  version                         Show version");
        System.out.println("  help                            Show this help");
        System.out.println();
        System.out.println("Options:");
        System.out.println("  --non-interactive   Skip interactive prompts, use defaults");
        System.out.println();
        System.out.println("Quick start:");
        System.out.println("  baafoo init my-project");
        System.out.println("  cd my-project");
        System.out.println("  ./start-server.sh");
    }

    // --- Inner classes ---

    private static class ServiceDef {
        final String name;
        final int port;

        ServiceDef(String name, int port) {
            this.name = name;
            this.port = port;
        }
    }
}
