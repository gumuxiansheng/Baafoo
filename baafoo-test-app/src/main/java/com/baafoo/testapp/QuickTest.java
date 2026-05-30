package com.baafoo.testapp;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class QuickTest {
    public static void main(String[] args) throws Exception {
        System.out.println("=== Baafoo Quick Connectivity Test ===");
        System.out.println();

        System.out.println("[1] HTTP call to httpbin.org/get...");
        try {
            URL url = new URL("http://httpbin.org/get");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            int code = conn.getResponseCode();
            String stubHeader = conn.getHeaderField("X-Baafoo-Stub");
            String ruleId = conn.getHeaderField("X-Baafoo-Rule-Id");
            System.out.println("    Status: " + code);
            System.out.println("    X-Baafoo-Stub: " + stubHeader);
            System.out.println("    X-Baafoo-Rule-Id: " + ruleId);

            InputStream is = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            if (is != null) {
                BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                reader.close();
                String body = sb.toString();
                if (body.length() > 500) body = body.substring(0, 500) + "...";
                System.out.println("    Body: " + body);
            }

            boolean intercepted = "true".equals(stubHeader);
            System.out.println("    RESULT: " + (intercepted ? "INTERCEPTED by Baafoo" : "NOT intercepted"));
        } catch (java.net.ConnectException e) {
            System.out.println("    ConnectException: " + e.getMessage());
            System.out.println("    (Connection refused — Agent may have redirected to a port that's not listening)");
        } catch (Exception e) {
            System.out.println("    Exception: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        System.out.println();
        System.out.println("[2] Baafoo Server management API...");
        try {
            URL url = new URL("http://127.0.0.1:8080/__baafoo__/api/rules");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            int code = conn.getResponseCode();
            System.out.println("    Status: " + code);
            InputStream is = conn.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();
            String body = sb.toString();
            if (body.length() > 300) body = body.substring(0, 300) + "...";
            System.out.println("    Body: " + body);
            System.out.println("    RESULT: Server is UP");
        } catch (Exception e) {
            System.out.println("    Exception: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        System.out.println();
        System.out.println("[3] TCP Socket to 127.0.0.1:9999...");
        try {
            Socket socket = new Socket();
            socket.setSoTimeout(5000);
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", 9999), 5000);
            System.out.println("    Connected to: " + socket.getRemoteSocketAddress());
            System.out.println("    Local: " + socket.getLocalAddress() + ":" + socket.getLocalPort());
            boolean redirected = socket.getPort() != 9999;
            System.out.println("    RESULT: " + (redirected ? "INTERCEPTED" : "Direct connection"));
            socket.close();
        } catch (java.net.ConnectException e) {
            System.out.println("    ConnectException: " + e.getMessage());
            System.out.println("    (Port not listening — expected if no TCP stub rule or Agent not intercepting)");
        } catch (Exception e) {
            System.out.println("    Exception: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        System.out.println();
        System.out.println("[4] Direct HTTP to Baafoo stub port 9000 with Host: httpbin.org...");
        try {
            URL url = new URL("http://127.0.0.1:9000/get");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Host", "httpbin.org");
            conn.setConnectTimeout(3000);
            conn.setReadTimeout(3000);
            int code = conn.getResponseCode();
            String stubHeader = conn.getHeaderField("X-Baafoo-Stub");
            System.out.println("    Status: " + code);
            System.out.println("    X-Baafoo-Stub: " + stubHeader);
            InputStream is = conn.getInputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) sb.append(line);
            reader.close();
            System.out.println("    Body: " + sb.toString());
            System.out.println("    RESULT: " + ("true".equals(stubHeader) ? "Stub working!" : "Stub not matching"));
        } catch (Exception e) {
            System.out.println("    Exception: " + e.getClass().getSimpleName() + ": " + e.getMessage());
        }

        System.out.println();
        System.out.println("=== Quick Test Complete ===");
    }
}
