package com.baafoo.testapp.caller;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

import com.baafoo.testapp.BaafooTestApp;

public class HttpCaller implements BaafooTestApp.Caller {

    private static final String TARGET_HOST = "httpbin.org";
    private static final int TARGET_PORT = 80;

    private final String serverUrl;

    public HttpCaller(String serverUrl) {
        this.serverUrl = serverUrl;
    }

    @Override
    public String name() {
        return "HTTP 外调测试 (目标: " + TARGET_HOST + ":" + TARGET_PORT + ")";
    }

    @Override
    public void run() throws Exception {
        testGet();
        testPost();
        testPut();
        testDelete();
        testWithHeaders();
        testWithQueryParams();
    }

    private void testGet() throws Exception {
        System.out.println("  [GET] http://" + TARGET_HOST + "/get");
        HttpURLConnection conn = (HttpURLConnection) new URL("http://" + TARGET_HOST + "/get").openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        printResponse(conn);
    }

    private void testPost() throws Exception {
        System.out.println("  [POST] http://" + TARGET_HOST + "/post");
        HttpURLConnection conn = (HttpURLConnection) new URL("http://" + TARGET_HOST + "/post").openConnection();
        conn.setRequestMethod("POST");
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        conn.setDoOutput(true);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);

        byte[] body = "{\"test\":\"baafoo\",\"protocol\":\"http\"}".getBytes(StandardCharsets.UTF_8);
        OutputStream os = conn.getOutputStream();
        os.write(body);
        os.close();
        printResponse(conn);
    }

    private void testPut() throws Exception {
        System.out.println("  [PUT] http://" + TARGET_HOST + "/put");
        HttpURLConnection conn = (HttpURLConnection) new URL("http://" + TARGET_HOST + "/put").openConnection();
        conn.setRequestMethod("PUT");
        conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
        conn.setDoOutput(true);
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);

        byte[] body = "{\"test\":\"baafoo-put\"}".getBytes(StandardCharsets.UTF_8);
        OutputStream os = conn.getOutputStream();
        os.write(body);
        os.close();
        printResponse(conn);
    }

    private void testDelete() throws Exception {
        System.out.println("  [DELETE] http://" + TARGET_HOST + "/delete");
        HttpURLConnection conn = (HttpURLConnection) new URL("http://" + TARGET_HOST + "/delete").openConnection();
        conn.setRequestMethod("DELETE");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        printResponse(conn);
    }

    private void testWithHeaders() throws Exception {
        System.out.println("  [GET+Headers] http://" + TARGET_HOST + "/get (X-Test-Header: baafoo)");
        HttpURLConnection conn = (HttpURLConnection) new URL("http://" + TARGET_HOST + "/get").openConnection();
        conn.setRequestMethod("GET");
        conn.setRequestProperty("X-Test-Header", "baafoo");
        conn.setRequestProperty("Accept", "application/json");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        printResponse(conn);
    }

    private void testWithQueryParams() throws Exception {
        System.out.println("  [GET+Query] http://" + TARGET_HOST + "/get?foo=bar&baz=qux");
        HttpURLConnection conn = (HttpURLConnection) new URL("http://" + TARGET_HOST + "/get?foo=bar&baz=qux").openConnection();
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(5000);
        conn.setReadTimeout(5000);
        printResponse(conn);
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
            System.out.println();
        } finally {
            conn.disconnect();
        }
    }
}
