package com.baafoo.testapp;

import com.baafoo.testapp.caller.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class BaafooTestApp {

    private static final String SEPARATOR = "═══════════════════════════════════════════════════════════";

    private static final String SERVER_URL = "http://127.0.0.1:8084";

    public static void main(String[] args) throws Exception {
        String serverUrl = args.length > 0 ? args[0] : SERVER_URL;

        BufferedReader reader = new BufferedReader(new InputStreamReader(System.in));

        Map<String, Caller> callers = new LinkedHashMap<String, Caller>();
        callers.put("1", new HttpCaller(serverUrl));
        callers.put("2", new TcpCaller());
        callers.put("3", new NioTcpCaller());
        callers.put("4", new KafkaCaller());
        callers.put("5", new PulsarCaller());
        callers.put("6", new JmsCaller());
        callers.put("7", new ConsulDnsCaller());
        callers.put("8", new ConsulHttpCaller());
        callers.put("9", new OkHttpCaller());
        callers.put("10", new FeignCaller());
        callers.put("11", new GrpcCaller());

        System.out.println(SEPARATOR);
        System.out.println("  Baafoo 挡板测试应用 — 多协议外调测试");
        System.out.println("  Server: " + serverUrl);
        System.out.println(SEPARATOR);

        while (true) {
            System.out.println();
            System.out.println("┌─────────────────────────────────────────────────┐");
            System.out.println("│  选择测试项:                                      │");
            System.out.println("│                                                   │");
            System.out.println("│  0  — 一键设置挡板规则 (Setup Rules)              │");
            System.out.println("│  1  — HTTP 外调测试                               │");
            System.out.println("│  2  — TCP Socket 外调测试                         │");
            System.out.println("│  3  — NIO Socket 外调测试                         │");
            System.out.println("│  4  — Kafka 外调测试                              │");
            System.out.println("│  5  — Pulsar 外调测试                             │");
            System.out.println("│  6  — JMS 外调测试                                │");
            System.out.println("│  7  — Consul DNS 外调测试                         │");
            System.out.println("│  8  — Consul HTTP API 外调测试                    │");
            System.out.println("│  9  — OkHttp 外调测试                             │");
            System.out.println("│  10 — Feign+OkHttp 外调测试                       │");
            System.out.println("│  11 — gRPC 外调测试                                │");
        System.out.println("│  A  — 全部运行 (Run All)                          │");
            System.out.println("│  Q  — 退出                                        │");
            System.out.println("└─────────────────────────────────────────────────┘");
            System.out.print("请输入选项: ");

            String line = reader.readLine();
            if (line == null) break;
            line = line.trim().toUpperCase();

            if ("Q".equals(line)) {
                System.out.println("退出测试应用。");
                break;
            }

            if ("0".equals(line)) {
                setupRules(serverUrl);
                continue;
            }

            if ("A".equals(line)) {
                runAll(callers);
                continue;
            }

            Caller caller = callers.get(line);
            if (caller != null) {
                runSingle(caller);
            } else {
                System.out.println("无效选项: " + line);
            }
        }
    }

    private static void setupRules(String serverUrl) {
        System.out.println();
        System.out.println("── 设置挡板规则 ──");
        RuleSetup setup = new RuleSetup(serverUrl);
        List<String> results = setup.setupAll();
        for (String r : results) {
            System.out.println("  " + r);
        }
        System.out.println("── 规则设置完成 ──");
    }

    private static void runSingle(Caller caller) {
        System.out.println();
        System.out.println(SEPARATOR);
        System.out.println("  ▶ " + caller.name());
        System.out.println(SEPARATOR);
        try {
            caller.run();
        } catch (Exception e) {
            // L-3: Stack trace goes to stderr (the conventional stream for errors) so that
            // stdout stays clean for the test result table.
            System.out.println("  ✗ 异常: " + e.getMessage());
            e.printStackTrace(System.err);
        }
    }

    private static void runAll(Map<String, Caller> callers) {
        System.out.println();
        System.out.println("═══════════════════════════════════════════════════");
        System.out.println("  ▶ 运行全部协议外调测试");
        System.out.println("═══════════════════════════════════════════════════");

        List<String> passed = new ArrayList<String>();
        List<String> failed = new ArrayList<String>();

        for (Caller caller : callers.values()) {
            System.out.println();
            System.out.println("── " + caller.name() + " ──");
            try {
                caller.run();
                passed.add(caller.name());
            } catch (Exception e) {
                System.out.println("  ✗ 异常: " + e.getMessage());
                failed.add(caller.name() + " (" + e.getMessage() + ")");
            }
        }

        System.out.println();
        System.out.println("═══════════════════════════════════════════════════");
        System.out.println("  测试结果汇总");
        System.out.println("═══════════════════════════════════════════════════");
        System.out.println("  通过: " + passed.size());
        for (String p : passed) {
            System.out.println("    ✓ " + p);
        }
        System.out.println("  失败: " + failed.size());
        for (String f : failed) {
            System.out.println("    ✗ " + f);
        }
        System.out.println("═══════════════════════════════════════════════════");
    }

    public interface Caller {
        String name();
        void run() throws Exception;
    }
}
