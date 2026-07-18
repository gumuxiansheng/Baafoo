package com.baafoo.testapp.caller;

import com.baafoo.testapp.BaafooTestApp;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class ConsulHttpCaller implements BaafooTestApp.Caller {

    private static final String CONSUL_HOST = "consul-server";
    private static final int CONSUL_PORT = 8500;

    @Override
    public String name() {
        return "Consul HTTP API 外调测试 (目标: " + CONSUL_HOST + ":" + CONSUL_PORT + ")";
    }

    @Override
    public void run() throws Exception {
        testHealthService();
        testKvGet();
    }

    private void testHealthService() throws Exception {
        String url = "http://" + CONSUL_HOST + ":" + CONSUL_PORT + "/v1/health/service/my-service";
        System.out.println("  [GET] " + url);
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            printResponse(conn);
        } catch (java.net.ConnectException e) {
            System.out.println("    连接失败: " + e.getMessage());
            System.out.println("    (无 Agent 时连接失败属正常行为)");
        } catch (java.net.UnknownHostException e) {
            System.out.println("    DNS 解析失败: " + e.getMessage());
            System.out.println("    (无 Agent 时 DNS 解析失败属正常行为)");
        }
        System.out.println();
    }

    private void testKvGet() throws Exception {
        String url = "http://" + CONSUL_HOST + ":" + CONSUL_PORT + "/v1/kv/config/baafoo";
        System.out.println("  [GET] " + url);
        try {
            HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            printResponse(conn);
        } catch (java.net.ConnectException e) {
            System.out.println("    连接失败: " + e.getMessage());
            System.out.println("    (无 Agent 时连接失败属正常行为)");
        } catch (java.net.UnknownHostException e) {
            System.out.println("    DNS 解析失败: " + e.getMessage());
            System.out.println("    (无 Agent 时 DNS 解析失败属正常行为)");
        }
        System.out.println();
    }

    private void printResponse(HttpURLConnection conn) throws Exception {
        // H-7: 在 finally 中 disconnect 释放底层 socket，避免连接泄漏
        try {
            int code = conn.getResponseCode();
            String stubHeader = conn.getHeaderField("X-Baafoo-Stub");
            String ruleId = conn.getHeaderField("X-Baafoo-Rule-Id");

            InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String body = "";
            if (is != null) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line);
                }
                reader.close();
                body = sb.toString();
                if (body.length() > 500) {
                    body = body.substring(0, 500) + "... (truncated)";
                }
            }

            boolean stubbed = "true".equals(stubHeader);
            System.out.println("    状态码: " + code);
            System.out.println("    挡板拦截: " + (stubbed ? "✓ 是" : "✗ 否"));
            if (ruleId != null) {
                System.out.println("    匹配规则: " + ruleId);
            }
            System.out.println("    响应体: " + body);
        } finally {
            conn.disconnect();
        }
    }
}
